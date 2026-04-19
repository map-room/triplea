package org.triplea.ai.sidecar.exec;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.dto.WireMoveDescription;
import org.triplea.ai.sidecar.dto.WireUnitClassification;

class WireMoveDescriptionBuilderTest {
  private static CanonicalGameData canonical;
  private GameData data;
  private Territory germany;
  private Territory sz112;

  @BeforeAll
  static void loadCanonical() {
    canonical = CanonicalGameData.load();
  }

  @BeforeEach
  void setUp() {
    data = canonical.cloneForSession();
    germany = data.getMap().getTerritoryOrThrow("Germany");
    sz112 = data.getMap().getTerritoryOrThrow("112 Sea Zone");
  }

  @Test
  void buildsLoadMdWithClassifications() {
    final Unit inf = unit("infantry");
    final Unit tp = unit("transport");
    final Map<UUID, String> wireMap = Map.of(inf.getId(), "W_inf", tp.getId(), "W_tp");
    final MoveDescription md =
        new MoveDescription(List.of(inf), new Route(germany, sz112), Map.of(inf, tp));

    final WireMoveDescription result = WireMoveDescriptionBuilder.build(md, wireMap);

    assertThat(result.unitIds()).containsExactly("W_inf");
    assertThat(result.from()).isEqualTo("Germany");
    assertThat(result.to()).isEqualTo("112 Sea Zone");
    assertThat(result.cargoToTransport()).containsExactly(Map.entry("W_inf", "W_tp"));
    assertThat(result.classifications())
        .containsEntry("W_inf", new WireUnitClassification(false, false))
        .containsEntry("W_tp", new WireUnitClassification(false, true));
    assertThat(result.airTransportsDependents()).isEmpty();
  }

  @Test
  void stripsAirUnitsFromCargoToTransport() {
    final Unit ftr = unit("fighter");
    final Unit cv = unit("carrier");
    final Map<UUID, String> wireMap = Map.of(ftr.getId(), "W_ftr", cv.getId(), "W_cv");
    final MoveDescription md =
        new MoveDescription(List.of(ftr, cv), new Route(sz112, germany), Map.of(ftr, cv));

    final WireMoveDescription result = WireMoveDescriptionBuilder.build(md, wireMap);

    assertThat(result.cargoToTransport()).isEmpty();
    assertThat(result.classifications())
        .containsEntry("W_ftr", new WireUnitClassification(true, false))
        // carrier has carrierCapacity, not transportCapacity — isTransport is false
        .containsEntry("W_cv", new WireUnitClassification(false, false));
  }

  @Test
  void dropsUnitsWithNoWireMapping() {
    final Unit inf = unit("infantry");
    final Map<UUID, String> wireMap = Map.of();

    final MoveDescription md =
        new MoveDescription(List.of(inf), new Route(germany, sz112), Map.of());

    final WireMoveDescription result = WireMoveDescriptionBuilder.build(md, wireMap);

    assertThat(result.unitIds()).isEmpty();
    assertThat(result.classifications()).isEmpty();
  }

  private Unit unit(final String type) {
    return data.getUnitTypeList()
        .getUnitTypeOrThrow(type)
        .createTemp(1, data.getPlayerList().getPlayers().iterator().next())
        .iterator()
        .next();
  }
}
