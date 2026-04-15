package org.triplea.ai.sidecar.wire;

import games.strategy.engine.data.Change;
import games.strategy.engine.data.CompositeChange;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.attachments.TechAttachment;
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

    // Round / phase / currentPlayer are read-only during decision execution. We do not mutate
    // the sequence; the caller is expected to already be invoking a ProAi method that matches
    // the phase. Log a warning on mismatch to aid triage.
    final int actualRound = gameData.getSequence().getRound();
    if (actualRound != wire.round()) {
      LOG.log(
          Level.WARNING,
          () ->
              "WireState round ("
                  + wire.round()
                  + ") differs from GameData round ("
                  + actualRound
                  + ")");
    }
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
      final Unit existing = findUnitById(territory.getUnits(), uuid);
      final Unit unit;
      if (existing != null) {
        unit = existing;
        if (wu.hitsTaken() != unit.getHits()) {
          // Route through ChangeFactory.unitsHit so game-data listeners fire. The IntegerMap
          // value is the absolute new hit count (ChangeFactory.unitsHit sets, not adds).
          existingUnitHitDeltas.put(unit, wu.hitsTaken());
        }
      } else {
        unit = new Unit(uuid, type, wireOwner, gameData);
        if (wu.hitsTaken() != unit.getHits()) {
          // Safe: unit is not yet attached to any territory, no listeners to bypass.
          unit.setHits(wu.hitsTaken());
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
