package org.triplea.ai.sidecar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import games.strategy.triplea.settings.ClientSetting;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.dto.NoncombatMoveRequest;
import org.triplea.ai.sidecar.dto.PurchasePlan;
import org.triplea.ai.sidecar.dto.PurchaseRequest;
import org.triplea.ai.sidecar.exec.NoncombatMoveExecutor;
import org.triplea.ai.sidecar.exec.PurchaseExecutor;
import org.triplea.ai.sidecar.wire.WireState;

/**
 * Replay-after-crash determinism gate (acceptance criterion for #2386).
 *
 * <p>The TS-side recovery flow is: bot worker dies mid-decision; replacement bot worker reads the
 * gamestate from bgio, dispatches {@code undoAllMoves} to roll the engine back to the start of the
 * phase, then re-calls the sidecar with the SAME wire seed (carried in {@code G.aiSeed}) and the
 * gamestate at the same checkpoint. For the recovery to succeed, the sidecar's response to that
 * second call MUST be byte-identical to the first.
 *
 * <p>This test scopes the assertion to the sidecar's contract: given identical {@code (gamestate,
 * seed)} pairs, two independent {@code execute} invocations on fresh executor instances must
 * produce byte-identical wire responses. The bgio kill-and-restart scaffolding lives on the
 * map-room TS side and is out of scope here; the existing TS harness (see #2376 audit notes) covers
 * it.
 *
 * <p>Two flavours run side-by-side: PurchaseExecutor and NoncombatMoveExecutor. Each builds its own
 * {@link games.strategy.engine.data.GameData} clone and {@code ProAi} per call, so the only thing
 * carried over from one run to the next is what the wire seed and wire state encode — exactly the
 * recovery contract.
 */
class StatelessReplayDeterminismTest {

  private static final long SEED = 42L;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static CanonicalGameData canonical;

  @BeforeAll
  static void init() {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load();
  }

  /**
   * Purchase replay: same {@code (gamestate, seed)} → byte-identical wire response across two fresh
   * executor instances. Proves a freshly-spawned bot worker would receive an identical plan to the
   * bot it replaced after dispatching {@code undoAllMoves}.
   */
  @Test
  void purchasePlan_isReplayDeterministicAcrossFreshExecutors() throws Exception {
    final PurchaseRequest request =
        new PurchaseRequest(
            new WireState(List.of(), List.of(), 1, "purchase", "Germans", List.of()), SEED);

    final PurchasePlan first = new PurchaseExecutor().execute(canonical, request);
    final PurchasePlan second = new PurchaseExecutor().execute(canonical, request);

    final String firstWire = MAPPER.writeValueAsString(first);
    final String secondWire = MAPPER.writeValueAsString(second);

    assertEquals(
        firstWire,
        secondWire,
        () ->
            "stateless purchase must replay byte-identically. "
                + "If a bot worker died mid-purchase and a replacement bot dispatched"
                + " undoAllMoves and re-called the sidecar with the same seed, the response must"
                + " match the first call. Diverged here:\n  first:  "
                + firstWire
                + "\n  second: "
                + secondWire);
    // Sanity: plan is non-empty (Germans always buy something on round 1).
    assertTrue(first.buys().size() > 0, "Germans must buy on round 1 — empty plan is suspicious");
  }

  /**
   * NCM replay: same contract as purchase. Each NCM call constructs its own state from canonical +
   * wire seed, with no cross-call dependency on a prior purchase result.
   */
  @Test
  void noncombatMovePlan_isReplayDeterministicAcrossFreshExecutors() throws Exception {
    final NoncombatMoveRequest request =
        new NoncombatMoveRequest(
            new WireState(List.of(), List.of(), 1, "nonCombatMove", "Germans", List.of()), SEED);

    final String firstWire =
        MAPPER.writeValueAsString(new NoncombatMoveExecutor().execute(canonical, request));
    final String secondWire =
        MAPPER.writeValueAsString(new NoncombatMoveExecutor().execute(canonical, request));

    assertEquals(
        firstWire,
        secondWire,
        () ->
            "stateless NCM must replay byte-identically across fresh executors. Diverged:\n  "
                + "first:  "
                + firstWire
                + "\n  second: "
                + secondWire);
  }
}
