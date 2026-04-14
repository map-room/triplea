package games.strategy.triplea.ai.pro;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameSequence;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.Resource;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.player.PlayerBridge;
import games.strategy.triplea.delegate.PurchaseDelegate;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.java.collections.IntegerMap;

/**
 * Issue #1735 — step 3 of the ProAI HTTP service spike.
 *
 * <p>Applies a minimal SessionDelta ({@code {players:[{playerId,PUs}]}}) to in-memory GameData and
 * runs {@link AbstractProAi#purchase} twice with different PU counts, capturing the resulting
 * {@link IntegerMap} of {@link ProductionRule} via a Mockito spy on the {@link PurchaseDelegate}.
 * Confirms that mutating the session state via the translator actually changes what the AI buys.
 */
public class ProAiSessionDeltaSpikeTest {

  @Test
  public void sessionDeltaDrivesDifferentPurchases() throws Exception {
    ClientSetting.setPreferences(new MemoryPreferences());

    final GameData gameData = TestMapGameData.GLOBAL1940.getGameData();
    final GamePlayer germans = gameData.getPlayerList().getPlayerId("Germans");
    final Resource pus = gameData.getResourceList().getResourceOrThrow("PUs");
    Assertions.assertNotNull(germans);
    Assertions.assertNotNull(pus);
    advanceToPurchaseStepFor(gameData, "Germans");

    final ProAi proAi = new ProAi("Spike", "Germans");
    final IDelegateBridge bridge = newDelegateBridge(germans);
    final PurchaseDelegate realDelegate = (PurchaseDelegate) gameData.getDelegate("purchase");
    realDelegate.setDelegateBridgeAndPlayer(bridge);
    final PlayerBridge playerBridgeMock = Mockito.mock(PlayerBridge.class);
    when(playerBridgeMock.getGameData()).thenReturn(gameData);
    proAi.initialize(playerBridgeMock, germans);

    // Spy on the real delegate so we can capture the IntegerMap<ProductionRule> passed to
    // purchase(...) — this is the canonical RPC output ProAi ultimately produces.
    final PurchaseDelegate spiedDelegate = spy(realDelegate);
    final AtomicReference<IntegerMap<ProductionRule>> captured = new AtomicReference<>();
    doAnswer(
            inv -> {
              @SuppressWarnings("unchecked")
              final IntegerMap<ProductionRule> arg = (IntegerMap<ProductionRule>) inv.getArgument(0);
              captured.set(arg == null ? null : new IntegerMap<>(arg));
              return inv.callRealMethod();
            })
        .when(spiedDelegate)
        .purchase(any());

    // --- Run 1: delta sets Germans PUs = 30 (canonical start) ---
    applySessionDelta(gameData, germans, pus, 30);
    captured.set(null);
    proAi.purchase(false, 30, spiedDelegate, gameData, germans);
    final IntegerMap<ProductionRule> buyAt30 = captured.get();

    // --- Run 2: delta bumps Germans PUs = 120 (should buy substantially more) ---
    applySessionDelta(gameData, germans, pus, 120);
    captured.set(null);
    proAi.purchase(false, 120, spiedDelegate, gameData, germans);
    final IntegerMap<ProductionRule> buyAt120 = captured.get();

    System.out.println("[spike-delta] buy @ PUs=30  : " + summarize(buyAt30));
    System.out.println("[spike-delta] buy @ PUs=120 : " + summarize(buyAt120));
    System.out.println("[spike-delta] dto sample    : " + toDtoJson(buyAt120, 120));

    Assertions.assertNotNull(buyAt30, "run 1 produced no purchase call");
    Assertions.assertNotNull(buyAt120, "run 2 produced no purchase call");
    Assertions.assertTrue(totalCost(buyAt120) > totalCost(buyAt30), "more PUs should spend more");
  }

  private static void applySessionDelta(
      final GameData gameData,
      final GamePlayer player,
      final Resource pusResource,
      final int newPuTotal) {
    final int current = player.getResources().getQuantity(pusResource);
    final int delta = newPuTotal - current;
    if (delta == 0) {
      return;
    }
    try (GameData.Unlocker ignored = gameData.acquireWriteLock()) {
      if (delta > 0) {
        player.getResources().addResource(pusResource, delta);
      } else {
        player.getResources().removeResourceUpTo(pusResource, -delta);
      }
    }
  }

  private static int totalCost(final IntegerMap<ProductionRule> buy) {
    if (buy == null) {
      return 0;
    }
    int sum = 0;
    for (final ProductionRule rule : buy.keySet()) {
      sum += buy.getInt(rule) * rule.getCosts().totalValues();
    }
    return sum;
  }

  private static String summarize(final IntegerMap<ProductionRule> buy) {
    if (buy == null || buy.isEmpty()) {
      return "(empty)";
    }
    final Map<String, Integer> ordered = new LinkedHashMap<>();
    for (final ProductionRule rule : buy.keySet()) {
      final int qty = buy.getInt(rule);
      if (qty > 0) {
        ordered.put(rule.getName(), qty);
      }
    }
    return ordered.toString() + "  cost=" + totalCost(buy);
  }

  private static String toDtoJson(final IntegerMap<ProductionRule> buy, final int pus) {
    final StringBuilder sb = new StringBuilder();
    sb.append("{\"kind\":\"purchase\",\"player\":\"Germans\",\"pus\":")
        .append(pus)
        .append(",\"buy\":[");
    boolean first = true;
    if (buy != null) {
      for (final ProductionRule rule : buy.keySet()) {
        final int qty = buy.getInt(rule);
        if (qty <= 0) {
          continue;
        }
        if (!first) {
          sb.append(",");
        }
        first = false;
        sb.append("{\"rule\":\"")
            .append(rule.getName())
            .append("\",\"qty\":")
            .append(qty)
            .append("}");
      }
    }
    sb.append("],\"spent\":").append(totalCost(buy)).append("}");
    return sb.toString();
  }

  private static void advanceToPurchaseStepFor(final GameData gameData, final String playerName) {
    final GameSequence sequence = gameData.getSequence();
    int tries = 0;
    while (tries++ < 200) {
      final GameStep step = sequence.getStep();
      if (step != null
          && step.getPlayerId() != null
          && playerName.equals(step.getPlayerId().getName())
          && GameStep.isPurchaseStepName(step.getName())) {
        return;
      }
      sequence.next();
    }
    throw new IllegalStateException("Never reached " + playerName + " purchase step");
  }
}
