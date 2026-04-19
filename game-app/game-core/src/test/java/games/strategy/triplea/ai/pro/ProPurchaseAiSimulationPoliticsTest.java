package games.strategy.triplea.ai.pro;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.triplea.xml.TestMapGameData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code simulatePoliticsInPurchase} flag on {@link AbstractProAi}.
 *
 * <p>When the sidecar calls {@link AbstractProAi#invokePurchaseForSidecar}, real politics has
 * already executed on the session GameData before purchase begins. The simulation loop inside
 * {@link AbstractProAi#purchase} must skip the politics step to avoid re-rolling RNG and
 * speculatively declaring additional wars — which would inflate attack-option scoring and produce
 * purchases for territories that won't actually be conquered.
 *
 * <p>These tests verify the flag's default value, setter behaviour, and the try/finally restoration
 * contract inside {@link AbstractProAi#invokePurchaseForSidecar}. The full end-to-end verification
 * (confirming no speculative war declarations pollute the purchase plan) requires integration
 * testing against a live sidecar session.
 */
class ProPurchaseAiSimulationPoliticsTest {

  private ProAi proAi;

  @BeforeEach
  void setUp() {
    proAi = new ProAi("test-ai", "Japanese");
  }

  /** Flag must start true — existing full-game TripleA code paths must be unaffected. */
  @Test
  void defaultFlagIsTrue() {
    assertTrue(
        proAi.isSimulatePoliticsInPurchase(),
        "simulatePoliticsInPurchase must default to true to preserve full-game TripleA behaviour");
  }

  /** Setter must update the flag. */
  @Test
  void setFlagFalseIsReflected() {
    proAi.setSimulatePoliticsInPurchase(false);
    assertFalse(
        proAi.isSimulatePoliticsInPurchase(),
        "setSimulatePoliticsInPurchase(false) must set the flag to false");
  }

  /** Setter round-trip: false then back to true. */
  @Test
  void setFlagTrueAfterFalseIsReflected() {
    proAi.setSimulatePoliticsInPurchase(false);
    proAi.setSimulatePoliticsInPurchase(true);
    assertTrue(
        proAi.isSimulatePoliticsInPurchase(),
        "setSimulatePoliticsInPurchase(true) after false must restore the flag to true");
  }

  /**
   * Verifies that {@link AbstractProAi#invokePurchaseForSidecar} restores the flag to {@code true}
   * in its {@code finally} block even when the underlying {@link AbstractProAi#purchase} throws
   * (which happens on an uninitialized AI with no game data).
   *
   * <p>This test exercises the try/finally restoration contract without requiring a full game
   * session.
   */
  @Test
  void invokePurchaseForSidecar_restoresFlagAfterException() {
    // Load minimal game data so purchase() can at least start before bailing out.
    final GameData data = TestMapGameData.GLOBAL1940.getGameData();
    final GamePlayer japanese = data.getPlayerList().getPlayerId("Japanese");

    // Confirm default state.
    assertTrue(proAi.isSimulatePoliticsInPurchase(), "precondition: flag must start true");

    // invokePurchaseForSidecar internally sets the flag to false then restores it.
    // It may throw (no calcdata, no bridge initialisation, etc.) — we don't care;
    // the finally block must fire regardless.
    try {
      proAi.invokePurchaseForSidecar(false, 0, null, data, japanese);
    } catch (final Exception ignored) {
      // Expected: purchase will fail without a fully-initialised session.
    }

    // Regardless of whether purchase threw, the try/finally must have restored the flag.
    assertTrue(
        proAi.isSimulatePoliticsInPurchase(),
        "invokePurchaseForSidecar must restore simulatePoliticsInPurchase=true in its finally block");
  }
}
