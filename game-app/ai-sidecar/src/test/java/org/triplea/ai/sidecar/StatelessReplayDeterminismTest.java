package org.triplea.ai.sidecar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import games.strategy.triplea.settings.ClientSetting;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.dto.NoncombatMovePlan;
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
 * second call MUST be deterministic.
 *
 * <p>This test scopes the assertion to the sidecar's contract: given identical {@code (gamestate,
 * seed)} pairs, two independent {@code execute} invocations on fresh executor instances must
 * produce equal {@code buys}, {@code repairs}, and {@code placements}. The bgio kill-and-restart
 * scaffolding lives on the map-room TS side and is out of scope here.
 *
 * <h2>Scope of the determinism gate</h2>
 *
 * <p>This gate compares only the structurally-stable fields of {@link PurchasePlan}: {@code buys},
 * {@code repairs}, {@code placements}. The {@code politicalActions} and {@code combatMoves} fields
 * can vary across same-(gamestate, seed) runs because of the HashMap-iteration ordering flake at
 * the politics-threshold (documented in {@code ProAiDeterminismAuditTest} javadoc and tracked
 * separately at map-room#2376). Closing that gap requires LinkedHashMap throughout {@code
 * AbstractProAi}'s stored state and is out of scope for the Session-elimination work in #2386 — the
 * architecture is replay-safe, only the hash ordering is residually unstable. The
 * separately-tracked TS-side audit harness covers the byte-identical wire-shape assertion when the
 * LinkedHashMap migration lands.
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
   * Purchase replay: same {@code (gamestate, seed)} → same {@code buys / repairs / placements}
   * across two fresh executor instances. Proves a freshly-spawned bot worker would receive an
   * identical purchase plan to the bot it replaced after dispatching {@code undoAllMoves}.
   */
  @Test
  void purchasePlan_isReplayDeterministicAcrossFreshExecutors() throws Exception {
    final PurchaseRequest request =
        new PurchaseRequest(
            new WireState(List.of(), List.of(), 1, "purchase", "Germans", List.of()), SEED);

    final PurchasePlan first = new PurchaseExecutor().execute(canonical, request);
    final PurchasePlan second = new PurchaseExecutor().execute(canonical, request);

    final String firstBuys = MAPPER.writeValueAsString(first.buys());
    final String secondBuys = MAPPER.writeValueAsString(second.buys());
    assertEquals(firstBuys, secondBuys, "purchase buys must replay identically");

    final String firstRepairs = MAPPER.writeValueAsString(first.repairs());
    final String secondRepairs = MAPPER.writeValueAsString(second.repairs());
    assertEquals(firstRepairs, secondRepairs, "purchase repairs must replay identically");

    final String firstPlacements = MAPPER.writeValueAsString(first.placements());
    final String secondPlacements = MAPPER.writeValueAsString(second.placements());
    assertEquals(firstPlacements, secondPlacements, "purchase placements must replay identically");

    // Sanity: plan is non-empty (Germans always buy something on round 1).
    assertTrue(first.buys().size() > 0, "Germans must buy on round 1 — empty plan is suspicious");
  }

  /**
   * NCM replay: same contract as purchase. Each NCM call constructs its own state from canonical +
   * wire seed, with no cross-call dependency on a prior purchase result. NCM's wire response is
   * just {@code moves}, which is structurally stable across runs (no politics-threshold
   * dependency).
   */
  @Test
  void noncombatMovePlan_isReplayDeterministicAcrossFreshExecutors() throws Exception {
    final org.triplea.ai.sidecar.dto.NoncombatMoveRequest request =
        new org.triplea.ai.sidecar.dto.NoncombatMoveRequest(
            new WireState(List.of(), List.of(), 1, "nonCombatMove", "Germans", List.of()), SEED);

    final NoncombatMovePlan first = new NoncombatMoveExecutor().execute(canonical, request);
    final NoncombatMovePlan second = new NoncombatMoveExecutor().execute(canonical, request);

    final String firstWire = MAPPER.writeValueAsString(first);
    final String secondWire = MAPPER.writeValueAsString(second);

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
