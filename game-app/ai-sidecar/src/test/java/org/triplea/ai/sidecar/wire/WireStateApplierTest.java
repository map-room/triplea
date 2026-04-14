package org.triplea.ai.sidecar.wire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
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
            "Russians");
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
                    "Germany",
                    "Germans",
                    List.of(new WireUnit("u-inf-1", "infantry", 0, 0)))),
            List.of(),
            1,
            "combat",
            "Germans");
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
            "Germans");
    WireStateApplier.apply(gd, wire, freshIdMap());
    assertThat(
            gd.getPlayerList().getPlayerId("Germans").getResources().getQuantity("PUs"))
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
            "Germans");
    WireStateApplier.apply(gd, wire, idMap);

    final Territory germany = gd.getMap().getTerritoryOrThrow("Germany");
    assertThat(germany.getUnits()).hasSize(3);
    final Map<String, Unit> byType =
        germany.getUnits().stream()
            .collect(Collectors.toMap(u -> u.getType().getName(), u -> u));
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
                    "Germany",
                    "Germans",
                    List.of(new WireUnit("u-def-inf-1", "infantry", 0, 0)))),
            List.of(),
            1,
            "combat",
            "Germans");
    WireStateApplier.apply(gd, wire, idMap);

    assertThat(gd.getMap().getTerritoryOrThrow("France").getUnits()).hasSize(2);
    assertThat(gd.getMap().getTerritoryOrThrow("Germany").getUnits()).hasSize(1);
    assertThat(idMap)
        .containsKeys("u-atk-inf-1", "u-atk-tank-1", "u-def-inf-1");
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
            "Germans");
    WireStateApplier.apply(gd, wire, idMap);

    final Territory wg = gd.getMap().getTerritoryOrThrow("Western Germany");
    assertThat(
            wg.getUnits().stream().map(u -> u.getType().getName()).collect(Collectors.toSet()))
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
            "Germans");
    WireStateApplier.apply(gd, a, idMap);
    assertThat(gd.getMap().getTerritoryOrThrow("Germany").getUnits()).hasSize(2);

    final WireState b =
        new WireState(
            List.of(
                new WireTerritory(
                    "Germany",
                    "Russians",
                    List.of(new WireUnit("u-inf-1", "infantry", 0, 0)))),
            List.of(),
            1,
            "combat",
            "Russians");
    WireStateApplier.apply(gd, b, idMap);

    final Territory germany = gd.getMap().getTerritoryOrThrow("Germany");
    assertThat(germany.getOwner().getName()).isEqualTo("Russians");
    // Only u-inf-1 survives; u-inf-2 was pruned because it was absent from wire B.
    assertThat(germany.getUnits()).hasSize(1);
    assertThat(germany.getUnits().iterator().next().getId())
        .isEqualTo(idMap.get("u-inf-1"));
  }

  @Test
  void unitIdStability_sameWireIdMapsToSameUuidAcrossApplies() {
    final GameData gd = fresh();
    final ConcurrentMap<String, UUID> idMap = freshIdMap();
    final WireState wire =
        new WireState(
            List.of(
                new WireTerritory(
                    "Germany",
                    "Germans",
                    List.of(new WireUnit("u-inf-1", "infantry", 0, 0)))),
            List.of(),
            1,
            "combat",
            "Germans");
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
            "Germans");
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
            "Germans");
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
                    "Germany",
                    "Germans",
                    List.of(new WireUnit("u-x", "deathstar", 0, 0)))),
            List.of(),
            1,
            "combat",
            "Germans");
    assertThatThrownBy(() -> WireStateApplier.apply(gd, wire, freshIdMap()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("deathstar");
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
            "Germans");
    WireStateApplier.apply(gd, wire, freshIdMap());
    assertThat(gd.getPlayerList().getPlayerId("Germans").getTechAttachment().getHeavyBomber())
        .isTrue();
  }
}
