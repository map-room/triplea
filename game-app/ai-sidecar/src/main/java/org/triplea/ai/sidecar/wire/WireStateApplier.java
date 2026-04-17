package org.triplea.ai.sidecar.wire;

import games.strategy.engine.data.Change;
import games.strategy.engine.data.CompositeChange;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
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
 * <p><b>Scope:</b> this is the <em>static</em> state applier. It reconstructs the board (who
 * owns what, which units stand where, how many PUs each player has, which techs are unlocked).
 * It deliberately <b>does not</b> populate {@code BattleTracker} or construct any {@code
 * IBattle}. Battle / air-battle / combat-phase transient state is the responsibility of the
 * per-kind executors (Tasks 22–24), each of which synthesizes an {@code IBattle}, registers it
 * with {@code BattleDelegate.getBattleTracker()}, and then invokes the appropriate ProAi
 * method (see {@code AbstractProAi.selectCasualties / retreatQuery / scrambleUnitsQuery}).
 *
 * <p><b>Mutation idiom:</b> all mutations go through {@link ChangeFactory} + {@link
 * GameData#performChange(Change)} — not direct setters — because that is the only path that
 * fires the game-data change listeners TripleA code relies on elsewhere.
 *
 * <p><b>Unit identity:</b> Map Room addresses units by a stable string {@code unitId}; TripleA
 * addresses them by {@link UUID}. The applier is handed a concurrent map that it populates
 * lazily on first encounter with a given Map Room unit ID and reuses on subsequent applies so
 * that the same Map Room unit always resolves to the same TripleA {@link Unit#getId()}.
 */
public final class WireStateApplier {

  private static final Logger LOG = System.getLogger(WireStateApplier.class.getName());

  private WireStateApplier() {}

  /**
   * Mutates {@code gameData} in place so it matches {@code wire}. Round / phase / currentPlayer
   * are treated as read-only metadata: a mismatch logs a warning but does not throw.
   *
   * <p>Callers must not invoke {@code apply()} concurrently on the same {@code (gameData,
   * unitIdMap)} pair; serialise per-session.
   *
   * @throws IllegalArgumentException if the wire references a territory, player, unit type, or
   *     resource that does not exist on the canonical map — those indicate a caller bug, not a
   *     recoverable condition.
   */
  public static void apply(
      final GameData gameData,
      final WireState wire,
      final ConcurrentMap<String, UUID> unitIdMap) {
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

    // Phase 3 branches: conquered-this-turn, factory operational damage, and round/step.
    // These run AFTER the main CompositeChange has been performed so that any units the
    // damage branch references are live on the board.
    applyConqueredThisTurn(gameData, wire);
    applyOperationalDamage(gameData, wire, unitIdMap);
    // Round/step last — GameSequence mutation has no interaction with the earlier branches,
    // but keeping it at the tail means nothing downstream can overwrite it.
    applyRoundAndStep(gameData, wire);
  }

  /**
   * Register every {@link WireTerritory#conqueredThisTurn()} territory with the session's
   * {@link BattleTracker#getConquered() conquered} set so that TripleA predicates such as
   * {@code AbstractPlaceDelegate.wasConquered(t)} gate placement / production as they would
   * during a real turn.
   *
   * <p>We cannot use {@code ChangeFactory.attachmentPropertyChange} here because TripleA does
   * not model conquered-this-turn as a {@link
   * games.strategy.triplea.attachments.TerritoryAttachment} field — it lives entirely on the
   * (transient) {@link BattleTracker}.
   */
  private static void applyConqueredThisTurn(final GameData gameData, final WireState wire) {
    boolean needTracker = false;
    for (final WireTerritory wt : wire.territories()) {
      if (wt.conqueredThisTurn()) {
        needTracker = true;
        break;
      }
    }
    if (!needTracker) {
      return;
    }
    ensureBattleDelegate(gameData);
    final BattleTracker tracker = gameData.getBattleDelegate().getBattleTracker();
    for (final WireTerritory wt : wire.territories()) {
      if (!wt.conqueredThisTurn()) {
        continue;
      }
      final Territory t = gameData.getMap().getTerritoryOrNull(wt.territoryId());
      if (t == null) {
        continue;
      }
      tracker.getConquered().add(t);
    }
  }

  /**
   * Apply {@link WireUnit#bombingDamage()} to every unit on every territory whose wire damage
   * differs from the live {@link Unit#getUnitDamage()} value. {@link
   * ChangeFactory#bombingUnitDamage} semantics are <em>set, not add</em> — the {@link
   * IntegerMap} values are absolute new damage counts.
   */
  private static void applyOperationalDamage(
      final GameData gameData,
      final WireState wire,
      final ConcurrentMap<String, UUID> unitIdMap) {
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
   * Advance {@link GameData#getSequence()} to the wire-supplied round and step. This mirrors
   * what {@code GameSequence.setRoundAndStep} does for save-game export: the {@code display
   * name} lookup is case-insensitive and falls back to index 0 with an error log if no match
   * is found — which is why the {@link StepNameMapper} contract is narrow.
   */
  private static void applyRoundAndStep(final GameData gameData, final WireState wire) {
    // StepNameMapper only covers Phase 3 wire phases (purchase / combatMove / nonCombatMove /
    // place). Legacy / defensive callers may send phase values like "combat" that pre-date
    // Phase 3; for those we leave the sequence untouched and log a warning — the defensive
    // executors already tolerate a stale round/step because they dispatch directly into ProAi.
    final String javaStepName;
    try {
      javaStepName = StepNameMapper.toJavaStepName(wire.phase(), wire.currentPlayer());
    } catch (final IllegalArgumentException e) {
      LOG.log(
          Level.WARNING,
          () ->
              "WireState phase '" + wire.phase() + "' not mappable; skipping round/step apply");
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
      if (javaStepName.equalsIgnoreCase(step.getName())
          && player.equals(step.getPlayerId())) {
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
   * Re-register a fresh {@link BattleDelegate} if {@code postDeSerialize} cleared it on the
   * cloned {@link GameData}. Mirrors {@code ExecutorSupport.ensureBattleDelegate} which lives
   * in the {@code exec} package and is not visible here.
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
      final UUID uuid =
          unitIdMap.computeIfAbsent(wu.unitId(), k -> UUID.randomUUID());
      desiredIds.add(uuid);
      final UnitType type = gameData.getUnitTypeList().getUnitType(wu.unitType()).orElse(null);
      if (type == null) {
        throw new IllegalArgumentException(
            "Unknown unit type in WireState: " + wu.unitType());
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
      out.add(
          ChangeFactory.unitsHit(
              existingUnitHitDeltas, Collections.singletonList(territory)));
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

    final Resource pus =
        gameData.getResourceList().getResourceOrThrow("PUs");
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
  }

  private static GamePlayer resolvePlayer(final GameData gameData, final String name) {
    final GamePlayer p = gameData.getPlayerList().getPlayerId(name);
    if (p == null) {
      throw new IllegalArgumentException("Unknown player in WireState: " + name);
    }
    return p;
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
