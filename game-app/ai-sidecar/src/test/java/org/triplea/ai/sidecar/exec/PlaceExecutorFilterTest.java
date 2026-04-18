package org.triplea.ai.sidecar.exec;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
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
 * RecordingPlaceDelegate.PlaceCapture} whose territory was conquered this turn must be
 * dropped from the resulting {@link PlacePlan}, since the map-room engine rejects placement
 * at freshly captured territories (rules §16/§20). Pro AI itself does not consult
 * wasConquered when picking placement targets, so this filter is the safety net.
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
    final UnitType infantry =
        gd.getUnitTypeList().getUnitType("infantry").orElseThrow();
    final Unit infInGermany =
        new Unit(UUID.randomUUID(), infantry, gd.getPlayerList().getPlayerId("Germans"), gd);
    final Unit infInFrance =
        new Unit(UUID.randomUUID(), infantry, gd.getPlayerList().getPlayerId("Germans"), gd);

    final List<RecordingPlaceDelegate.PlaceCapture> captures =
        List.of(
            new RecordingPlaceDelegate.PlaceCapture(List.of(infInGermany), germany),
            new RecordingPlaceDelegate.PlaceCapture(List.of(infInFrance), france));

    final PlacePlan plan =
        PlaceExecutor.groupCapturesIntoPlan(captures, Set.of(france), "test-session");

    assertThat(plan.placements()).hasSize(1);
    assertThat(plan.placements().get(0).territoryName()).isEqualTo("Germany");
    assertThat(plan.placements().get(0).unitTypes()).containsExactly("infantry");
  }

  @Test
  void groupCapturesIntoPlan_keepsAllWhenNoConqueredTerritories() {
    final GameData gd = canonical.cloneForSession();
    final Territory germany = gd.getMap().getTerritoryOrThrow("Germany");
    final Territory france = gd.getMap().getTerritoryOrThrow("France");
    final UnitType infantry =
        gd.getUnitTypeList().getUnitType("infantry").orElseThrow();
    final Unit infInGermany =
        new Unit(UUID.randomUUID(), infantry, gd.getPlayerList().getPlayerId("Germans"), gd);
    final Unit infInFrance =
        new Unit(UUID.randomUUID(), infantry, gd.getPlayerList().getPlayerId("Germans"), gd);

    final List<RecordingPlaceDelegate.PlaceCapture> captures =
        List.of(
            new RecordingPlaceDelegate.PlaceCapture(List.of(infInGermany), germany),
            new RecordingPlaceDelegate.PlaceCapture(List.of(infInFrance), france));

    final PlacePlan plan =
        PlaceExecutor.groupCapturesIntoPlan(captures, Set.of(), "test-session");

    assertThat(plan.placements()).hasSize(2);
    assertThat(plan.placements())
        .extracting(p -> p.territoryName())
        .containsExactlyInAnyOrder("Germany", "France");
  }

  @Test
  void groupCapturesIntoPlan_returnsEmptyPlanWhenAllCapturesAreConquered() {
    final GameData gd = canonical.cloneForSession();
    final Territory france = gd.getMap().getTerritoryOrThrow("France");
    final UnitType infantry =
        gd.getUnitTypeList().getUnitType("infantry").orElseThrow();
    final Unit infInFrance =
        new Unit(UUID.randomUUID(), infantry, gd.getPlayerList().getPlayerId("Germans"), gd);

    final PlacePlan plan =
        PlaceExecutor.groupCapturesIntoPlan(
            List.of(new RecordingPlaceDelegate.PlaceCapture(List.of(infInFrance), france)),
            Set.of(france),
            "test-session");

    assertThat(plan.placements()).isEmpty();
  }
}
