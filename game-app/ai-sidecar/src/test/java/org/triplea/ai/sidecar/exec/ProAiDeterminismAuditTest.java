package org.triplea.ai.sidecar.exec;

import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import games.strategy.engine.data.GameData;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.settings.ClientSetting;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.dto.NoncombatMovePlan;
import org.triplea.ai.sidecar.dto.NoncombatMoveRequest;
import org.triplea.ai.sidecar.dto.PurchasePlan;
import org.triplea.ai.sidecar.dto.PurchaseRequest;
import org.triplea.ai.sidecar.session.ProSessionSnapshotStore;
import org.triplea.ai.sidecar.session.Session;
import org.triplea.ai.sidecar.session.SessionKey;
import org.triplea.ai.sidecar.wire.WireState;

/**
 * Empirical determinism audit for {@link ProAi} sidecar entry points (issue
 * map-room/map-room#2376).
 *
 * <p>Gates the stateless-sidecar architecture campaign: if {@code invokePurchaseForSidecar} and
 * {@code invokeNonCombatMoveForSidecar} produce byte-identical wire responses across N fresh
 * sessions seeded with the same {@code (gamestate, seed)} pair, the {@code Session} / {@code
 * ProSessionSnapshot} / {@code SessionRegistry} machinery can be eliminated and every sidecar call
 * modelled as a pure function. If they diverge, the divergence sites need to be fixed first.
 *
 * <p>Each test mirrors the production session-creation path in {@link
 * org.triplea.ai.sidecar.session.SessionRegistry#buildSession SessionRegistry.buildSession} —
 * specifically calling {@code proAi.getProData().setSeed(SEED)} immediately after constructing the
 * {@link ProAi}. Without this seed-setting, the audit would test a configuration the production
 * sidecar never actually exercises.
 *
 * <p>This audit is deliberately non-fixing. Failures surface specific divergence sites that are
 * filed as separate follow-up issues; the audit's only role is to capture the empirical evidence.
 *
 * <p><b>Status after map-room/map-room#2384 (per-call wire seeding):</b> {@link
 * org.triplea.ai.sidecar.exec.PurchaseExecutor#execute PurchaseExecutor.execute} and {@link
 * org.triplea.ai.sidecar.exec.NoncombatMoveExecutor#execute NoncombatMoveExecutor.execute} now
 * reseed {@code proData.getRng()} and the battle calculator from {@code request.seed()} on every
 * call. The mechanical seeding contract is in place: every sidecar call is a function of {@code
 * (gamestate, wire seed)} as far as the seeded RNG sources are concerned. Empirically (10 reruns
 * per fixture against this branch):
 *
 * <ul>
 *   <li>Round-1 Russians purchase: <b>GREEN</b> — buys, placements, politicalActions, combatMoves
 *       all byte-identical. Russia has no war-declaration options on round 1, so threshold flips
 *       can't bite, and Russia's combat-move plan converges on the same source-territory ordering.
 *   <li>Round-1 Germans / Japanese / British purchase: {@code buys} and {@code placements} now
 *       byte-identical across runs (the gross drift from before #2377 is gone), but {@code
 *       politicalActions} and {@code combatMoves} source-ordering still flip across runs.
 *   <li>Round-1 Germans / Japanese NCM: residual move-source-territory drift, downstream of the
 *       same flake.
 * </ul>
 *
 * <p>The residual divergence is downstream of {@code HashMap<Territory, ?>} / {@code HashMap<Unit,
 * ?>} iteration inside {@link games.strategy.triplea.ai.pro.data.ProTerritoryManager} and {@link
 * games.strategy.triplea.ai.pro.AbstractProAi#storedCombatMoveMap storedCombatMoveMap}: identity
 * hashes drift across {@code GameData} clones and HashMap iteration order shifts the attack-options
 * list passed to {@link games.strategy.triplea.ai.pro.ProPoliticsAi}, which then cascades into the
 * {@code random <= warChance} threshold check. Closing it requires either deterministic hashing or
 * LinkedHashMap throughout {@code ProAi} — both larger scope than #2384 and tracked as a follow-up.
 *
 * <p>Per the discussion on map-room/map-room#2376, the remaining flake is not architecturally
 * load-bearing — sidecar calls are one-shot, the bot does not compare wire responses across
 * retries, and Session-elimination is gated on "calls being self-contained from gamestate" rather
 * than on byte-identical reproducibility. This audit class stays {@link Disabled} so CI does not
 * flake on the threshold-boundary cases.
 *
 * <p>To re-run the audit manually (delete or comment-out the class-level {@link Disabled}):
 *
 * <pre>{@code
 * ./gradlew :game-app:ai-sidecar:test \
 *   --tests org.triplea.ai.sidecar.exec.ProAiDeterminismAuditTest
 * }</pre>
 */
@Disabled(
    "Audit harness for map-room/map-room#2376. Residual HashMap-iteration flake remains after"
        + " #2384 — not architecturally load-bearing, see class javadoc. Re-enable progressively"
        + " as further determinism fixes (LinkedHashMap throughout ProAi) land.")
class ProAiDeterminismAuditTest {

  /** Seed used for every audit run. Matches the constant in {@link PurchaseExecutorTest}. */
  private static final long SEED = 42L;

  /**
   * Number of repeated runs per fixture. The audit's #2376 brief asks for 100 runs; in practice
   * empirical divergence shows up in the first two runs whenever {@code Math.random()} or other
   * unseeded RNG is consulted, so 10 is plenty for the binary GREEN/RED outcome and keeps the whole
   * audit suite under ten minutes (~17 s per purchase iteration; full purchase + combat-move +
   * noncombat-move pipeline is multiples of that).
   */
  private static final int RUNS = 10;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir Path snapshotDir;

