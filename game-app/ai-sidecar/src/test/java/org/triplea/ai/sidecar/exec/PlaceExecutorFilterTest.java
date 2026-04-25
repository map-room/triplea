package org.triplea.ai.sidecar.exec;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.settings.ClientSetting;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.dto.PlacePlan;

/**
 * Unit tests for {@link PlaceExecutor#groupCapturesIntoPlan}: any {@link
 * RecordingPlaceDelegate.PlaceCapture} whose territory was conquered this turn must be dropped from
 * the resulting {@link PlacePlan} for regular nations, since the map-room engine rejects placement
 * at freshly captured territories (rules §16/§20). China (placementAnyTerritory=true) is exempt
 * — it may place on any owned territory including those captured this turn.
 */
class PlaceExecutorFilterTest {

  private static CanonicalGameData canonical;

  @BeforeAll
  static void init() {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load();
  }

  @Test
  void groupCapturesIntoPlan_dropsCapturesAtConqueredTerritories() {
    final GameData gd = canonical.cloneForSession();
    final Territory germany = gd.getMap().getTerritoryOrThrow("Germany");
    final Territory france = gd.getMap().getTerritoryOrThrow("France");
    final UnitType infantry = gd.getUnitTypeList().getUnitType("infantry").orElseThrow();
    final Unit infInGermany =
        new Unit(UUID.randomUUID(), infantry, gd.getPlayerList().getPlayerId("Germans"), gd);
    final Unit infInFrance =
        new Unit(UUID.randomUUID(), infantry, gd.getPlayerList().getPlayerId("Germans"), gd);

    final List<RecordingPlaceDelegate.PlaceCapture> captures =
        List.of(
            new RecordingPlaceDelegate.PlaceCapture(List.of(infInGermany), germany),
            new RecordingPlaceDelegate.PlaceCapture(List.of(infInFrance), france));

    final PlacePlan plan =
        PlaceExecutor.groupCapturesIntoPlan(captures, Set.of(france), "test-session", false);

    assertThat(plan.placements()).hasSize(1);
    assertThat(plan.placements().get(0).territoryName()).isEqualTo("Germany");
    assertThat(plan.placements().get(0).unitTypes()).containsExactly("infantry");
  }

  @Test
  void groupCapturesIntoPlan_keepsAllWhenNoConqueredTerritories() {
    final GameData gd = canonical.cloneForSession();
    final Territory germany = gd.getMap().getTerritoryOrThrow("Germany");
    final Territory france = gd.getMap().getTerritoryOrThrow("France");
    final UnitType infantry = gd.getUnitTypeList().getUnitType("infantry").orElseThrow();
    final Unit infInGermany =
        new Unit(UUID.randomUUID(), infantry, gd.getPlayerList().getPlayerId("Germans"), gd);
    final Unit infInFrance =
        new Unit(UUID.randomUUID(), infantry, gd.getPlayerList().getPlayerId("Germans"), gd);

    final List<RecordingPlaceDelegate.PlaceCapture> captures =
        List.of(
            new RecordingPlaceDelegate.PlaceCapture(List.of(infInGermany), germany),
            new RecordingPlaceDelegate.PlaceCapture(List.of(infInFrance), france));

    final PlacePlan plan =
        PlaceExecutor.groupCapturesIntoPlan(captures, Set.of(), "test-session", false);

    assertThat(plan.placements()).hasSize(2);
    assertThat(plan.placements())
        .extracting(p -> p.territoryName())
        .containsExactlyInAnyOrder("Germany", "France");
  }

  @Test
  void groupCapturesIntoPlan_returnsEmptyPlanWhenAllCapturesAreConquered() {
    final GameData gd = canonical.cloneForSession();
    final Territory france = gd.getMap().getTerritoryOrThrow("France");
    final UnitType infantry = gd.getUnitTypeList().getUnitType("infantry").orElseThrow();
    final Unit infInFrance =
        new Unit(UUID.randomUUID(), infantry, gd.getPlayerList().getPlayerId("Germans"), gd);

    final PlacePlan plan =
        PlaceExecutor.groupCapturesIntoPlan(
            List.of(new RecordingPlaceDelegate.PlaceCapture(List.of(infInFrance), france)),
            Set.of(france),
            "test-session",
            false);

    assertThat(plan.placements()).isEmpty();
  }

  @Test
  void groupCapturesIntoPlan_keepsConqueredTerritoriesForPlacementAnywherePlayer() {
    // China has placementAnyTerritory=true — conquered-this-turn territories must NOT be filtered.
    final GameData gd = canonical.cloneForSession();
    final Territory szechwan = gd.getMap().getTerritoryOrThrow("Szechwan");
    final Territory manchuria = gd.getMap().getTerritoryOrThrow("Manchuria");
    final UnitType infantry = gd.getUnitTypeList().getUnitType("infantry").orElseThrow();
    final GamePlayer china = gd.getPlayerList().getPlayerId("Chinese");
    final Unit infInSzechwan = new Unit(UUID.randomUUID(), infantry, china, gd);
    final Unit infInManchuria = new Unit(UUID.randomUUID(), infantry, china, gd);

    final List<RecordingPlaceDelegate.PlaceCapture> captures =
        List.of(
            new RecordingPlaceDelegate.PlaceCapture(List.of(infInSzechwan), szechwan),
            new RecordingPlaceDelegate.PlaceCapture(List.of(infInManchuria), manchuria));

    // Manchuria conquered this turn, but China can still place there (canPlaceInConquered=true)
    final PlacePlan plan =
        PlaceExecutor.groupCapturesIntoPlan(
            captures, Set.of(manchuria), "test-session", /* canPlaceInConquered= */ true);

    assertThat(plan.placements()).hasSize(2);
    assertThat(plan.placements())
        .extracting(p -> p.territoryName())
        .containsExactlyInAnyOrder("Szechwan", "Manchuria");
  }
}
