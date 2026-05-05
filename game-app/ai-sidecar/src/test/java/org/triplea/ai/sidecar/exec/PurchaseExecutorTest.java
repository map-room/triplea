package org.triplea.ai.sidecar.exec;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.NamedAttachable;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.Constants;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.settings.ClientSetting;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.dto.PurchaseOrder;
import org.triplea.ai.sidecar.dto.PurchasePlan;
import org.triplea.ai.sidecar.dto.PurchaseRequest;
import org.triplea.ai.sidecar.session.Session;
import org.triplea.ai.sidecar.session.SessionKey;
import org.triplea.ai.sidecar.wire.WireState;

class PurchaseExecutorTest {

  private static CanonicalGameData canonical;

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load();
  }

  private Session freshSession(final String nation) {
    final GameData data = canonical.cloneForSession();
    final ProAi proAi = new ProAi("sidecar-test-" + nation, nation);
    return new Session(
        "s-test-" + UUID.randomUUID(),
        new SessionKey("g1", nation),
        42L,
        proAi,
        data,
        new ConcurrentHashMap<>(),
        Executors.newSingleThreadExecutor());
  }

  private static PurchaseRequest purchaseRequestFor(final String nation) {
    return new PurchaseRequest(
        new WireState(List.of(), List.of(), 1, "purchase", nation, List.of()));
  }

  private static int playerPus(final GameData data, final String nation) {
    final GamePlayer p = data.getPlayerList().getPlayerId(nation);
    final Resource pus = data.getResourceList().getResourceOrThrow(Constants.PUS);
    return p.getResources().getQuantity(pus);
  }

  private static int totalCost(final GameData data, final PurchasePlan plan) {
    final Resource pus = data.getResourceList().getResourceOrThrow(Constants.PUS);
    int sum = 0;
    for (final PurchaseOrder order : plan.buys()) {
      final ProductionRule rule = findRuleForUnitType(data, order.unitType());
      if (rule == null) {
        continue;
      }
      sum += rule.getCosts().getInt(pus) * order.count();
    }
    return sum;
  }

  private static ProductionRule findRuleForUnitType(
      final GameData data, final String unitTypeName) {
    for (final ProductionRule rule : data.getProductionRuleList().getProductionRules()) {
      for (final NamedAttachable result : rule.getResults().keySet()) {
        if (result instanceof UnitType ut && ut.getName().equals(unitTypeName)) {
          return rule;
        }
      }
    }
    return null;
  }

  @Test
  void returnsNonEmptyPlanForTurn1Germans() throws Exception {
    final Session session = freshSession("Germans");
    final PurchasePlan plan =
        new PurchaseExecutor().execute(session, purchaseRequestFor("Germans"));

    assertThat(plan.buys()).isNotEmpty();
    final int budget = playerPus(session.gameData(), "Germans");
    assertThat(totalCost(session.gameData(), plan)).isLessThanOrEqualTo(budget);
  }

  @Test
  void trimToFitClampsWhenProAiOverruns() throws Exception {
    final Session session = freshSession("Germans");
    final GameData data = session.gameData();
    final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
    final Resource pus = data.getResourceList().getResourceOrThrow(Constants.PUS);
    final int current = germans.getResources().getQuantity(pus);
    // Zero out Germans' PUs so any ProAi overrun is clamped to empty by trimToFit.
    data.performChange(ChangeFactory.changeResourcesChange(germans, pus, -current));

    final PurchasePlan plan =
        new PurchaseExecutor().execute(session, purchaseRequestFor("Germans"));

    assertThat(totalCost(data, plan)).isEqualTo(0);
  }

  /**
   * Regression for map-room#2194: factory repair cost must be deducted from the unit-purchase
   * budget. Before the fix, {@link RecordingPurchaseDelegate#purchaseRepair} captured but did NOT
   * deduct repair IPC from {@code player.getResources()}, so {@code ProPurchaseAi.purchase()}
   * initialized its {@link games.strategy.triplea.ai.pro.data.ProResourceTracker} with the full
   * pre-repair budget. Combined cost (repair + unit buys) could then exceed available IPC →
   * engine rejected "ran out of IPC".
   */
  @Test
  void repairCostDeductedFromUnitBudgetWhenFactoryIsDamaged() {
    final Session session = freshSession("Germans");
    final GameData data = session.gameData();
    final Resource pus = data.getResourceList().getResourceOrThrow(Constants.PUS);
    final GamePlayer player = data.getPlayerList().getPlayerId("Germans");

    // Apply 7 points of SBR damage to Germany's factory.
    final Territory germany = data.getMap().getTerritoryOrThrow("Germany");
    final Unit factory =
        germany.getUnitCollection().stream()
            .filter(Matches.unitCanProduceUnitsAndCanBeDamaged())
            .findFirst()
            .orElseThrow(() -> new AssertionError("No damageable factory found in Germany"));
    final int factoryDamage = 7;
    final var damageMap = new org.triplea.java.collections.IntegerMap<Unit>();
    damageMap.put(factory, factoryDamage);
    data.performChange(ChangeFactory.bombingUnitDamage(damageMap, List.of(germany)));

    final int startingPus = player.getResources().getQuantity(pus);

    final PurchasePlan plan = new PurchaseExecutor().execute(session, purchaseRequestFor("Germans"));

    // ProAi must have decided to repair (the factory has damage and the player can afford it).
    assertThat(plan.repairs())
        .as("ProAi should repair the damaged factory")
        .isNotEmpty();

    // Repair cost is 1 PU per damage point (Global 1940 standard).
    final int repairCost =
        plan.repairs().stream()
            .mapToInt(org.triplea.ai.sidecar.dto.RepairOrder::repairCount)
            .sum();
    final int unitCost = totalCost(data, plan);

    assertThat(repairCost + unitCost)
        .as(
            "repair (%d) + unit buys (%d) must not exceed starting IPC (%d)",
            repairCost, unitCost, startingPus)
        .isLessThanOrEqualTo(startingPus);
  }

  @Test
  void translatesProductionRuleToUnitTypeName() throws Exception {
    final Session session = freshSession("Germans");
    final PurchasePlan plan =
        new PurchaseExecutor().execute(session, purchaseRequestFor("Germans"));

    for (final PurchaseOrder order : plan.buys()) {
      assertThat(order.unitType()).isNotNull();
      assertThat(order.count()).isGreaterThan(0);
      // The unit type must resolve to a real ProductionRule on the canonical map.
      assertThat(findRuleForUnitType(session.gameData(), order.unitType()))
          .as("unit type %s has a production rule", order.unitType())
          .isNotNull();
    }
  }
}
