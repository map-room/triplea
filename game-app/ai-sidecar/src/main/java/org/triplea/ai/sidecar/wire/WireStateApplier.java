package org.triplea.ai.sidecar.wire;

import games.strategy.engine.data.Change;
import games.strategy.engine.data.CompositeChange;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.RelationshipType;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.attachments.TechAttachment;
import games.strategy.triplea.delegate.battle.BattleDelegate;
import games.strategy.triplea.delegate.battle.BattleTracker;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import org.triplea.java.collections.IntegerMap;

/**
 * Applies a {@link WireState} onto a session's cloned {@link GameData}, mutating it to reflect
 * territory ownership, unit layout, player PU totals, and tech flags sent by Map Room.
 *
 * <p><b>Scope:</b> this is the <em>static</em> state applier. It reconstructs the board (who owns
 * what, which units stand where, how many PUs each player has, which techs are unlocked). It
 * deliberately <b>does not</b> populate {@code BattleTracker} or construct any {@code IBattle}.
 * Battle / air-battle / combat-phase transient state is the responsibility of the per-kind
 * executors (Tasks 22–24), each of which synthesizes an {@code IBattle}, registers it with {@code
 * BattleDelegate.getBattleTracker()}, and then invokes the appropriate ProAi method (see {@code
 * AbstractProAi.selectCasualties / retreatQuery / scrambleUnitsQuery}).
 *
 * <p><b>Mutation idiom:</b> all mutations go through {@link ChangeFactory} + {@link
 * GameData#performChange(Change)} — not direct setters — because that is the only path that fires
 * the game-data change listeners TripleA code relies on elsewhere.
 *
 * <p><b>Unit identity:</b> Map Room addresses units by a stable string {@code unitId}; TripleA
 * addresses them by {@link UUID}. The applier is handed a concurrent map that it populates lazily
 * on first encounter with a given Map Room unit ID and reuses on subsequent applies so that the
 * same Map Room unit always resolves to the same TripleA {@link Unit#getId()}.
 */
public final class WireStateApplier {

  private static final Logger LOG = System.getLogger(WireStateApplier.class.getName());

  private WireStateApplier() {}

  /**
   * Mutates {@code gameData} in place so it matches {@code wire}. Round / phase / currentPlayer are
   * treated as read-only metadata: a mismatch logs a warning but does not throw.
   *
   * <p>Callers must not invoke {@code apply()} concurrently on the same {@code (gameData,
   * unitIdMap)} pair; serialise per-session.
   *
   * @throws IllegalArgumentException if the wire references a territory, player, unit type, or
   *     resource that does not exist on the canonical map — those indicate a caller bug, not a
   *     recoverable condition.
   */
  public static void apply(
      final GameData gameData, final WireState wire, final ConcurrentMap<String, UUID> unitIdMap) {
    final CompositeChange changes = new CompositeChange();

    for (final WireTerritory wt : wire.territories()) {
      applyTerritory(gameData, wt, unitIdMap, changes);
    }

    for (final WirePlayer wp : wire.players()) {
      applyPlayer(gameData, wp, changes);
    }

    if (!changes.isEmpty()) {
      gameData.performChange(changes);
    }

    // Hydrate RelationshipTracker directly — must happen before the politics step (Task 8) runs
    // on this same GameData so that invokePoliticsForSidecar sees the current relationship graph.
    // Direct setter is used (not ChangeFactory.relationshipChange) to ensure in-memory visibility
    // without relying on change-listener ordering (see map-room#1824 phase-ordering constraint).
    applyRelationships(gameData, wire);

    // Phase 3 branches: conquered-this-turn, factory operational damage, and round/step.
    // These run AFTER the main CompositeChange has been performed so that any units the
    // damage branch references are live on the board.
    applyConqueredThisTurn(gameData, wire);
    applyOperationalDamage(gameData, wire, unitIdMap);
    applyUnitProperties(gameData, wire, unitIdMap);
    // Round/step last — GameSequence mutation has no interaction with the earlier branches,
    // but keeping it at the tail means nothing downstream can overwrite it.
    applyRoundAndStep(gameData, wire);
    WireStateVerifier.verifyApply(gameData, wire, unitIdMap);
  }

