package org.triplea.ai.sidecar.wire;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.RelationshipType;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.attachments.TechAttachment;
import games.strategy.triplea.delegate.battle.BattleTracker;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
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

/**
 * Post-apply integrity verifier. Called at the end of {@link WireStateApplier#apply} to detect
 * drift between an incoming {@link WireState} and the live {@link GameData} after apply has run.
 *
 * <p>Logs one {@code WARNING} line per specific mismatch using a grep-friendly
 * {@code apply-drift kind=...} prefix, plus one {@code INFO} summary line per apply. Never throws
 * — this is observational instrumentation, not enforcement.
 */
public final class WireStateVerifier {

  private static final Logger LOG = System.getLogger(WireStateVerifier.class.getName());

  // Ordered list of wire-side tech names (matches WireStateApplier.TECH_PROPERTY_NAMES keys).
  private static final List<String> TECH_NAMES =
      List.of(
          "heavyBomber",
          "longRangeAir",
          "jetPower",
          "rocket",
          "industrialTechnology",
          "superSub",
          "destroyerBombard",
          "improvedArtillerySupport",
          "paratroopers",
          "increasedFactoryProduction",
          "warBonds",
          "mechanizedInfantry",
          "aaRadar",
          "shipyards");

  private WireStateVerifier() {}

  /**
   * Verify that {@code gameData} now reflects every field carried in {@code wire}. Logs one
   * WARNING per drift and one INFO summary at the end. Never throws.
   */
  public static void verifyApply(
      final GameData gameData,
      final WireState wire,
      final ConcurrentMap<String, UUID> unitIdMap) {
    try {
      // Build global unit indices for territory-placement and transport-link checks.
      final Map<UUID, Unit> allUnitsById = new HashMap<>();
      final Map<UUID, Territory> unitToTerritory = new HashMap<>();
      for (final Territory t : gameData.getMap().getTerritories()) {
        for (final Unit u : t.getUnits()) {
          allUnitsById.put(u.getId(), u);
          unitToTerritory.put(u.getId(), t);
        }
      }
      // Reverse map: TripleA UUID → wire unit ID, for human-readable transport drift messages.
      final Map<UUID, String> uuidToWireId = new HashMap<>();
      for (final Map.Entry<String, UUID> e : unitIdMap.entrySet()) {
        uuidToWireId.put(e.getValue(), e.getKey());
      }

      int mismatches = 0;
      for (final WireTerritory wt : wire.territories()) {
        mismatches += verifyTerritory(gameData, wt, unitIdMap,
            allUnitsById, unitToTerritory, uuidToWireId);
      }
      for (final WirePlayer wp : wire.players()) {
        mismatches += verifyPlayer(gameData, wp);
      }
      for (final WireRelationship wr : wire.relationships()) {
        mismatches += verifyRelationship(gameData, wr);
      }
      final int total = mismatches;
      LOG.log(
          Level.INFO,
          () ->
              "apply-verify territories="
                  + wire.territories().size()
                  + " players="
                  + wire.players().size()
                  + " mismatches="
                  + total);
    } catch (final Exception e) {
      LOG.log(Level.WARNING, () -> "apply-verify threw unexpectedly: " + e.getMessage());
    }
  }

