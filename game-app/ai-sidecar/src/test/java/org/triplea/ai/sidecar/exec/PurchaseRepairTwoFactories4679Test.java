package org.triplea.ai.sidecar.exec;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.Constants;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.settings.ClientSetting;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.dto.PurchasePlan;
import org.triplea.ai.sidecar.dto.PurchaseRequest;
import org.triplea.ai.sidecar.dto.RepairOrder;
import org.triplea.ai.sidecar.wire.WireState;

/**
 * map-room#4679: ProAI calls {@code purchaseRepair} once per damaged factory. {@link
 * RecordingPurchaseDelegate} replaced its captured map on every call, so only the last factory's
 * repair reached the wire plan, while {@code super.purchaseRepair} had repaired every factory in
 * the simulation. Map Room then kept the dropped factory damaged and the planned buys overran its
 * production capacity (Anniversary seed 4609: Poland and Karelia repaired, only Karelia sent).
 */
class PurchaseRepairTwoFactories4679Test {
  private static final String MAP = "ww2global40_2nd_edition";

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
  }

  private static Unit factoryIn(final GameData data, final String territory) {
    return data.getMap().getTerritoryOrThrow(territory).getUnits().stream()
        .filter(Matches.unitCanProduceUnits())
        .findFirst()
        .orElseThrow();
  }

  @Test
  void bothDamagedFactoriesAreRepairedOnTheWire() throws Exception {
    final GameData data = CanonicalGameData.load(MAP + ".xml").cloneForSession();
    final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
    data.performChange(
        ChangeFactory.unitPropertyChange(factoryIn(data, "Germany"), 6, "unitDamage"));
    data.performChange(
        ChangeFactory.unitPropertyChange(factoryIn(data, "Western Germany"), 4, "unitDamage"));
    final var pus = data.getResourceList().getResourceOrThrow(Constants.PUS);
    final int have = germans.getResources().getQuantity(pus);
    data.performChange(ChangeFactory.changeResourcesChange(germans, pus, 60 - have));

    final PurchasePlan plan =
        new PurchaseExecutor()
            .executeOn(
                data,
                new PurchaseRequest(
                    new WireState(List.of(), List.of(), 3, "purchase", "Germans", List.of(), MAP),
                    4679L));

    final Map<String, Integer> byTerritory =
        plan.repairs().stream()
            .collect(
                Collectors.groupingBy(
                    RepairOrder::territory, Collectors.summingInt(RepairOrder::repairCount)));
    assertThat(byTerritory)
        .as("repairs %s", plan.repairs())
        .containsExactlyInAnyOrderEntriesOf(Map.of("Germany", 6, "Western Germany", 4));
  }
}