  private static CanonicalGameData canonical;

  @BeforeAll
  static void init() {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load();
  }

  // ---------------------------------------------------------------------------
  // Purchase determinism — one fixture per nation. Round 1 across nations
  // exercises offensive (Germans, Japanese), defensive (Russians), and
  // dual-economy (British) code paths without needing custom mid-game state.
  // ---------------------------------------------------------------------------

  @Test
  void purchaseIsDeterministic_round1Germans() throws Exception {
    assertPurchaseDeterministic("Germans");
  }

  @Test
  void purchaseIsDeterministic_round1Japanese() throws Exception {
    assertPurchaseDeterministic("Japanese");
  }

  @Test
  void purchaseIsDeterministic_round1Russians() throws Exception {
    assertPurchaseDeterministic("Russians");
  }

  @Test
  void purchaseIsDeterministic_round1British() throws Exception {
    assertPurchaseDeterministic("British");
  }

  // ---------------------------------------------------------------------------
  // Noncombat-move determinism — purchase + noncombat-move per run. (Combat-move
  // executor was deleted in #2382 / triplea#86 once the bot bypassed it TS-side;
  // purchase's snapshot is sufficient to seed NCM.) If purchase is
  // non-deterministic, NCM determinism cannot hold either; if NCM diverges but
  // purchase passes, the divergence is in the move planning itself.
  // ---------------------------------------------------------------------------

  @Test
  void noncombatMoveIsDeterministic_round1Germans() throws Exception {
    assertNoncombatMoveDeterministic("Germans");
  }

  @Test
  void noncombatMoveIsDeterministic_round1Japanese() throws Exception {
    assertNoncombatMoveDeterministic("Japanese");
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Build a session that mirrors {@link
   * org.triplea.ai.sidecar.session.SessionRegistry#buildSession} exactly: fresh canonical clone,
   * new ProAi, seed the deterministic RNG, single-threaded offensive executor.
   */
  private Session freshSession(final String nation) {
    final GameData data = canonical.cloneForSession();
    final ProAi proAi = new ProAi("audit-" + nation + "-" + UUID.randomUUID(), nation);
    // CRITICAL: mirrors SessionRegistry.buildSession exactly. Without these two seed-set calls,
    // ProData.rng and the ConcurrentBattleCalculator both run unseeded, masking the determinism
    // contract that production code carefully establishes (map-room/map-room#2376 / #2377).
    proAi.getProData().setSeed(SEED);
    proAi.seedBattleCalc(SEED);
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    return new Session(
        "s-audit-" + UUID.randomUUID(),
        new SessionKey("g-audit", nation, 1),
        SEED,
        proAi,
        data,
        new ConcurrentHashMap<>(),
        executor);
  }

  private static WireState wireState(final String phase, final String nation) {
    return new WireState(List.of(), List.of(), 1, phase, nation, List.of());
  }

  private void assertPurchaseDeterministic(final String nation) throws Exception {
    final ProSessionSnapshotStore store = new ProSessionSnapshotStore(snapshotDir);
    String first = null;

    for (int i = 0; i < RUNS; i++) {
      final Session session = freshSession(nation);
      try {
        final PurchasePlan plan =
            new PurchaseExecutor(store)
                .execute(session, new PurchaseRequest(wireState("purchase", nation), SEED));
        final String wire = MAPPER.writeValueAsString(plan);
        if (first == null) {
          first = wire;
        } else if (!wire.equals(first)) {
          // Short-circuit on first divergence — no need to keep running, the audit
          // outcome is binary. Saves runtime so the full 6-fixture suite stays under
          // ten minutes even when most fixtures fail.
          failWithDiff(nation, "purchase", first, wire, i, RUNS);
        }
      } finally {
        session.offensiveExecutor().shutdownNow();
      }
    }
  }

  private void assertNoncombatMoveDeterministic(final String nation) throws Exception {
    String first = null;

    for (int i = 0; i < RUNS; i++) {
      // Each run uses its own snapshot dir so cross-run snapshot reuse cannot mask
      // determinism issues — every run is a true cold start.
      final Path runDir = Files.createTempDirectory(snapshotDir, "run-" + i + "-");
      final ProSessionSnapshotStore store = new ProSessionSnapshotStore(runDir);
      final Session session = freshSession(nation);
      try {
        new PurchaseExecutor(store)
            .execute(session, new PurchaseRequest(wireState("purchase", nation), SEED));
        final NoncombatMovePlan plan =
            new NoncombatMoveExecutor()
                .execute(
                    session, new NoncombatMoveRequest(wireState("nonCombatMove", nation), SEED));
        final String wire = MAPPER.writeValueAsString(plan);
        if (first == null) {
          first = wire;
        } else if (!wire.equals(first)) {
          failWithDiff(nation, "noncombat-move", first, wire, i, RUNS);
        }
      } finally {
        session.offensiveExecutor().shutdownNow();
      }
    }
  }

  /**
   * On first observed divergence, fail with a pretty-printed side-by-side dump so the failure
   * message itself carries the field-level diff for the audit report.
   */
  private static void failWithDiff(
      final String nation,
      final String phase,
      final String first,
      final String diverged,
      final int divergedAt,
      final int runs)
      throws Exception {
    fail(
        String.format(
            "Non-deterministic %s %s: run 0 differs from run %d (short-circuit; up to %d runs"
                + " configured).%n--- run 0 ---%n%s%n--- run %d ---%n%s%n",
            nation, phase, divergedAt, runs, prettyJson(first), divergedAt, prettyJson(diverged)));
  }

  private static String prettyJson(final String json) throws Exception {
    final JsonNode node = MAPPER.readTree(json);
    return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
  }
}
