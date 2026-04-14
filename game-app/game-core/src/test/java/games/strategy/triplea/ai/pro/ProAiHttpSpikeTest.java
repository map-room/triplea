package games.strategy.triplea.ai.pro;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameSequence;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.player.PlayerBridge;
import games.strategy.triplea.delegate.PurchaseDelegate;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.xml.TestMapGameData;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

/**
 * Issue #1735 — step 1 of the ProAI HTTP service spike.
 *
 * <p>Drives {@code AbstractProAi.purchase(false, ...)} against a freshly loaded Global 1940 game
 * data for the Germans, detached from any running game loop. Times ProData construction (implicit
 * in purchase()) and the end-to-end purchase decision, and asserts that the call does not throw.
 *
 * <p>Run with: {@code ./gradlew :game-app:game-core:test --tests
 * games.strategy.triplea.ai.pro.ProAiHttpSpikeTest}.
 */
public class ProAiHttpSpikeTest {

  @Test
  public void germansPurchaseOnGlobal1940() {
    ClientSetting.setPreferences(new MemoryPreferences());

    final long loadStart = System.nanoTime();
    final GameData gameData = TestMapGameData.GLOBAL1940.getGameData();
    final long loadNs = System.nanoTime() - loadStart;

    final GamePlayer germans = gameData.getPlayerList().getPlayerId("Germans");
    Assertions.assertNotNull(germans, "Germans player must exist in Global 1940");

    // Advance the sequence to Germany's purchase step so AbstractProAi.purchase can find
    // subsequent combat/noncombat/place steps for simulation.
    advanceToPurchaseStepFor(gameData, "Germans");

    final ProAi proAi = new ProAi("Spike", "Germans");
    final IDelegateBridge bridge = newDelegateBridge(germans);
    final PurchaseDelegate purchaseDelegate = (PurchaseDelegate) gameData.getDelegate("purchase");
    purchaseDelegate.setDelegateBridgeAndPlayer(bridge);

    final PlayerBridge playerBridgeMock = Mockito.mock(PlayerBridge.class);
    when(playerBridgeMock.getGameData()).thenReturn(gameData);

    proAi.initialize(playerBridgeMock, germans);

    final int pus = germans.getResources().getQuantity("PUs");
    System.out.printf(
        "[spike] Global 1940 load=%.1fms, germans PUs=%d%n", loadNs / 1_000_000.0, pus);

    final long t0 = System.nanoTime();
    Assertions.assertDoesNotThrow(
        () -> proAi.purchase(false, pus, purchaseDelegate, gameData, germans));
    final long firstNs = System.nanoTime() - t0;

    // Second call to measure warm latency (ProData should still rebuild because purchase()
    // calls initializeData() internally every time, but JIT is warm).
    final long t1 = System.nanoTime();
    Assertions.assertDoesNotThrow(
        () -> proAi.purchase(false, pus, purchaseDelegate, gameData, germans));
    final long secondNs = System.nanoTime() - t1;

    System.out.printf(
        "[spike] purchase cold=%.1fms warm=%.1fms%n",
        firstNs / 1_000_000.0, secondNs / 1_000_000.0);
  }

  /**
   * Walks the game sequence until the current step is the {@code purchase} step for the given
   * player. Required because AbstractProAi.purchase simulates forward from
   * {@code sequence.getStepIndex() + 1}, so the caller must position the sequence on the player's
   * purchase step before invoking the AI.
   */
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
