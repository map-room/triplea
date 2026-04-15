package org.triplea.ai.sidecar.exec;

import games.strategy.engine.data.GameData;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.settings.ClientSetting;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.dto.ScramblePlan;
import org.triplea.ai.sidecar.dto.ScrambleRequest;
import org.triplea.ai.sidecar.session.Session;
import org.triplea.ai.sidecar.session.SessionKey;
import org.triplea.ai.sidecar.wire.WireState;
import org.triplea.ai.sidecar.wire.WireTerritory;
import org.triplea.ai.sidecar.wire.WireUnit;

/**
 * Latency benchmark for {@link ScrambleExecutor}.
 *
 * <p>Measures the honest cold path (fresh {@link Session} + fresh {@link ProAi} + first
 * {@code execute()} call) and the warm path (same session, repeated calls) and reports p50/p95
 * for each to stdout. Gated by {@code spec §5} at <b>500 ms cold p95</b>; the spec asks us to
 * report a violation rather than bump the budget.
 *
 * <p>Tagged {@code benchmark} so CI can exclude via {@code excludeTags} if we ever add such a
 * filter; by default it runs as part of the normal test task, so the numbers land in every
 * green build log.
 */
@Tag("benchmark")
class ScrambleExecutorBenchmark {

  private static CanonicalGameData canonical;

  private static final int COLD_RUNS = 3;
  private static final int WARM_RUNS = 10;
  private static final long COLD_P95_BUDGET_NANOS = 500L * 1_000_000L; // 500 ms

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load();
  }

  @Test
  void coldAndWarmLatencyReport() {
    // --- Cold runs: fresh session every iteration. ----------------------------------------
    final long[] coldNanos = new long[COLD_RUNS];
    for (int i = 0; i < COLD_RUNS; i++) {
      final Session session = freshSession();
      final ScrambleRequest req = buildRequest("cold-" + i);
      final long t0 = System.nanoTime();
      final ScramblePlan plan = new ScrambleExecutor().execute(session, req);
      coldNanos[i] = System.nanoTime() - t0;
      if (plan == null) {
        throw new AssertionError("cold run " + i + " returned null plan");
      }
    }

    // --- Warm runs: one shared session, repeated calls with fresh (but same-shape) ids. ---
    final long[] warmNanos = new long[WARM_RUNS];
    final Session warmSession = freshSession();
    // First call warms the ProAi / ProData path; do NOT count it toward warm numbers.
    new ScrambleExecutor().execute(warmSession, buildRequest("warm-prime"));
    for (int i = 0; i < WARM_RUNS; i++) {
      final ScrambleRequest req = buildRequest("warm-" + i);
      final long t0 = System.nanoTime();
      final ScramblePlan plan = new ScrambleExecutor().execute(warmSession, req);
      warmNanos[i] = System.nanoTime() - t0;
      if (plan == null) {
        throw new AssertionError("warm run " + i + " returned null plan");
      }
    }

    final long coldP50 = percentile(coldNanos, 50);
    final long coldP95 = percentile(coldNanos, 95);
    final long warmP50 = percentile(warmNanos, 50);
    final long warmP95 = percentile(warmNanos, 95);

    // Emit to stdout so the numbers show up in gradle test logs.
    System.out.println("[scramble-bench] cold p50=" + fmt(coldP50) + " p95=" + fmt(coldP95));
    System.out.println("[scramble-bench] warm p50=" + fmt(warmP50) + " p95=" + fmt(warmP95));
    System.out.println("[scramble-bench] cold raw (ns): " + Arrays.toString(coldNanos));
    System.out.println("[scramble-bench] warm raw (ns): " + Arrays.toString(warmNanos));

    if (coldP95 > COLD_P95_BUDGET_NANOS) {
      System.out.println(
          "[scramble-bench] WARNING: cold p95 "
              + fmt(coldP95)
              + " exceeds spec §5 500ms defensive budget — needs human decision before Task 25");
    }
    // Per the task contract we do not fail the test on a budget violation — we report and let
    // the implementer escalate. The regular functional tests still gate the commit.
  }

  private Session freshSession() {
    final GameData data = canonical.cloneForSession();
    final ProAi proAi = new ProAi("bench-Germans", "Germans");
    return new Session(
        "s-bench-" + UUID.randomUUID(),
        new SessionKey("g-bench", "Germans"),
        42L,
        proAi,
        data,
        new ConcurrentHashMap<>(),
        Executors.newSingleThreadExecutor());
  }

  private static ScrambleRequest buildRequest(final String tag) {
    final WireState wire =
        new WireState(
            List.of(
                new WireTerritory(
                    "112 Sea Zone",
                    "Germans",
                    List.of(new WireUnit("u-dd-" + tag, "destroyer", 0, 0))),
                new WireTerritory(
                    "Western Germany",
                    "Germans",
                    List.of(
                        new WireUnit("u-airfield-" + tag, "airfield", 0, 0),
                        new WireUnit("u-ftr1-" + tag, "fighter", 0, 0),
                        new WireUnit("u-ftr2-" + tag, "fighter", 0, 0)))),
            List.of(),
            1,
            "combat",
            "Germans");
    return new ScrambleRequest(
        wire,
        new ScrambleRequest.ScrambleBattle(
            "112 Sea Zone",
            Map.of(
                "Western Germany",
                new ScrambleRequest.ScrambleSource(
                    2,
                    List.of(
                        new WireUnit("u-ftr1-" + tag, "fighter", 0, 0),
                        new WireUnit("u-ftr2-" + tag, "fighter", 0, 0))))));
  }

  private static long percentile(final long[] samples, final int p) {
    final long[] copy = samples.clone();
    Arrays.sort(copy);
    if (copy.length == 0) {
      return 0;
    }
    final int idx = (int) Math.ceil(p / 100.0 * copy.length) - 1;
    return copy[Math.max(0, Math.min(copy.length - 1, idx))];
  }

  private static String fmt(final long nanos) {
    return String.format("%.2fms", nanos / 1_000_000.0);
  }
}
