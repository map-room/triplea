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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.dto.PurchasePlan;
import org.triplea.ai.sidecar.dto.PurchaseRequest;
import org.triplea.ai.sidecar.dto.RepairOrder;
import org.triplea.ai.sidecar.wire.WireState;

/**
 * map-room#4616: the Russians held 18 IPCs against a 20-point factory repair, and the sidecar
 * returned the full 20-point repair anyway ({@code PURCHASE-BUDGET startPUs=18 repairCost=20}). The
 * engine refuses a repair the treasury cannot pay, so the bot replayed it 291 times.
 */
class PurchaseRepairAffordable4616Test {
  private static final String MAP = "ww2global40_2nd_edition";

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
  }

  private static PurchasePlan planWith(final int damage, final int treasury) throws Exception {
    final GameData data = CanonicalGameData.load(MAP + ".xml").cloneForSession();
    final GamePlayer russians = data.getPlayerList().getPlayerId("Russians");
    final Unit factory =
        data.getMap().getTerritoryOrThrow("Russia").getUnits().stream()
            .filter(Matches.unitCanProduceUnits())
            .findFirst()
            .orElseThrow();
    data.performChange(ChangeFactory.unitPropertyChange(factory, damage, "unitDamage"));
    final var pus = data.getResourceList().getResourceOrThrow(Constants.PUS);
    final int have = russians.getResources().getQuantity(pus);
    data.performChange(ChangeFactory.changeResourcesChange(russians, pus, treasury - have));
    return new PurchaseExecutor()
        .executeOn(
            data,
            new PurchaseRequest(
                new WireState(List.of(), List.of(), 3, "purchase", "Russians", List.of(), MAP),
                4616L));
  }

  private static int repaired(final PurchasePlan plan) {
    return plan.repairs().stream().mapToInt(RepairOrder::repairCount).sum();
  }

  @Test
  void repairsNoMorePointsThanTheTreasuryCanPay() throws Exception {
    final PurchasePlan plan = planWith(20, 18);
    assertThat(repaired(plan)).as("repairs %s", plan.repairs()).isEqualTo(18);
  }

  /** Control: an affordable repair is planned in full. */
  @Test
  void anAffordableRepairIsPlannedInFull() throws Exception {
    final PurchasePlan plan = planWith(6, 40);
    assertThat(repaired(plan)).as("repairs %s", plan.repairs()).isEqualTo(6);
  }

  @Test
  void anEmptyTreasuryRepairsNothing() throws Exception {
    final PurchasePlan plan = planWith(20, 0);
    assertThat(plan.repairs()).isEmpty();
  }
}