  /**
   * Reconcile the session's {@link BattleTracker#getConquered() conquered} set to match the wire
   * payload exactly. Territories flagged {@code conqueredThisTurn=true} are added; territories
   * flagged {@code false} are removed (clearing stale entries from a prior apply). Territories not
   * present in the wire payload at all are left untouched.
   *
   * <p>We cannot use {@code ChangeFactory.attachmentPropertyChange} here because TripleA does not
   * model conquered-this-turn as a {@link games.strategy.triplea.attachments.TerritoryAttachment}
   * field — it lives entirely on the (transient) {@link BattleTracker}.
   *
   * <p>Prior to this fix the method only added (never removed), so a territory conquered on turn N
   * and cleared on turn N+1 (TS-side {@code _conqueredThisTurn} reset) would remain in the
   * BattleTracker indefinitely, producing a steady-state drift warning every sync cycle.
   */
  private static void applyConqueredThisTurn(final GameData gameData, final WireState wire) {
    ensureBattleDelegate(gameData);
    final BattleTracker tracker = gameData.getBattleDelegate().getBattleTracker();
    for (final WireTerritory wt : wire.territories()) {
      final Territory t = gameData.getMap().getTerritoryOrNull(wt.territoryId());
      if (t == null) {
        continue;
      }
      if (wt.conqueredThisTurn()) {
        tracker.getConquered().add(t);
      } else {
        tracker.getConquered().remove(t);
      }
    }
  }

  /**
   * Apply {@link WireUnit#bombingDamage()} to every unit on every territory whose wire damage
   * differs from the live {@link Unit#getUnitDamage()} value. {@link
   * ChangeFactory#bombingUnitDamage} semantics are <em>set, not add</em> — the {@link IntegerMap}
   * values are absolute new damage counts.
   */
  private static void applyOperationalDamage(
      final GameData gameData, final WireState wire, final ConcurrentMap<String, UUID> unitIdMap) {
    for (final WireTerritory wt : wire.territories()) {
      final Territory t = gameData.getMap().getTerritoryOrNull(wt.territoryId());
      if (t == null) {
        continue;
      }
      final IntegerMap<Unit> newDamage = new IntegerMap<>();
      for (final WireUnit wu : wt.units()) {
        final UUID uuid = unitIdMap.get(wu.unitId());
        if (uuid == null) {
          continue;
        }
        final Unit unit = findUnitById(t.getUnits(), uuid);
        if (unit == null) {
          continue;
        }
        if (wu.bombingDamage() != unit.getUnitDamage()) {
          newDamage.put(unit, wu.bombingDamage());
        }
      }
      if (!newDamage.isEmpty()) {
        gameData.performChange(
            ChangeFactory.bombingUnitDamage(newDamage, Collections.singletonList(t)));
      }
    }
  }

  /**
   * Advance {@link GameData#getSequence()} to the wire-supplied round and step. This mirrors what
   * {@code GameSequence.setRoundAndStep} does for save-game export: the {@code display name} lookup
   * is case-insensitive and falls back to index 0 with an error log if no match is found — which is
   * why the {@link StepNameMapper} contract is narrow.
   */
  private static void applyRoundAndStep(final GameData gameData, final WireState wire) {
    // StepNameMapper covers all wired phases (purchase / combatMove / battle / nonCombatMove /
    // place). Any unmapped phase (e.g. tech, intelligence) leaves the sequence untouched and
    // logs a warning.
    final String javaStepName;
    try {
      javaStepName = StepNameMapper.toJavaStepName(wire.phase(), wire.currentPlayer());
    } catch (final IllegalArgumentException e) {
      LOG.log(
          Level.WARNING,
          () -> "WireState phase '" + wire.phase() + "' not mappable; skipping round/step apply");
      return;
    }
    final GamePlayer player = gameData.getPlayerList().getPlayerId(wire.currentPlayer());
    if (player == null) {
      throw new IllegalArgumentException("Unknown player in WireState: " + wire.currentPlayer());
    }
    // GameSequence.setRoundAndStep compares against GameStep.getDisplayName() — which for
    // Global 1940 falls back to the delegate's displayName ("Purchase Units" etc.) and is
    // thus the same across all players that share that delegate. The XML step <em>name</em>
    // ("germansPurchase") is what uniquely identifies the step; locate the target step by
    // name, then feed its own getDisplayName() + player back into setRoundAndStep so the
    // existing API resolves to the correct step index.
    GameStep target = null;
    for (final GameStep step : gameData.getSequence().getSteps()) {
      if (javaStepName.equalsIgnoreCase(step.getName()) && player.equals(step.getPlayerId())) {
        target = step;
        break;
      }
    }
    if (target == null) {
      LOG.log(
          Level.WARNING,
          () ->
              "No GameStep matched name '"
                  + javaStepName
                  + "' for player "
                  + wire.currentPlayer()
                  + "; leaving sequence untouched");
      return;
    }
    gameData.getSequence().setRoundAndStep(wire.round(), target.getDisplayName(), player);
  }

