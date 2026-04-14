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
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.player.PlayerBridge;
import games.strategy.engine.random.PlainRandomSource;
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
 * Issue #1735 — step 4 of the ProAI HTTP service spike.
 *
 * <p>Asserts that two independent ProAi.purchase runs with the same {@link
 * PlainRandomSource#setSeedOverride global seed override} produce identical {@link IntegerMap}
 * output. Proves that the 20-line ThreadLocal/static-seed patch on {@link PlainRandomSource} is
 * enough to drive byte-identical replay for the purchase code path — at least at PU levels that
 * actually exercise the odds simulator.
 */
public class ProAiSeededRngSpikeTest {

  @Test
  public void seededPurchaseIsDeterministic() throws Exception {
    ClientSetting.setPreferences(new MemoryPreferences());

    PlainRandomSource.setSeedOverride(42L);
    try {
      final String first = runPurchase(120);
      final String second = runPurchase(120);
      System.out.println("[spike-seed] run 1: " + first);
      System.out.println("[spike-seed] run 2: " + second);
      Assertions.assertEquals(first, second, "seeded PlainRandomSource should be deterministic");
    } finally {
      PlainRandomSource.clearSeedOverride();
    }
  }

  private static String runPurchase(final int pus) {
    final GameData gameData = TestMapGameData.GLOBAL1940.getGameData();
    final GamePlayer germans = gameData.getPlayerList().getPlayerId("Germans");
    advanceToPurchaseStepFor(gameData, "Germans");

    final ProAi proAi = new ProAi("Spike", "Germans");
    final IDelegateBridge bridge = newDelegateBridge(germans);
    final PurchaseDelegate realDelegate = (PurchaseDelegate) gameData.getDelegate("purchase");
    realDelegate.setDelegateBridgeAndPlayer(bridge);
    final PlayerBridge playerBridgeMock = Mockito.mock(PlayerBridge.class);
    when(playerBridgeMock.getGameData()).thenReturn(gameData);
    proAi.initialize(playerBridgeMock, germans);

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

    proAi.purchase(false, pus, spiedDelegate, gameData, germans);
    return summarize(captured.get());
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
    return ordered.toString();
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
