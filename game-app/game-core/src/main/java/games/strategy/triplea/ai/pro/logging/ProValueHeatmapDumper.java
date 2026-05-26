package games.strategy.triplea.ai.pro.logging;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.ai.pro.ProData;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;

/**
 * Phase-0 instrumentation for the Strategic Value Field epic (map-room#2755).
 *
 * <p>When enabled, dumps the {@code Map<Territory, Double>} produced by {@link
 * games.strategy.triplea.ai.pro.util.ProTerritoryValueUtils#findTerritoryValues} (and its sea-only
 * sibling) to a JSON file under {@code $HOME/.maproom/ai-debug/}. The dump is a debugging aid — the
 * Map Room client overlay reads it and renders a heatmap. Disabled by default; enable by setting
 * env var {@code AI_DUMP_VALUES=1} or system property {@code ai.dump.values=1} (the property is for
 * tests so they can flip the gate without spawning a child JVM).
 *
 * <p>JSON shape:
 *
 * <pre>{@code
 * { "round": 3,
 *   "player": "Americans",
 *   "entrypoint": "combined" | "sea-only",
 *   "timestampMs": 1716732123456,
 *   "seq": 42,
 *   "territories": [
 *     { "name": "Eastern United States", "isWater": false, "value": 12.5 },
 *     ...
 *   ]
 * }
 * }</pre>
 *
 * <p>This class is intentionally self-contained: no Jackson, no DI, no sidecar deps. Future phases
 * will extend the schema (S(n) cache, blended vs baseline split) — keep the JSON loose so the
 * client overlay can ignore unknown fields.
 */
public final class ProValueHeatmapDumper {

  /** Output subdirectory under {@code $HOME}. */
  static final String OUTPUT_SUBDIR = ".maproom/ai-debug";

  private static final AtomicLong SEQ = new AtomicLong(0);

  private ProValueHeatmapDumper() {}

  /** Returns {@code true} when dumping is enabled via env var or system property. */
  public static boolean isEnabled() {
    return "1".equals(System.getenv("AI_DUMP_VALUES"))
        || "1".equals(System.getProperty("ai.dump.values"));
  }

  /**
   * Writes the value map to disk if enabled; no-op otherwise. Never throws — instrumentation must
   * not break a running game. Failures are logged via {@link ProLogger#warn} and swallowed.
   *
   * @param entrypoint short label identifying the {@code ProTerritoryValueUtils} entrypoint that
   *     produced this map ({@code "combined"} for {@code findTerritoryValues}, {@code "sea-only"}
   *     for {@code findSeaTerritoryValues}).
   */
  public static void dumpIfEnabled(
      final ProData proData,
      final GamePlayer player,
      final String entrypoint,
      final Map<Territory, Double> territoryValueMap) {
    dumpIfEnabledFromGameData(proData.getData(), player, entrypoint, territoryValueMap);
  }

  /**
   * Overload for callers that don't have a {@link ProData} on hand — notably {@link
   * games.strategy.triplea.ai.pro.util.ProTerritoryValueUtils#findSeaTerritoryValues}, whose
   * signature takes only {@link GamePlayer}.
   */
  public static void dumpIfEnabledFromGameData(
      final GameData data,
      final GamePlayer player,
      final String entrypoint,
      final Map<Territory, Double> territoryValueMap) {
    if (!isEnabled()) {
      return;
    }
    try {
      final Path outDir = resolveOutputDir();
      Files.createDirectories(outDir);
      final int round = data.getSequence().getRound();
      final long timestampMs = Instant.now().toEpochMilli();
      final long seq = SEQ.incrementAndGet();
      final String filename =
          String.format(
              "%d-%s-%s-%d-%05d.json",
              round, sanitize(player.getName()), entrypoint, timestampMs, seq);
      final Path outFile = outDir.resolve(filename);
      try (Writer w = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8)) {
        writeJson(w, round, player.getName(), entrypoint, timestampMs, seq, territoryValueMap);
      }
    } catch (final IOException | RuntimeException e) {
      ProLogger.warn("ProValueHeatmapDumper failed: " + e.getMessage());
    }
  }

  /** Resolves the output dir. Override via system property {@code ai.dump.values.dir} for tests. */
  static Path resolveOutputDir() {
    final String override = System.getProperty("ai.dump.values.dir");
    if (override != null && !override.isEmpty()) {
      return Paths.get(override);
    }
    final String home = System.getProperty("user.home");
    if (home == null || home.isEmpty()) {
      throw new UncheckedIOException(new IOException("user.home not set"));
    }
    return Paths.get(home, OUTPUT_SUBDIR);
  }

  /** Reset the monotonic sequence counter — for tests. */
  static void resetSeqForTests() {
    SEQ.set(0);
  }

  /** Strips characters that would break a filename. */
  private static String sanitize(final String s) {
    return s.replaceAll("[^A-Za-z0-9_-]", "_");
  }

  /**
   * Writes the JSON document. Hand-rolled to avoid pulling Jackson into game-core for a debug tool.
   * Sorts territories by name so diffs across dumps are readable.
   */
  private static void writeJson(
      final Writer w,
      final int round,
      final String playerName,
      final String entrypoint,
      final long timestampMs,
      final long seq,
      final Map<Territory, Double> territoryValueMap)
      throws IOException {
    final List<Map.Entry<Territory, Double>> entries =
        new ArrayList<>(territoryValueMap.entrySet());
    entries.sort((a, b) -> a.getKey().getName().compareTo(b.getKey().getName()));
    w.write("{\"round\":");
    w.write(Integer.toString(round));
    w.write(",\"player\":");
    writeJsonString(w, playerName);
    w.write(",\"entrypoint\":");
    writeJsonString(w, entrypoint);
    w.write(",\"timestampMs\":");
    w.write(Long.toString(timestampMs));
    w.write(",\"seq\":");
    w.write(Long.toString(seq));
    w.write(",\"territories\":[");
    boolean first = true;
    for (final Map.Entry<Territory, Double> e : entries) {
      if (!first) {
        w.write(",");
      }
      first = false;
      w.write("{\"name\":");
      writeJsonString(w, e.getKey().getName());
      w.write(",\"isWater\":");
      w.write(Boolean.toString(e.getKey().isWater()));
      w.write(",\"value\":");
      w.write(formatDouble(e.getValue()));
      w.write("}");
    }
    w.write("]}");
  }

  private static void writeJsonString(final Writer w, final @Nullable String s) throws IOException {
    if (s == null) {
      w.write("null");
      return;
    }
    w.write("\"");
    for (int i = 0; i < s.length(); i++) {
      final char c = s.charAt(i);
      switch (c) {
        case '"' -> w.write("\\\"");
        case '\\' -> w.write("\\\\");
        case '\n' -> w.write("\\n");
        case '\r' -> w.write("\\r");
        case '\t' -> w.write("\\t");
        default -> {
          if (c < 0x20) {
            w.write(String.format("\\u%04x", (int) c));
          } else {
            w.write(c);
          }
        }
      }
    }
    w.write("\"");
  }

  private static String formatDouble(final @Nullable Double d) {
    if (d == null || d.isNaN() || d.isInfinite()) {
      return "0";
    }
    return Double.toString(d);
  }
}