  /**
   * Re-register a fresh {@link BattleDelegate} if {@code postDeSerialize} cleared it on the cloned
   * {@link GameData}. Mirrors {@code ExecutorSupport.ensureBattleDelegate} which lives in the
   * {@code exec} package and is not visible here.
   */
  private static void ensureBattleDelegate(final GameData gameData) {
    if (gameData.getDelegateOptional("battle").isPresent()) {
      return;
    }
    final BattleDelegate delegate = new BattleDelegate();
    delegate.initialize("battle", "Combat");
    gameData.addDelegate(delegate);
  }

  private static void applyTerritory(
      final GameData gameData,
      final WireTerritory wt,
      final ConcurrentMap<String, UUID> unitIdMap,
      final CompositeChange out) {
    final Territory territory = gameData.getMap().getTerritoryOrNull(wt.territoryId());
    if (territory == null) {
      throw new IllegalArgumentException("Unknown territory in WireState: " + wt.territoryId());
    }

    final GamePlayer wireOwner = resolvePlayer(gameData, wt.owner());
    if (!territory.getOwner().equals(wireOwner)) {
      out.add(ChangeFactory.changeOwner(territory, wireOwner));
    }

    // Build the desired unit set, resolving Map-Room unit IDs to stable TripleA UUIDs.
    final List<Unit> desired = new ArrayList<>(wt.units().size());
    final Set<UUID> desiredIds = new HashSet<>();
    // Accumulated hit-count changes for units that already exist on the territory. New units
    // are still mutated directly below (pre-add, before any listeners can observe them).
    final IntegerMap<Unit> existingUnitHitDeltas = new IntegerMap<>();
    for (final WireUnit wu : wt.units()) {
      final UUID uuid = unitIdMap.computeIfAbsent(wu.unitId(), k -> UUID.randomUUID());
      desiredIds.add(uuid);
      final UnitType type = gameData.getUnitTypeList().getUnitType(wu.unitType()).orElse(null);
      if (type == null) {
        throw new IllegalArgumentException("Unknown unit type in WireState: " + wu.unitType());
      }
      // Per-unit owner: use the explicit owner from the wire when present, otherwise fall back
      // to the territory owner. The fallback preserves backward compatibility with clients that
      // do not send an owner field. The per-unit owner is critical for sea zones (territory
      // owner = Neutral) where multiple nations may have ships — without it the ProAI sees all
      // naval units as belonging to Neutral and misidentifies allies as enemies (#1776).
      final GamePlayer unitOwner =
          (wu.owner() != null && !wu.owner().isEmpty())
              ? resolvePlayer(gameData, wu.owner())
              : wireOwner;
      final Unit existing = findUnitById(territory.getUnits(), uuid);
      final Unit unit;
      final java.math.BigDecimal desiredAlreadyMoved = java.math.BigDecimal.valueOf(wu.movesUsed());
      if (existing != null) {
        unit = existing;
        if (wu.hitsTaken() != unit.getHits()) {
          // Route through ChangeFactory.unitsHit so game-data listeners fire. The IntegerMap
          // value is the absolute new hit count (ChangeFactory.unitsHit sets, not adds).
          existingUnitHitDeltas.put(unit, wu.hitsTaken());
        }
        // Pro AI's Non-Combat Move planner consults Unit.alreadyMoved when picking
        // landing destinations for air units (movesLeft = maxMovementAllowed -
        // alreadyMoved). Without this sync, every plane appears unmoved and the AI
        // generates landings the engine rejects (e.g., a tac bomber with 1 move
        // left planning a 5-hex landing).
        if (unit.getAlreadyMoved().compareTo(desiredAlreadyMoved) != 0) {
          out.add(
              ChangeFactory.unitPropertyChange(
                  unit, desiredAlreadyMoved, Unit.PropertyName.ALREADY_MOVED));
        }
        // Captured infrastructure (factories, airfields) stays in the same territory but
        // its owner changes to the capturing nation. Without this update the ProAI sees the
        // old owner on every subsequent apply and the verifier fires drift warnings every turn.
        if (!unitOwner.equals(unit.getOwner())) {
          out.add(ChangeFactory.changeOwner(List.of(unit), unitOwner, territory));
        }
      } else {
        unit = new Unit(uuid, type, unitOwner, gameData);
        // Register with the central UnitsList so {@code GameData.getUnits().get(uuid)}
        // resolves to this unit. The raw {@link Unit#Unit(UUID, UnitType, GamePlayer,
        // GameData)} constructor does not do this registration — only {@link
        // UnitType#create(int, GamePlayer, boolean)} does — and Phase 3 branches such as
        // {@link ChangeFactory#bombingUnitDamage} look units up by UUID via UnitsList.
        gameData.getUnits().put(unit);
        if (wu.hitsTaken() != unit.getHits()) {
          // Safe: unit is not yet attached to any territory, no listeners to bypass.
          unit.setHits(wu.hitsTaken());
        }
        // Same rationale as the existing-unit branch above; safe to set directly
        // because the unit is not yet attached to a territory.
        if (unit.getAlreadyMoved().compareTo(desiredAlreadyMoved) != 0) {
          unit.setAlreadyMoved(desiredAlreadyMoved);
        }
      }
      desired.add(unit);
    }
    if (!existingUnitHitDeltas.isEmpty()) {
      out.add(ChangeFactory.unitsHit(existingUnitHitDeltas, Collections.singletonList(territory)));
    }

    // Remove any live units not in the wire set.
    final List<Unit> toRemove = new ArrayList<>();
    for (final Unit u : territory.getUnits()) {
      if (!desiredIds.contains(u.getId())) {
        toRemove.add(u);
      }
    }
    if (!toRemove.isEmpty()) {
      out.add(ChangeFactory.removeUnits(territory, toRemove));
    }

    // Add any desired units not already present on the territory.
    final Set<UUID> presentIds = new HashSet<>();
    for (final Unit u : territory.getUnits()) {
      presentIds.add(u.getId());
    }
    final List<Unit> toAdd = new ArrayList<>();
    for (final Unit u : desired) {
      if (!presentIds.contains(u.getId())) {
        toAdd.add(u);
      }
    }
    if (!toAdd.isEmpty()) {
      out.add(ChangeFactory.addUnits(territory, toAdd));
    }
  }

