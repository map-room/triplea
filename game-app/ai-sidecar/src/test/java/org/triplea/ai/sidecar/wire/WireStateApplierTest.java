package org.triplea.ai.sidecar.wire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.delegate.TransportTracker;
import games.strategy.triplea.settings.ClientSetting;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;

class WireStateApplierTest {

  private static CanonicalGameData canonical;

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load();
  }

  private GameData fresh() {
    return canonical.cloneForSession();
  }

  private ConcurrentMap<String, UUID> freshIdMap() {
    return new ConcurrentHashMap<>();
  }

  // ---------- plan step 1: three load-bearing mutations ----------

  @Test
  void updatesTerritoryOwnership() {
    final GameData gd = fresh();
    final WireState wire =
        new WireState(
            List.of(new WireTerritory("Germany", "Russians", List.of())),
            List.of(),
            1,
            "combat",
            "Russians",
            List.of());
    WireStateApplier.apply(gd, wire, freshIdMap());
    assertThat(gd.getMap().getTerritoryOrThrow("Germany").getOwner().getName())
        .isEqualTo("Russians");
  }

  @Test
  void syncsUnitsAtTerritory() {
    final GameData gd = fresh();
    final WireState wire =
        new WireState(
            List.of(
                new WireTerritory(
                    "Germany", "Germans", List.of(new WireUnit("u-inf-1", "infantry", 0, 0)))),
            List.of(),
            1,
            "combat",
            "Germans",
            List.of());
    WireStateApplier.apply(gd, wire, freshIdMap());
    final Territory germany = gd.getMap().getTerritoryOrThrow("Germany");
    assertThat(germany.getUnits()).hasSize(1);
    assertThat(germany.getUnits().iterator().next().getType().getName()).isEqualTo("infantry");
  }

  @Test
  void updatesPlayerPus() {
    final GameData gd = fresh();
    final WireState wire =
        new WireState(
            List.of(),
            List.of(new WirePlayer("Germans", 42, List.of(), false)),
            1,
            "combat",
            "Germans",
            List.of());
    WireStateApplier.apply(gd, wire, freshIdMap());
    assertThat(gd.getPlayerList().getPlayerId("Germans").getResources().getQuantity("PUs"))
        .isEqualTo(42);
  }

  // ---------- §5 defensive scenarios ----------

  @Test
  void selectCasualtiesScenario_preservesMixedDefenderStack() {
    // Defender territory with three different unit types — the kind of stack ProAi would need
    // to pick casualties from. We verify both presence and that the id map resolves each
    // Map Room id to a live Unit in the territory.
    final GameData gd = fresh();
    final ConcurrentMap<String, UUID> idMap = freshIdMap();
    final WireState wire =
        new WireState(
            List.of(
                new WireTerritory(
                    "Germany",
                    "Germans",
                    List.of(
                        new WireUnit("u-inf-1", "infantry", 0, 0),
                        new WireUnit("u-art-1", "artillery", 0, 0),
                        new WireUnit("u-tank-1", "armour", 0, 0)))),
            List.of(),
            1,
            "combat",
            "Germans",
            List.of());
    WireStateApplier.apply(gd, wire, idMap);

    final Territory germany = gd.getMap().getTerritoryOrThrow("Germany");
    assertThat(germany.getUnits()).hasSize(3);
    final Map<String, Unit> byType =
        germany.getUnits().stream().collect(Collectors.toMap(u -> u.getType().getName(), u -> u));
    assertThat(byType.keySet()).containsExactlyInAnyOrder("infantry", "artillery", "armour");

    assertThat(idMap).containsKeys("u-inf-1", "u-art-1", "u-tank-1");
    for (final Map.Entry<String, UUID> e : idMap.entrySet()) {
      assertThat(germany.getUnits().stream().map(Unit::getId)).contains(e.getValue());
    }
  }

  @Test
  void retreatQueryScenario_attackerStackAndDefenderTerritoryBothPresent() {
    // Attacker stack in France, defender stack in Germany — both populated from the wire;
    // afterwards both territories reflect wire state exactly and the id map resolves all
    // attacker unit ids.
    final GameData gd = fresh();
    final ConcurrentMap<String, UUID> idMap = freshIdMap();
    final WireState wire =
        new WireState(
            List.of(
                new WireTerritory(
                    "France",
                    "Germans",
                    List.of(
                        new WireUnit("u-atk-inf-1", "infantry", 0, 0),
                        new WireUnit("u-atk-tank-1", "armour", 0, 0))),
                new WireTerritory(
                    "Germany", "Germans", List.of(new WireUnit("u-def-inf-1", "infantry", 0, 0)))),
            List.of(),
            1,
            "combat",
            "Germans",
            List.of());
    WireStateApplier.apply(gd, wire, idMap);

    assertThat(gd.getMap().getTerritoryOrThrow("France").getUnits()).hasSize(2);
    assertThat(gd.getMap().getTerritoryOrThrow("Germany").getUnits()).hasSize(1);
    assertThat(idMap).containsKeys("u-atk-inf-1", "u-atk-tank-1", "u-def-inf-1");
  }

  @Test
  void scrambleScenario_airBaseUnitsPresent() {
    // Air-base territory (Western Germany) holds a fighter ready to scramble.
    final GameData gd = fresh();
    final ConcurrentMap<String, UUID> idMap = freshIdMap();
    final WireState wire =
        new WireState(
            List.of(
                new WireTerritory(
                    "Western Germany",
                    "Germans",
                    List.of(
                        new WireUnit("u-ftr-1", "fighter", 0, 0),
                        new WireUnit("u-tac-1", "tactical_bomber", 0, 0)))),
            List.of(),
            1,
            "combat",
            "Germans",
            List.of());
    WireStateApplier.apply(gd, wire, idMap);

    final Territory wg = gd.getMap().getTerritoryOrThrow("Western Germany");
    assertThat(wg.getUnits().stream().map(u -> u.getType().getName()).collect(Collectors.toSet()))
        .contains("fighter", "tactical_bomber");
    assertThat(idMap).containsKeys("u-ftr-1", "u-tac-1");
  }

  // ---------- tightened requirements ----------

  @Test
  void roundTripAfterTwoApplies_reflectsLaterStateOnly() {
    final GameData gd = fresh();
    final ConcurrentMap<String, UUID> idMap = freshIdMap();

    final WireState a =
        new WireState(
            List.of(
                new WireTerritory(
                    "Germany",
                    "Germans",
                    List.of(
                        new WireUnit("u-inf-1", "infantry", 0, 0),
                        new WireUnit("u-inf-2", "infantry", 0, 0)))),
            List.of(),
            1,
            "combat",
            "Germans",
            List.of());
    WireStateApplier.apply(gd, a, idMap);
    assertThat(gd.getMap().getTerritoryOrThrow("Germany").getUnits()).hasSize(2);

    final WireState b =
        new WireState(
            List.of(
                new WireTerritory(
                    "Germany", "Russians", List.of(new WireUnit("u-inf-1", "infantry", 0, 0)))),
            List.of(),
            1,
            "combat",
            "Russians",
            List.of());
    WireStateApplier.apply(gd, b, idMap);

    final Territory germany = gd.getMap().getTerritoryOrThrow("Germany");
    assertThat(germany.getOwner().getName()).isEqualTo("Russians");
    // Only u-inf-1 survives; u-inf-2 was pruned because it was absent from wire B.
    assertThat(germany.getUnits()).hasSize(1);
    assertThat(germany.getUnits().iterator().next().getId()).isEqualTo(idMap.get("u-inf-1"));
  }

  @Test
  void unitIdStability_sameWireIdMapsToSameUuidAcrossApplies() {
    final GameData gd = fresh();
    final ConcurrentMap<String, UUID> idMap = freshIdMap();
    final WireState wire =
        new WireState(
            List.of(
                new WireTerritory(
                    "Germany", "Germans", List.of(new WireUnit("u-inf-1", "infantry", 0, 0)))),
            List.of(),
            1,
            "combat",
            "Germans",
            List.of());
    WireStateApplier.apply(gd, wire, idMap);
    final UUID first = idMap.get("u-inf-1");

    WireStateApplier.apply(gd, wire, idMap);
    final UUID second = idMap.get("u-inf-1");

    assertThat(second).isEqualTo(first);
    // No churn: still exactly one mapping, one unit on the territory, same UUID.
    assertThat(idMap).hasSize(1);
    assertThat(gd.getMap().getTerritoryOrThrow("Germany").getUnits()).hasSize(1);
    assertThat(gd.getMap().getTerritoryOrThrow("Germany").getUnits().iterator().next().getId())
        .isEqualTo(first);
  }

  @Test
  void unknownTerritory_throws() {
    final GameData gd = fresh();
    final WireState wire =
        new WireState(
            List.of(new WireTerritory("Atlantis", "Germans", List.of())),
            List.of(),
            1,
            "combat",
            "Germans",
            List.of());
    assertThatThrownBy(() -> WireStateApplier.apply(gd, wire, freshIdMap()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Atlantis");
  }

  @Test
  void unknownPlayer_throws() {
    final GameData gd = fresh();
    final WireState wire =
        new WireState(
            List.of(),
            List.of(new WirePlayer("Martians", 42, List.of(), false)),
            1,
            "combat",
            "Germans",
            List.of());
    assertThatThrownBy(() -> WireStateApplier.apply(gd, wire, freshIdMap()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Martians");
  }

  @Test
  void unknownUnitType_throws() {
    final GameData gd = fresh();
    final WireState wire =
        new WireState(
            List.of(
                new WireTerritory(
                    "Germany", "Germans", List.of(new WireUnit("u-x", "deathstar", 0, 0)))),
            List.of(),
            1,
            "combat",
            "Germans",
            List.of());
    assertThatThrownBy(() -> WireStateApplier.apply(gd, wire, freshIdMap()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("deathstar");
  }

  // ---------- per-unit owner (#1776 regression fence) ----------

  /**
   * Sea zone with ships from two different nations: the unit's owner must come from the per-unit
   * {@code owner} field, not the territory owner (Neutral). This is the regression fence for the
   * bug where ProAI saw Italian ally ships as enemies because they were all attributed to Neutral.
   */
  @Test
  void perUnitOwner_seaZoneMultiNation_assignsCorrectOwners() {
    final GameData gd = fresh();
    // 112 Sea Zone: territory owner is Germans but the cruiser belongs to Italians.
    // This mirrors the bug scenario where sea zones hold ships from allied nations.
    final WireUnit germanSub =
        WireUnit.of(
            "u-sub-1", "submarine", 0, 0, 0, "Germans", null, false, false, false, false, 0);
    final WireUnit italianCruiser =
        WireUnit.of("u-cru-1", "cruiser", 0, 0, 0, "Italians", null, false, false, false, false, 0);
    final WireState wire =
        new WireState(
            List.of(
                new WireTerritory("112 Sea Zone", "Germans", List.of(germanSub, italianCruiser))),
            List.of(),
            1,
            "combat",
            "Germans",
            List.of());

    final ConcurrentMap<String, UUID> idMap = freshIdMap();
    WireStateApplier.apply(gd, wire, idMap);

    final Territory seaZone = gd.getMap().getTerritoryOrNull("112 Sea Zone");
    assertThat(seaZone).isNotNull();
    final Map<String, String> unitOwners =
        seaZone.getUnits().stream()
            .collect(Collectors.toMap(u -> u.getType().getName(), u -> u.getOwner().getName()));
    assertThat(unitOwners).containsEntry("submarine", "Germans");
    assertThat(unitOwners).containsEntry("cruiser", "Italians");
  }

  /**
   * Backward compat: units without an owner field ({@code owner} null) fall back to territory
   * owner. No crash, correct assignment.
   */
  @Test
  void perUnitOwner_nullOwner_fallsBackToTerritoryOwner() {
    final GameData gd = fresh();
    // Germany is a land territory owned by Germans.
    final WireUnit infantry = new WireUnit("u-inf-1", "infantry", 0, 0); // no owner field
    final WireState wire =
        new WireState(
            List.of(new WireTerritory("Germany", "Germans", List.of(infantry))),
            List.of(),
            1,
            "combat",
            "Germans",
            List.of());

    WireStateApplier.apply(gd, wire, freshIdMap());

    final Territory germany = gd.getMap().getTerritoryOrNull("Germany");
    assertThat(germany).isNotNull();
    assertThat(germany.getUnits()).hasSize(1);
    assertThat(germany.getUnits().iterator().next().getOwner().getName()).isEqualTo("Germans");
  }

  // ---------- transportedBy / submerged / wasInCombat hydration (#1831) ----------

  @Test
  void submerged_trueOnWire_setsSubmergedOnUnit() {
    final GameData gd = fresh();
    final WireUnit sub =
        WireUnit.of("u-sub-1", "submarine", 0, 0, 0, "Germans", null, true, false, false, false, 0);
    final WireState wire =
        new WireState(
            List.of(new WireTerritory("112 Sea Zone", "Germans", List.of(sub))),
            List.of(),
            1,
            "combatMove",
            "Germans",
            List.of());
    final ConcurrentMap<String, UUID> idMap = freshIdMap();
    WireStateApplier.apply(gd, wire, idMap);

    final Territory seaZone = gd.getMap().getTerritoryOrNull("112 Sea Zone");
    assertThat(seaZone).isNotNull();
    final Unit liveUnit = seaZone.getUnits().iterator().next();
    assertThat(liveUnit.getSubmerged()).isTrue();
  }

  @Test
  void wasInCombat_trueOnWire_setsWasInCombatOnUnit() {
    final GameData gd = fresh();
    final WireUnit infantry =
        WireUnit.of("u-inf-1", "infantry", 0, 0, 0, "Germans", null, false, true, false, false, 0);
    final WireState wire =
        new WireState(
            List.of(new WireTerritory("Germany", "Germans", List.of(infantry))),
            List.of(),
            1,
            "nonCombatMove",
            "Germans",
            List.of());
    final ConcurrentMap<String, UUID> idMap = freshIdMap();
    WireStateApplier.apply(gd, wire, idMap);

    final Territory germany = gd.getMap().getTerritoryOrNull("Germany");
    assertThat(germany).isNotNull();
    final Unit liveUnit = germany.getUnits().iterator().next();
    assertThat(liveUnit.getWasInCombat()).isTrue();
  }

  @Test
  void transportedBy_unitIdOnWire_linksInfantryToTransport() {
    final GameData gd = fresh();
    final WireUnit transport =
        WireUnit.of(
            "u-trn-1", "transport", 0, 0, 0, "Germans", null, false, false, false, false, 0);
    final WireUnit infantry =
        WireUnit.of(
            "u-inf-1", "infantry", 0, 0, 0, "Germans", "u-trn-1", false, false, false, false, 0);
    final WireState wire =
        new WireState(
            List.of(new WireTerritory("112 Sea Zone", "Germans", List.of(transport, infantry))),
            List.of(),
            1,
            "combatMove",
            "Germans",
            List.of());
    final ConcurrentMap<String, UUID> idMap = freshIdMap();
    WireStateApplier.apply(gd, wire, idMap);

    final Territory seaZone = gd.getMap().getTerritoryOrNull("112 Sea Zone");
    assertThat(seaZone).isNotNull();
    final UUID transportUuid = idMap.get("u-trn-1");
    final UUID infantryUuid = idMap.get("u-inf-1");
    assertThat(transportUuid).isNotNull();
    assertThat(infantryUuid).isNotNull();

    Unit transportUnit = null;
    Unit infantryUnit = null;
    for (final Unit u : seaZone.getUnits()) {
      if (u.getId().equals(transportUuid)) {
        transportUnit = u;
      } else if (u.getId().equals(infantryUuid)) {
        infantryUnit = u;
      }
    }
    assertThat(transportUnit).isNotNull();
    assertThat(infantryUnit).isNotNull();
    assertThat(infantryUnit.getTransportedBy()).isEqualTo(transportUnit);
  }

  @Test
  void appliesMovesUsedToAlreadyMoved_newUnit() {
    // Regression: WireUnit.movesUsed (the moves a unit has already spent this
    // turn on the Map Room side) was parsed from JSON but never applied to
    // TripleA's Unit.alreadyMoved field. Pro AI then planned movement as if
    // every air unit had its full movement budget left, generating moves the
    // engine rejects (e.g., trying to land a tac bomber 5 hexes away when
    // only 1 move remains).
    final GameData gd = fresh();
    final WireState wire =
        new WireState(
            List.of(
                new WireTerritory(
                    "Germany",
                    "Germans",
                    List.of(new WireUnit("u-tac-1", "tactical_bomber", 0, 4)))),
            List.of(),
            1,
            "noncombat",
            "Germans",
            List.of());
    WireStateApplier.apply(gd, wire, freshIdMap());
    final Territory germany = gd.getMap().getTerritoryOrThrow("Germany");
    final Unit tac = germany.getUnits().iterator().next();
    assertThat(tac.getAlreadyMoved()).isEqualTo(new java.math.BigDecimal(4));
  }

  @Test
  void appliesMovesUsedToAlreadyMoved_existingUnit() {
    // Same regression, exercising the existing-unit branch (apply called twice
    // with different movesUsed values for the same unitId).
    final GameData gd = fresh();
    final ConcurrentMap<String, UUID> idMap = freshIdMap();
    final WireState first =
        new WireState(
            List.of(
                new WireTerritory(
                    "Germany", "Germans", List.of(new WireUnit("u-fighter-1", "fighter", 0, 0)))),
            List.of(),
            1,
            "combat",
            "Germans",
            List.of());
    WireStateApplier.apply(gd, first, idMap);
    final WireState second =
        new WireState(
            List.of(
                new WireTerritory(
                    "Germany", "Germans", List.of(new WireUnit("u-fighter-1", "fighter", 0, 3)))),
            List.of(),
            1,
            "noncombat",
            "Germans",
            List.of());
    WireStateApplier.apply(gd, second, idMap);
    final Territory germany = gd.getMap().getTerritoryOrThrow("Germany");
    final Unit fighter = germany.getUnits().iterator().next();
    assertThat(fighter.getAlreadyMoved()).isEqualTo(new java.math.BigDecimal(3));
  }

  // ---------- wasLoadedThisTurn / wasUnloadedInCombatPhase / bonusMovement hydration (#1832)
  // ----------

  @Test
  void wasLoadedThisTurn_trueOnWire_setsWasLoadedThisTurnOnUnit() {
    final GameData gd = fresh();
    final WireUnit infantry =
        WireUnit.of("u-inf-1", "infantry", 0, 1, 0, "Germans", null, false, false, true, false, 0);
    final WireState wire =
        new WireState(
            List.of(new WireTerritory("Germany", "Germans", List.of(infantry))),
            List.of(),
            1,
            "combatMove",
            "Germans",
            List.of());
    final ConcurrentMap<String, UUID> idMap = freshIdMap();
    WireStateApplier.apply(gd, wire, idMap);

    final Territory germany = gd.getMap().getTerritoryOrNull("Germany");
    assertThat(germany).isNotNull();
    final Unit liveUnit = germany.getUnits().iterator().next();
    assertThat(liveUnit.getWasLoadedThisTurn()).isTrue();
  }

  @Test
  void wasUnloadedInCombatPhase_trueOnWire_setsWasUnloadedInCombatPhaseOnUnit() {
    final GameData gd = fresh();
    final WireUnit infantry =
        WireUnit.of("u-inf-1", "infantry", 0, 1, 0, "Germans", null, false, false, false, true, 0);
    final WireState wire =
        new WireState(
            List.of(new WireTerritory("Germany", "Germans", List.of(infantry))),
            List.of(),
            1,
            "combatMove",
            "Germans",
            List.of());
    final ConcurrentMap<String, UUID> idMap = freshIdMap();
    WireStateApplier.apply(gd, wire, idMap);

    final Territory germany = gd.getMap().getTerritoryOrNull("Germany");
    assertThat(germany).isNotNull();
    final Unit liveUnit = germany.getUnits().iterator().next();
    assertThat(liveUnit.getWasUnloadedInCombatPhase()).isTrue();
  }

  @Test
  void bonusMovement_nonZeroOnWire_setsBonusMovementOnUnit() {
    final GameData gd = fresh();
    final WireUnit fighter =
        WireUnit.of("u-ftr-1", "fighter", 0, 0, 0, "Germans", null, false, false, false, false, 1);
    final WireState wire =
        new WireState(
            List.of(new WireTerritory("Germany", "Germans", List.of(fighter))),
            List.of(),
            1,
            "combatMove",
            "Germans",
            List.of());
    final ConcurrentMap<String, UUID> idMap = freshIdMap();
    WireStateApplier.apply(gd, wire, idMap);

    final Territory germany = gd.getMap().getTerritoryOrNull("Germany");
    assertThat(germany).isNotNull();
    final Unit liveUnit = germany.getUnits().iterator().next();
    assertThat(liveUnit.getBonusMovement()).isEqualTo(1);
  }

  // ---------- unit owner propagation on existing units (#41) ----------

  @Test
  void unitOwnerUpdated_whenExistingUnitChangesOwner() {
    // Simulates a factory/airfield unit that was placed in France by Germans on a prior apply
    // and then captured by Russians. The second apply carries the same unit UUID but a different
    // owner field. The applier must update the live unit's owner, not leave it as Germans.
    final GameData gd = fresh();
    final ConcurrentMap<String, UUID> idMap = freshIdMap();

    final WireState first =
        new WireState(
            List.of(
                new WireTerritory(
                    "France",
                    "Germans",
                    List.of(
                        WireUnit.of(
                            "u-fac-1",
                            "factory_major",
                            0,
                            0,
                            0,
                            "Germans",
                            null,
                            false,
                            false,
                            false,
                            false,
                            0)))),
            List.of(),
            1,
            "combat",
            "Germans",
            List.of());
    WireStateApplier.apply(gd, first, idMap);

    assertThat(
            gd.getMap()
                .getTerritoryOrThrow("France")
                .getUnits()
                .iterator()
                .next()
                .getOwner()
                .getName())
        .isEqualTo("Germans");

    // Second apply: same unit ID, territory now owned by Russians (captured), unit owner =
    // Russians.
    final WireState second =
        new WireState(
            List.of(
                new WireTerritory(
                    "France",
                    "Russians",
                    List.of(
                        WireUnit.of(
                            "u-fac-1",
                            "factory_major",
                            0,
                            0,
                            0,
                            "Russians",
                            null,
                            false,
                            false,
                            false,
                            false,
                            0)))),
            List.of(),
            1,
            "combat",
            "Russians",
            List.of());
    WireStateApplier.apply(gd, second, idMap);

    final Territory france = gd.getMap().getTerritoryOrThrow("France");
    assertThat(france.getOwner().getName()).isEqualTo("Russians");
    assertThat(france.getUnits()).hasSize(1);
    assertThat(france.getUnits().iterator().next().getOwner().getName()).isEqualTo("Russians");
  }

  // ---------- conquered-this-turn reconciliation (#2013) ----------

  @Test
  void conqueredThisTurn_trueOnWire_addsToBattleTracker() {
    final GameData gd = fresh();
    final WireState wire =
        new WireState(
            List.of(new WireTerritory("Ethiopia", "British", List.of(), true)),
            List.of(),
            1,
            "place",
            "British",
            List.of());
    WireStateApplier.apply(gd, wire, freshIdMap());
    final Territory ethiopia = gd.getMap().getTerritoryOrThrow("Ethiopia");
    assertThat(gd.getBattleDelegate().getBattleTracker().getConquered()).contains(ethiopia);
  }

  @Test
  void conqueredThisTurn_falseOnSecondApply_removesFromBattleTracker() {
    // Regression fence for #2013: territory conquered on turn N must be removed from
    // BattleTracker when the next wire payload says conqueredThisTurn=false.
    final GameData gd = fresh();
    final ConcurrentMap<String, UUID> idMap = freshIdMap();
    final WireState withConquered =
        new WireState(
            List.of(new WireTerritory("Ethiopia", "British", List.of(), true)),
            List.of(),
            1,
            "place",
            "British",
            List.of());
    WireStateApplier.apply(gd, withConquered, idMap);
    final Territory ethiopia = gd.getMap().getTerritoryOrThrow("Ethiopia");
    assertThat(gd.getBattleDelegate().getBattleTracker().getConquered()).contains(ethiopia);

    final WireState withoutConquered =
        new WireState(
            List.of(new WireTerritory("Ethiopia", "British", List.of(), false)),
            List.of(),
            2,
            "purchase",
            "British",
            List.of());
    WireStateApplier.apply(gd, withoutConquered, idMap);
    assertThat(gd.getBattleDelegate().getBattleTracker().getConquered()).doesNotContain(ethiopia);
  }

  @Test
  void techFlag_setsAttachmentProperty() {
    final GameData gd = fresh();
    final WireState wire =
        new WireState(
            List.of(),
            List.of(new WirePlayer("Germans", 30, List.of("heavyBomber"), false)),
            1,
            "combat",
            "Germans",
            List.of());
    WireStateApplier.apply(gd, wire, freshIdMap());
    assertThat(gd.getPlayerList().getPlayerId("Germans").getTechAttachment().getHeavyBomber())
        .isTrue();
  }

  // ---------- transport unloaded / unloadedTo round-trip (#2162) ----------

  @Test
  void transportUnloaded_consistentState_setsUnloadedOnTransportAndUnloadedToOnCargo() {
    // Consistent wire state: transport.unloaded = [infantry], infantry.unloadedTo = "Germany".
    // After apply, the Java transport must have the infantry in its unloaded collection and the
    // infantry must have the Germany territory as its unloadedTo.
    final GameData gd = fresh();
    final ConcurrentMap<String, UUID> idMap = freshIdMap();

    final WireUnit transport =
        WireUnit.of(
            "u-trn-1",
            "transport",
            0,
            0,
            0,
            "Germans",
            null,
            false,
            false,
            false,
            false,
            0,
            false,
            false,
            null,
            List.of("u-inf-1"),
            null,
            false);
    final WireUnit infantry =
        WireUnit.of(
            "u-inf-1",
            "infantry",
            0,
            0,
            0,
            "Germans",
            null,
            false,
            false,
            false,
            true,
            0,
            false,
            false,
            null,
            null,
            "Germany",
            false);

    final WireState wire =
        new WireState(
            List.of(
                new WireTerritory("112 Sea Zone", "Germans", List.of(transport)),
                new WireTerritory("Germany", "Germans", List.of(infantry))),
            List.of(),
            1,
            "combatMove",
            "Germans",
            List.of());

    WireStateApplier.apply(gd, wire, idMap);

    final Territory seaZone = gd.getMap().getTerritoryOrNull("112 Sea Zone");
    assertThat(seaZone).isNotNull();
    final Unit transportUnit =
        seaZone.getUnits().stream()
            .filter(u -> u.getId().equals(idMap.get("u-trn-1")))
            .findFirst()
            .orElseThrow();
    final Territory germanyTerr = gd.getMap().getTerritoryOrNull("Germany");
    assertThat(germanyTerr).isNotNull();
    final Unit infantryUnit =
        germanyTerr.getUnits().stream()
            .filter(u -> u.getId().equals(idMap.get("u-inf-1")))
            .findFirst()
            .orElseThrow();

    assertThat(transportUnit.getUnloaded()).containsExactly(infantryUnit);
    assertThat(infantryUnit.getUnloadedTo()).isNotNull();
    assertThat(infantryUnit.getUnloadedTo().getName()).isEqualTo("Germany");
  }

  @Test
  void transportUnloaded_nullUnloadedToOnCargo_staleEntrySkipped_preventsNpe() {
    // Defense-in-depth for #2162. Wire state is inconsistent: transport.unloaded = [infantry]
    // but infantry.unloadedTo = null. This arises when a foreign-owned transport retains its
    // unloaded list from a prior nation's combat-move phase while the cargo unit's unloadedTo
    // was cleared because the cargo belongs to the active player. The applier must skip the
    // stale entry so transport.getUnloaded() is empty, preventing an NPE in
    // TransportTracker.isTransportUnloadRestrictedToAnotherTerritory.
    final GameData gd = fresh();

    final WireUnit transport =
        WireUnit.of(
            "u-trn-1",
            "transport",
            0,
            0,
            0,
            "Germans",
            null,
            false,
            false,
            false,
            false,
            0,
            false,
            false,
            null,
            List.of("u-inf-1"),
            null,
            false);
    final WireUnit infantry =
        WireUnit.of(
            "u-inf-1",
            "infantry",
            0,
            0,
            0,
            "Germans",
            null,
            false,
            false,
            false,
            false,
            0,
            false,
            false,
            null,
            null,
            null,
            false); // unloadedTo = null — stale cross-nation state

    final WireState wire =
        new WireState(
            List.of(
                new WireTerritory("112 Sea Zone", "Germans", List.of(transport)),
                new WireTerritory("Germany", "Germans", List.of(infantry))),
            List.of(),
            1,
            "combatMove",
            "Germans",
            List.of());

    WireStateApplier.apply(gd, wire, freshIdMap());

    final Territory seaZone = gd.getMap().getTerritoryOrNull("112 Sea Zone");
    assertThat(seaZone).isNotNull();
    final Unit transportUnit =
        seaZone.getUnits().stream()
            .filter(u -> u.getType().getName().equals("transport"))
            .findFirst()
            .orElseThrow();

    // Stale entry skipped — transport has no unloaded units
    assertThat(transportUnit.getUnloaded()).isEmpty();

    // Regression: no NPE when TransportTracker interrogates this transport
    assertThatCode(
            () ->
                TransportTracker.isTransportUnloadRestrictedToAnotherTerritory(
                    transportUnit, seaZone))
        .doesNotThrowAnyException();
  }

  /**
   * Regression for #2268: when cargo dies between combat-move and NCM, the wire transport still
   * lists the dead unit's ID in its {@code unloaded} array (TS engine doesn't clean up transport
   * refs on unit deletion). The second apply must clear the transport's Java {@code unloaded} list
   * so that battle-calculator workers don't inherit a stale list with null {@code unloadedTo},
   * which causes NPE in {@code RemoveUnitsHistoryChange} during NCM odds simulation.
   */
  @Test
  void transportUnloaded_deadCargoAbsentFromWire_staleUnloadedListCleared() {
    final GameData gd = fresh();
    final ConcurrentMap<String, UUID> idMap = freshIdMap();

    // Apply 1 (combat-move): transport references infantry, infantry has unloadedTo=Germany.
    final WireUnit transportApply1 =
        WireUnit.of(
            "u-trn-1",
            "transport",
            0,
            0,
            0,
            "Germans",
            null,
            false,
            false,
            false,
            false,
            0,
            false,
            false,
            null,
            List.of("u-inf-1"),
            null,
            false);
    final WireUnit infantryApply1 =
        WireUnit.of(
            "u-inf-1",
            "infantry",
            0,
            0,
            0,
            "Germans",
            null,
            false,
            false,
            false,
            true,
            0,
            false,
            false,
            null,
            null,
            "Germany",
            false);

    final WireState wireA =
        new WireState(
            List.of(
                new WireTerritory("112 Sea Zone", "Germans", List.of(transportApply1)),
                new WireTerritory("Germany", "Germans", List.of(infantryApply1))),
            List.of(),
            1,
            "combatMove",
            "Germans",
            List.of());
    WireStateApplier.apply(gd, wireA, idMap);

    final Territory seaZone = gd.getMap().getTerritoryOrNull("112 Sea Zone");
    assertThat(seaZone).isNotNull();
    final Unit transportUnit =
        seaZone.getUnits().stream()
            .filter(u -> u.getId().equals(idMap.get("u-trn-1")))
            .findFirst()
            .orElseThrow();
    // Precondition: after apply 1, transport has infantry in unloaded list.
    assertThat(transportUnit.getUnloaded()).hasSize(1);

    // Apply 2 (NCM): infantry was killed in battle — absent from wire entirely.
    // Transport's wire state still says unloaded=['u-inf-1'] (TS engine doesn't clean up
    // transport.unloaded on unit deletion). All entries are absent from wireUnitById so the
    // #2162 guard skips them, producing an empty resolved list. The fix must apply that
    // empty list to clear the stale Java unloaded state.
    final WireUnit transportApply2 =
        WireUnit.of(
            "u-trn-1",
            "transport",
            0,
            0,
            0,
            "Germans",
            null,
            false,
            false,
            false,
            false,
            0,
            false,
            false,
            null,
            List.of("u-inf-1"),
            null,
            false);

    final WireState wireB =
        new WireState(
            List.of(new WireTerritory("112 Sea Zone", "Germans", List.of(transportApply2))),
            List.of(),
            1,
            "nonCombatMove",
            "Germans",
            List.of());
    WireStateApplier.apply(gd, wireB, idMap);

    // Stale unloaded list must be cleared — transport has no dead cargo (#2268 regression fence).
    assertThat(transportUnit.getUnloaded())
        .as(
            "transport.unloaded must be empty after NCM wire apply when cargo was killed"
                + " — stale list causes NPE in RemoveUnitsHistoryChange (regression: #2268)")
        .isEmpty();
  }

  /**
   * Regression for #2268 (else branch): when wire sends an empty/null unloaded list but Java
   * transport still has stale cargo from a prior apply, the stale list must be cleared.
   */
  @Test
  void transportUnloaded_emptyOnWire_clearsStaleJavaUnloadedList() {
    final GameData gd = fresh();
    final ConcurrentMap<String, UUID> idMap = freshIdMap();

    // Apply 1: establish unloaded relationship.
    final WireUnit transportApply1 =
        WireUnit.of(
            "u-trn-1",
            "transport",
            0,
            0,
            0,
            "Germans",
            null,
            false,
            false,
            false,
            false,
            0,
            false,
            false,
            null,
            List.of("u-inf-1"),
            null,
            false);
    final WireUnit infantryApply1 =
        WireUnit.of(
            "u-inf-1",
            "infantry",
            0,
            0,
            0,
            "Germans",
            null,
            false,
            false,
            false,
            true,
            0,
            false,
            false,
            null,
            null,
            "Germany",
            false);

    WireStateApplier.apply(
        gd,
        new WireState(
            List.of(
                new WireTerritory("112 Sea Zone", "Germans", List.of(transportApply1)),
                new WireTerritory("Germany", "Germans", List.of(infantryApply1))),
            List.of(),
            1,
            "combatMove",
            "Germans",
            List.of()),
        idMap);

    final Territory seaZone = gd.getMap().getTerritoryOrNull("112 Sea Zone");
    assertThat(seaZone).isNotNull();
    final Unit transportUnit =
        seaZone.getUnits().stream()
            .filter(u -> u.getId().equals(idMap.get("u-trn-1")))
            .findFirst()
            .orElseThrow();
    assertThat(transportUnit.getUnloaded()).hasSize(1);

    // Apply 2: transport wire sends null unloaded (NCM phase — no unload happened yet).
    final WireUnit transportApply2 =
        WireUnit.of(
            "u-trn-1",
            "transport",
            0,
            0,
            0,
            "Germans",
            null,
            false,
            false,
            false,
            false,
            0,
            false,
            false,
            null,
            null, // unloaded = null on wire
            null,
            false);

    WireStateApplier.apply(
        gd,
        new WireState(
            List.of(new WireTerritory("112 Sea Zone", "Germans", List.of(transportApply2))),
            List.of(),
            1,
            "nonCombatMove",
            "Germans",
            List.of()),
        idMap);

    assertThat(transportUnit.getUnloaded())
        .as("stale Java unloaded list must be cleared when wire sends null unloaded (#2268)")
        .isEmpty();
  }

  // ---------- China production frontier (#2174) ----------

  @Test
  void appliesProductionFrontierClosedForChinese() {
    final GameData gd = fresh();
    // Default canonical XML sets Chinese to productionChinese_Burma_Road_Open.
    assertThat(gd.getPlayerList().getPlayerId("Chinese").getProductionFrontier().getName())
        .isEqualTo("productionChinese_Burma_Road_Open");

    final WireState wire =
        new WireState(
            List.of(),
            List.of(
                new WirePlayer(
                    "Chinese",
                    0,
                    List.of(),
                    false,
                    null,
                    null,
                    "productionChinese_Burma_Road_Closed")),
            1,
            "purchase",
            "Chinese",
            List.of());
    WireStateApplier.apply(gd, wire, freshIdMap());

    assertThat(gd.getPlayerList().getPlayerId("Chinese").getProductionFrontier().getName())
        .isEqualTo("productionChinese_Burma_Road_Closed");
  }

  @Test
  void productionFrontierNullLeavesChineseFrontierUnchanged() {
    final GameData gd = fresh();
    final String originalFrontier =
        gd.getPlayerList().getPlayerId("Chinese").getProductionFrontier().getName();

    final WireState wire =
        new WireState(
            List.of(),
            List.of(new WirePlayer("Chinese", 0, List.of(), false)),
            1,
            "purchase",
            "Chinese",
            List.of());
    WireStateApplier.apply(gd, wire, freshIdMap());

    assertThat(gd.getPlayerList().getPlayerId("Chinese").getProductionFrontier().getName())
        .isEqualTo(originalFrontier);
  }

  @Test
  void appliesProductionFrontierOpenForChinese() {
    final GameData gd = fresh();
    // Manually force Closed first so we can verify Open restores it.
    gd.getPlayerList()
        .getPlayerId("Chinese")
        .setProductionFrontier(
            gd.getProductionFrontierList()
                .getProductionFrontier("productionChinese_Burma_Road_Closed"));
    assertThat(gd.getPlayerList().getPlayerId("Chinese").getProductionFrontier().getName())
        .isEqualTo("productionChinese_Burma_Road_Closed");

    final WireState wire =
        new WireState(
            List.of(),
            List.of(
                new WirePlayer(
                    "Chinese",
                    0,
                    List.of(),
                    false,
                    null,
                    null,
                    "productionChinese_Burma_Road_Open")),
            1,
            "purchase",
            "Chinese",
            List.of());
    WireStateApplier.apply(gd, wire, freshIdMap());

    assertThat(gd.getPlayerList().getPlayerId("Chinese").getProductionFrontier().getName())
        .isEqualTo("productionChinese_Burma_Road_Open");
  }

  /**
   * Regression for map-room#2210: phantom units left in a player's holding pool (e.g. from a failed
   * invokePlaceForSidecar where §20 validation rejected the placement) must be cleared when
   * WireStateApplier applies the next turn's wire state.
   *
   * <p>Before the fix: {@link WireStateApplier#applyPlayer} reconciled PUs and tech but never
   * touched the holding pool. Stale units accumulated there across HTTP request cycles, inflating
   * subsequent place-phase outputs and causing false "factory_minor at Kwangtung" log entries.
   */
  @Test
  void holdingPoolClearedWhenWireApplied() {
    final GameData gd = fresh();
    final GamePlayer germans = gd.getPlayerList().getPlayerId("Germans");

    // Simulate what invokePlaceForSidecar does: add a unit to the holding pool before placement.
    // In the failure case (§20 validation rejects the place), the unit stays in the holding pool
    // rather than being moved to the territory.
    final UnitType infantry = gd.getUnitTypeList().getUnitTypeOrThrow("infantry");
    final Unit phantomUnit = infantry.create(1, germans).get(0);
    gd.performChange(ChangeFactory.addUnits(germans, List.of(phantomUnit)));
    assertThat(germans.getUnitCollection().getUnits())
        .as("precondition: phantom in holding pool")
        .hasSize(1);

    // Apply a wire state for the next turn — the holding pool is always empty between turns.
    // The wire state includes the Germans player (with their current PUs) but no holding-pool
    // units.
    final int currentPus = germans.getResources().getQuantity("PUs");
    final WireState wire =
        new WireState(
            List.of(),
            List.of(new WirePlayer("Germans", currentPus, List.of(), false)),
            1,
            "purchase",
            "Germans",
            List.of());
    WireStateApplier.apply(gd, wire, freshIdMap());

    assertThat(germans.getUnitCollection().getUnits())
        .as(
            "holding pool must be empty after wire apply — phantom from failed place must be"
                + " cleared (regression: map-room#2210)")
        .isEmpty();
  }
}