  private static int verifyTerritory(
      final GameData gameData,
      final WireTerritory wt,
      final ConcurrentMap<String, UUID> unitIdMap,
      final Map<UUID, Unit> allUnitsById,
      final Map<UUID, Territory> unitToTerritory,
      final Map<UUID, String> uuidToWireId) {
    final Territory t = gameData.getMap().getTerritoryOrNull(wt.territoryId());
    if (t == null) {
      return 0;
    }
    int drift = 0;

    // owner
    final String actualOwner = t.getOwner().getName();
    if (!wt.owner().equals(actualOwner)) {
      LOG.log(
          Level.WARNING,
          () ->
              "apply-drift kind=owner territory="
                  + wt.territoryId()
                  + " expected="
                  + wt.owner()
                  + " actual="
                  + actualOwner);
      drift++;
    }

    // unit count
    final int wireUnitCount = wt.units().size();
    final int actualUnitCount = t.getUnits().size();
    if (wireUnitCount != actualUnitCount) {
      LOG.log(
          Level.WARNING,
          () ->
              "apply-drift kind=unit-count territory="
                  + wt.territoryId()
                  + " expected="
                  + wireUnitCount
                  + " actual="
                  + actualUnitCount);
      drift++;
    }

    // per-unit checks (only for units resolvable via unitIdMap)
    for (final WireUnit wu : wt.units()) {
      final UUID uuid = unitIdMap.get(wu.unitId());
      if (uuid == null) {
        continue;
      }
      final Unit unit = findUnitById(t.getUnits(), uuid);
      if (unit == null) {
        // Unit is known to the id map but not in the expected territory — report where it is.
        final Territory actual = unitToTerritory.get(uuid);
        final String actualLocation = actual != null ? actual.getName() : "not-found";
        LOG.log(
            Level.WARNING,
            () ->
                "apply-drift kind=unit-territory unitId="
                    + wu.unitId()
                    + " expected="
                    + wt.territoryId()
                    + " actual="
                    + actualLocation);
        drift++;
        continue;
      }

      // hits
      if (wu.hitsTaken() != unit.getHits()) {
        LOG.log(
            Level.WARNING,
            () ->
                "apply-drift kind=unit-hits territory="
                    + wt.territoryId()
                    + " unitId="
                    + wu.unitId()
                    + " expected="
                    + wu.hitsTaken()
                    + " actual="
                    + unit.getHits());
        drift++;
      }

      // alreadyMoved
      final BigDecimal expectedMoved = BigDecimal.valueOf(wu.movesUsed());
      if (expectedMoved.compareTo(unit.getAlreadyMoved()) != 0) {
        LOG.log(
            Level.WARNING,
            () ->
                "apply-drift kind=unit-already-moved territory="
                    + wt.territoryId()
                    + " unitId="
                    + wu.unitId()
                    + " expected="
                    + wu.movesUsed()
                    + " actual="
                    + unit.getAlreadyMoved());
        drift++;
      }

      // unit owner (fallback to territory owner when wire owner is blank)
      final String expectedUnitOwner =
          (wu.owner() != null && !wu.owner().isEmpty()) ? wu.owner() : wt.owner();
      final String actualUnitOwner = unit.getOwner().getName();
      if (!expectedUnitOwner.equals(actualUnitOwner)) {
        LOG.log(
            Level.WARNING,
            () ->
                "apply-drift kind=unit-owner territory="
                    + wt.territoryId()
                    + " unitId="
                    + wu.unitId()
                    + " expected="
                    + expectedUnitOwner
                    + " actual="
                    + actualUnitOwner);
        drift++;
      }

      // transportedBy — wire says which transport carries this unit; live state must match
      drift += verifyTransportedBy(wu, unit, unitIdMap, allUnitsById, uuidToWireId,
          wt.territoryId());

      // combat-phase boolean flags
      drift += verifyUnitBoolFlag(wu.submerged(), unit.getSubmerged(),
          "submerged", wt.territoryId(), wu.unitId());
      drift += verifyUnitBoolFlag(wu.wasInCombat(), unit.getWasInCombat(),
          "wasInCombat", wt.territoryId(), wu.unitId());
      drift += verifyUnitBoolFlag(wu.wasLoadedThisTurn(), unit.getWasLoadedThisTurn(),
          "wasLoadedThisTurn", wt.territoryId(), wu.unitId());
      drift += verifyUnitBoolFlag(wu.wasUnloadedInCombatPhase(), unit.getWasUnloadedInCombatPhase(),
          "wasUnloadedInCombatPhase", wt.territoryId(), wu.unitId());
    }

    // conquered-this-turn (only checkable if the battle delegate is present)
    if (gameData.getDelegateOptional("battle").isPresent()) {
      final BattleTracker tracker = gameData.getBattleDelegate().getBattleTracker();
      final boolean actualConquered = tracker.getConquered().contains(t);
      if (wt.conqueredThisTurn() != actualConquered) {
        LOG.log(
            Level.WARNING,
            () ->
                "apply-drift kind=conquered-this-turn territory="
                    + wt.territoryId()
                    + " expected="
                    + wt.conqueredThisTurn()
                    + " actual="
                    + actualConquered);
        drift++;
      }
    }

    return drift;
  }