  /**
   * Hydrate per-unit transient state (transportedBy, submerged, wasInCombat) after all units are
   * live on the board. Runs after the main {@link CompositeChange} so that transporter units
   * referenced by transportedBy are guaranteed to exist in game data.
   */
  private static void applyUnitProperties(
      final GameData gameData, final WireState wire, final ConcurrentMap<String, UUID> unitIdMap) {
    // Index every live unit by UUID for O(1) transport resolution across territories.
    final Map<UUID, Unit> allUnitsById = new HashMap<>();
    for (final Territory t : gameData.getMap().getTerritories()) {
      for (final Unit u : t.getUnits()) {
        allUnitsById.put(u.getId(), u);
      }
    }
    // Defense-in-depth: index wire units by Map Room id so we can check unloadedTo on cargo units
    // when building a transport's unloaded list (#2162 — stale cross-nation transport state).
    final Map<String, WireUnit> wireUnitById = new HashMap<>();
    for (final WireTerritory wt : wire.territories()) {
      for (final WireUnit wu : wt.units()) {
        wireUnitById.put(wu.unitId(), wu);
      }
    }

    for (final WireTerritory wt : wire.territories()) {
      final Territory t = gameData.getMap().getTerritoryOrNull(wt.territoryId());
      if (t == null) {
        continue;
      }
      final CompositeChange changes = new CompositeChange();
      for (final WireUnit wu : wt.units()) {
        final UUID uuid = unitIdMap.get(wu.unitId());
        if (uuid == null) {
          continue;
        }
        final Unit unit = allUnitsById.get(uuid);
        if (unit == null) {
          continue;
        }

        if (wu.submerged() != unit.getSubmerged()) {
          changes.add(
              ChangeFactory.unitPropertyChange(unit, wu.submerged(), Unit.PropertyName.SUBMERGED));
        }
        if (wu.wasInCombat() != unit.getWasInCombat()) {
          changes.add(
              ChangeFactory.unitPropertyChange(
                  unit, wu.wasInCombat(), Unit.PropertyName.WAS_IN_COMBAT));
        }
        if (wu.wasLoadedThisTurn() != unit.getWasLoadedThisTurn()) {
          changes.add(
              ChangeFactory.unitPropertyChange(
                  unit, wu.wasLoadedThisTurn(), Unit.PropertyName.LOADED_THIS_TURN));
        }
        if (wu.wasUnloadedInCombatPhase() != unit.getWasUnloadedInCombatPhase()) {
          changes.add(
              ChangeFactory.unitPropertyChange(
                  unit, wu.wasUnloadedInCombatPhase(), Unit.PropertyName.UNLOADED_IN_COMBAT_PHASE));
        }
        if (wu.bonusMovement() != unit.getBonusMovement()) {
          changes.add(
              ChangeFactory.unitPropertyChange(
                  unit, wu.bonusMovement(), Unit.PropertyName.BONUS_MOVEMENT));
        }
        if (wu.wasAmphibious() != unit.getWasAmphibious()) {
          changes.add(
              ChangeFactory.unitPropertyChange(
                  unit, wu.wasAmphibious(), Unit.PropertyName.UNLOADED_AMPHIBIOUS));
        }
        if (wu.wasScrambled() != unit.getWasScrambled()) {
          changes.add(
              ChangeFactory.unitPropertyChange(
                  unit, wu.wasScrambled(), Unit.PropertyName.WAS_SCRAMBLED));
        }
        if (wu.wasLoadedAfterCombat() != unit.getWasLoadedAfterCombat()) {
          changes.add(
              ChangeFactory.unitPropertyChange(
                  unit, wu.wasLoadedAfterCombat(), Unit.PropertyName.LOADED_AFTER_COMBAT));
        }
        if (wu.maxScrambleCount() != null && wu.maxScrambleCount() != unit.getMaxScrambleCount()) {
          changes.add(
              ChangeFactory.unitPropertyChange(
                  unit, wu.maxScrambleCount(), Unit.PropertyName.MAX_SCRAMBLE_COUNT));
        }
        if (wu.unloadedTo() != null) {
          final Territory unloadedToTerritory =
              gameData.getMap().getTerritoryOrNull(wu.unloadedTo());
          if (unloadedToTerritory != null && !unloadedToTerritory.equals(unit.getUnloadedTo())) {
            changes.add(
                ChangeFactory.unitPropertyChange(
                    unit, unloadedToTerritory, Unit.PropertyName.UNLOADED_TO));
          }
        } else if (unit.getUnloadedTo() != null) {
          changes.add(ChangeFactory.unitPropertyChange(unit, null, Unit.PropertyName.UNLOADED_TO));
        }
        if (wu.unloaded() != null && !wu.unloaded().isEmpty()) {
          final List<Unit> unloadedUnits = new ArrayList<>();
          for (final String unloadedId : wu.unloaded()) {
            // Skip cargo units whose wire state has no unloadedTo — this indicates stale
            // cross-nation transport state (#2162): the transport belongs to one nation but
            // the cargo belongs to another, so only the cargo's unloadedTo was cleared at
            // phase start while the transport's unloaded list was not. Propagating it to
            // Java would cause an NPE in TransportTracker.isTransportUnloadRestricted*.
            final WireUnit cargoWu = wireUnitById.get(unloadedId);
            if (cargoWu == null || cargoWu.unloadedTo() == null) {
              continue;
            }
            final UUID unloadedUuid = unitIdMap.get(unloadedId);
            if (unloadedUuid != null) {
              final Unit unloadedUnit = allUnitsById.get(unloadedUuid);
              if (unloadedUnit != null) {
                unloadedUnits.add(unloadedUnit);
              }
            }
          }
          if (!unloadedUnits.isEmpty() && !unloadedUnits.equals(unit.getUnloaded())) {
            changes.add(
                ChangeFactory.unitPropertyChange(unit, unloadedUnits, Unit.PropertyName.UNLOADED));
          }
        }

        final String wireTransportedById = wu.transportedBy();
        final Unit currentTransportedBy = unit.getTransportedBy();
        if (wireTransportedById != null) {
          final UUID transporterUuid = unitIdMap.get(wireTransportedById);
          if (transporterUuid != null) {
            final Unit transporter = allUnitsById.get(transporterUuid);
            if (transporter != null && !transporter.equals(currentTransportedBy)) {
              changes.add(
                  ChangeFactory.unitPropertyChange(
                      unit, transporter, Unit.PropertyName.TRANSPORTED_BY));
            }
          }
        } else if (currentTransportedBy != null) {
          changes.add(
              ChangeFactory.unitPropertyChange(unit, null, Unit.PropertyName.TRANSPORTED_BY));
        }
      }
      if (!changes.isEmpty()) {
        gameData.performChange(changes);
      }
    }
  }

