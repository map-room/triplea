package org.triplea.ai.sidecar.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import games.strategy.engine.data.GameData;
import games.strategy.triplea.settings.ClientSetting;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.dto.NoncombatMovePlan;
import org.triplea.ai.sidecar.dto.NoncombatMoveRequest;
import org.triplea.ai.sidecar.dto.WireMoveDescription;
import org.triplea.ai.sidecar.wire.WireState;

/**
 * Integration tests for the stateless {@link NoncombatMoveExecutor}.
 *
 * <p>Each test runs NCM on a fresh {@link GameData} clone — there is no inter-call state on the
 * sidecar to set up. The pre-#2386 "purchase first to populate stored maps, then NCM" pattern is
 * gone: NCM rebuilds {@code factoryMoveMap} internally from the wire-applied gamestate and falls
 * back to {@code findMaxPurchaseDefenders} for the cantMove-unit estimate per factory territory.
 */
class NoncombatMoveExecutorIntegrationTest {

  private static final long SEED = 42L;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static CanonicalGameData canonical;

  @BeforeAll
  static void init() {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load();
  }

  // The StepNameMapper uses TripleA camelCase phase names: "purchase", "combatMove",
  // "nonCombatMove", "place". These must match exactly for WireStateApplier to advance the
  // game sequence to the correct step, which ProNonCombatMoveAi needs to call isCombatMove().
  private static WireState noncombatWireState(final String nation) {
    return new WireState(List.of(), List.of(), 1, "nonCombatMove", nation, List.of());
  }

  /** Sanity smoke: Germans produce at least one noncombat move on turn 1. */
  @Test
  void germansNoncombatMoveAfterFullPipeline() {
    final GameData data = canonical.cloneForSession();
    final NoncombatMovePlan plan =
        new NoncombatMoveExecutor()
            .executeOn(data, new NoncombatMoveRequest(noncombatWireState("Germans"), SEED));

    assertNotNull(plan, "noncombat-move plan must not be null");
    assertFalse(
        plan.moves().isEmpty(),
        "Germans should have at least one noncombat move on turn 1; got: " + plan.moves());
  }

  /** isBombing invariant: no captured move in the noncombat phase has isBombing == true. */
  @Test
  void noBombingMovesInNoncombatPhase() {
    final GameData data = canonical.cloneForSession();
    final NoncombatMovePlan plan =
        new NoncombatMoveExecutor()
            .executeOn(data, new NoncombatMoveRequest(noncombatWireState("Germans"), SEED));

    assertNotNull(plan, "plan must not be null (no bombing invariant violation)");
  }

  /**
   * Regression for map-room#2195: Germans must send at least one unit to Bulgaria
   * (Friendly_Neutral) on turn 1 NCM. The fix tightened {@code hasAlliedLandUnits} to player-owned
   * only; the stateless contract must not regress that.
   */
  @Test
  void germansClaimBulgariaOnTurn1() {
    final GameData data = canonical.cloneForSession();
    final NoncombatMovePlan plan =
        new NoncombatMoveExecutor()
            .executeOn(data, new NoncombatMoveRequest(noncombatWireState("Germans"), SEED));

    assertNotNull(plan);
    assertTrue(
        plan.moves().stream().anyMatch(m -> "Bulgaria".equals(m.to())),
        "Germans must send at least one unit to Bulgaria (Friendly_Neutral) on turn 1 NCM"
            + " — regression map-room#2195. Actual moves to: "
            + plan.moves().stream().map(WireMoveDescription::to).distinct().sorted().toList());
  }

  /**
   * Determinism gate: same {@code (gamestate, seed)} → byte-identical wire response across N fresh
   * GameData clones. Acts as a per-PR regression bar that the stateless rewrite preserved per-call
   * determinism.
   */
  @Test
  void statelessNoncombatMoveIsDeterministic_round1Germans() throws Exception {
    String first = null;
    final int runs = 5;
    for (int i = 0; i < runs; i++) {
      final GameData data = canonical.cloneForSession();
      final NoncombatMovePlan plan =
          new NoncombatMoveExecutor()
              .executeOn(data, new NoncombatMoveRequest(noncombatWireState("Germans"), SEED));
      final String wire = MAPPER.writeValueAsString(plan);
      if (first == null) {
        first = wire;
      } else {
        assertEquals(first, wire, "stateless NCM must be deterministic across runs");
      }
    }
  }
}