  private static int verifyTransportedBy(
      final WireUnit wu,
      final Unit unit,
      final ConcurrentMap<String, UUID> unitIdMap,
      final Map<UUID, Unit> allUnitsById,
      final Map<UUID, String> uuidToWireId,
      final String territoryId) {
    final String wireTransportId = wu.transportedBy();
    final Unit actualTransport = unit.getTransportedBy();

    if (wireTransportId != null) {
      final UUID transporterUuid = unitIdMap.get(wireTransportId);
      if (transporterUuid == null) {
        // Transporter wire ID not yet in idMap — can't verify; skip silently.
        return 0;
      }
      final Unit expectedTransporter = allUnitsById.get(transporterUuid);
      if (expectedTransporter == null) {
        return 0;
      }
      if (!expectedTransporter.equals(actualTransport)) {
        final String actualLabel = actualTransport != null
            ? uuidToWireId.getOrDefault(actualTransport.getId(), "uuid:" + actualTransport.getId())
            : "null";
        LOG.log(
            Level.WARNING,
            () ->
                "apply-drift kind=unit-transport-by territory="
                    + territoryId
                    + " unitId="
                    + wu.unitId()
                    + " expected="
                    + wireTransportId
                    + " actual="
                    + actualLabel);
        return 1;
      }
    } else if (actualTransport != null) {
      // Wire says no transport, but live unit has one.
      final String actualLabel =
          uuidToWireId.getOrDefault(actualTransport.getId(), "uuid:" + actualTransport.getId());
      LOG.log(
          Level.WARNING,
          () ->
              "apply-drift kind=unit-transport-by territory="
                  + territoryId
                  + " unitId="
                  + wu.unitId()
                  + " expected=null"
                  + " actual="
                  + actualLabel);
      return 1;
    }
    return 0;
  }

  private static int verifyUnitBoolFlag(
      final boolean expected,
      final boolean actual,
      final String flagName,
      final String territoryId,
      final String wireUnitId) {
    if (expected == actual) {
      return 0;
    }
    LOG.log(
        Level.WARNING,
        () ->
            "apply-drift kind=unit-"
                + flagName
                + " territory="
                + territoryId
                + " unitId="
                + wireUnitId
                + " expected="
                + expected
                + " actual="
                + actual);
    return 1;
  }

  private static int verifyPlayer(final GameData gameData, final WirePlayer wp) {
    final GamePlayer player = gameData.getPlayerList().getPlayerId(wp.playerId());
    if (player == null) {
      return 0;
    }
    int drift = 0;

    // PUs
    final Resource pus = gameData.getResourceList().getResourceOrThrow("PUs");
    final int actualPus = player.getResources().getQuantity(pus);
    if (wp.pus() != actualPus) {
      LOG.log(
          Level.WARNING,
          () ->
              "apply-drift kind=player-pus player="
                  + wp.playerId()
                  + " expected="
                  + wp.pus()
                  + " actual="
                  + actualPus);
      drift++;
    }

    // tech — compare wire's expected set against the set of activated techs in TechAttachment
    final TechAttachment ta = player.getTechAttachment();
    if (ta != null && wp.tech() != null) {
      final Set<String> expectedTech = Set.copyOf(wp.tech());
      final Set<String> actualTech = getActivatedTechNames(ta);
      if (!expectedTech.equals(actualTech)) {
        final List<String> expectedSorted = sorted(expectedTech);
        final List<String> actualSorted = sorted(actualTech);
        LOG.log(
            Level.WARNING,
            () ->
                "apply-drift kind=player-tech player="
                    + wp.playerId()
                    + " expected="
                    + expectedSorted
                    + " actual="
                    + actualSorted);
        drift++;
      }
    }

    return drift;
  }

  private static int verifyRelationship(final GameData gameData, final WireRelationship wr) {
    final GamePlayer a = gameData.getPlayerList().getPlayerId(wr.a());
    final GamePlayer b = gameData.getPlayerList().getPlayerId(wr.b());
    if (a == null || b == null) {
      return 0;
    }
    final RelationshipType type = gameData.getRelationshipTracker().getRelationshipType(a, b);
    if (type == null) {
      return 0;
    }
    final String actualKind = type.getRelationshipTypeAttachment().getArcheType();
    if (!wr.kind().equalsIgnoreCase(actualKind)) {
      LOG.log(
          Level.WARNING,
          () ->
              "apply-drift kind=relationship pair="
                  + wr.a()
                  + "-"
                  + wr.b()
                  + " expected="
                  + wr.kind()
                  + " actual="
                  + actualKind);
      return 1;
    }
    return 0;
  }

  private static Set<String> getActivatedTechNames(final TechAttachment ta) {
    final Set<String> result = new HashSet<>();
    for (final String name : TECH_NAMES) {
      final var prop = ta.getPropertyOrEmpty(name);
      if (prop.isPresent() && Boolean.TRUE.equals(prop.get().getValue())) {
        result.add(name);
      }
    }
    return result;
  }

  private static List<String> sorted(final Set<String> set) {
    final List<String> list = new ArrayList<>(set);
    Collections.sort(list);
    return list;
  }

  private static Unit findUnitById(final Collection<Unit> units, final UUID id) {
    for (final Unit u : units) {
      if (u.getId().equals(id)) {
        return u;
      }
    }
    return null;
  }
}