  private static Unit findUnitById(final Collection<Unit> units, final UUID id) {
    for (final Unit u : units) {
      if (u.getId().equals(id)) {
        return u;
      }
    }
    return null;
  }

  private static void applyPlayer(
      final GameData gameData, final WirePlayer wp, final CompositeChange out) {
    final GamePlayer player = resolvePlayer(gameData, wp.playerId());

    // Clear the player's unit collection (holding pool). In a normal TripleA turn the holding
    // pool is empty between phases — any units present here are from a prior place-phase
    // simulation (invokePlaceForSidecar) where §20 validation rejected the placement so the unit
    // was never moved to the territory. Without this clear, stale units accumulate across turns
    // and inflate subsequent place-phase outputs (map-room#2210).
    final List<games.strategy.engine.data.Unit> holdingPool =
        new ArrayList<>(player.getUnitCollection().getUnits());
    if (!holdingPool.isEmpty()) {
      out.add(ChangeFactory.removeUnits(player, holdingPool));
    }

    final Resource pus = gameData.getResourceList().getResourceOrThrow("PUs");
    final int current = player.getResources().getQuantity(pus);
    final int delta = wp.pus() - current;
    if (delta != 0) {
      out.add(ChangeFactory.changeResourcesChange(player, pus, delta));
    }

    if (wp.tech() != null && !wp.tech().isEmpty()) {
      final TechAttachment techAttachment = player.getTechAttachment();
      if (techAttachment != null) {
        for (final String techName : wp.tech()) {
          final String property = TECH_PROPERTY_NAMES.get(techName);
          if (property == null) {
            throw new IllegalArgumentException("Unknown tech flag in WireState: " + techName);
          }
          out.add(ChangeFactory.attachmentPropertyChange(techAttachment, Boolean.TRUE, property));
        }
      }
    }

    // NOTE: WirePlayer.capitalCaptured has no direct analog in TripleA attachments — the
    // canonical TripleA model tracks capital capture via territory ownership + RulesAttachment
    // lookups rather than a boolean flag on the player. It is accepted on the wire for Map
    // Room's own bookkeeping but is currently a no-op on the Java side. Revisit if a specific
    // executor needs it.

    // China's production frontier must be set explicitly because the canonical GameData defaults to
    // productionChinese_Burma_Road_Open and the sidecar never fires TripleA's trigger system that
    // would switch it. Without this, ProPurchaseAi always sees Artillery as available even when the
    // Burma Road is closed (#2174).
    if (wp.productionFrontier() != null) {
      final var frontier =
          gameData.getProductionFrontierList().getProductionFrontier(wp.productionFrontier());
      if (frontier == null) {
        throw new IllegalArgumentException(
            "Unknown productionFrontier in WireState: " + wp.productionFrontier());
      }
      player.setProductionFrontier(frontier);
    }
  }

  private static GamePlayer resolvePlayer(final GameData gameData, final String name) {
    final GamePlayer p = gameData.getPlayerList().getPlayerId(name);
    if (p == null) {
      throw new IllegalArgumentException("Unknown player in WireState: " + name);
    }
    return p;
  }

  /**
   * Hydrate {@link games.strategy.engine.data.RelationshipTracker} from the wire's {@code
   * relationships} list. Each pair is set unconditionally via the tracker's direct setter so the
   * mutation is visible immediately to in-memory readers — the sidecar's politics step runs next on
   * this same tracker and must see the current state (see map-room#1824 phase-ordering constraint).
   *
   * <p>Pairs whose nations don't exist in the loaded GameData are skipped with a warning (defensive
   * — the TS side should already filter to TRIPLEA_KNOWN_PLAYERS, but stale fixtures shouldn't blow
   * up the applier).
   */
  private static void applyRelationships(final GameData gameData, final WireState wire) {
    if (wire.relationships().isEmpty()) {
      return;
    }
    for (final WireRelationship rel : wire.relationships()) {
      final GamePlayer a = gameData.getPlayerList().getPlayerId(rel.a());
      final GamePlayer b = gameData.getPlayerList().getPlayerId(rel.b());
      if (a == null || b == null) {
        LOG.log(
            Level.WARNING,
            () -> "Skipping relationship for unknown player(s): " + rel.a() + " / " + rel.b());
        continue;
      }
      final RelationshipType type = resolveRelationshipType(gameData, rel.kind());
      if (type == null) {
        LOG.log(Level.WARNING, () -> "Unknown relationship kind: " + rel.kind());
        continue;
      }
      gameData.getRelationshipTracker().setRelationship(a, b, type);
    }
  }

  /**
   * Resolve a wire {@code kind} ("war" / "allied" / "neutral") to the engine's first {@link
   * RelationshipType} whose archeType matches. The Global 1940 XML defines multiple subtypes per
   * archetype; the first one found is the canonical inter-power type for that archetype.
   */
  private static RelationshipType resolveRelationshipType(
      final GameData gameData, final String kind) {
    return gameData.getRelationshipTypeList().getAllRelationshipTypes().stream()
        .filter(r -> kind.equalsIgnoreCase(r.getRelationshipTypeAttachment().getArcheType()))
        .findFirst()
        .orElse(null);
  }

  // Map Room tech-flag name -> TechAttachment property name. Keep in sync with
  // TechAttachment's private setters: the set of supported flags is intentionally narrow; any
  // unknown flag throws so callers can't silently drift.
  private static final Map<String, String> TECH_PROPERTY_NAMES =
      Map.ofEntries(
          Map.entry("heavyBomber", "heavyBomber"),
          Map.entry("longRangeAir", "longRangeAir"),
          Map.entry("jetPower", "jetPower"),
          Map.entry("rocket", "rocket"),
          Map.entry("industrialTechnology", "industrialTechnology"),
          Map.entry("superSub", "superSub"),
          Map.entry("destroyerBombard", "destroyerBombard"),
          Map.entry("improvedArtillerySupport", "improvedArtillerySupport"),
          Map.entry("paratroopers", "paratroopers"),
          Map.entry("increasedFactoryProduction", "increasedFactoryProduction"),
          Map.entry("warBonds", "warBonds"),
          Map.entry("mechanizedInfantry", "mechanizedInfantry"),
          Map.entry("aaRadar", "aaRadar"),
          Map.entry("shipyards", "shipyards"));
}
