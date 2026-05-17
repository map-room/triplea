package org.triplea.ai.sidecar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SidecarLogFormatter}.
 *
 * <p>Covers: field presence and order, {@code -} sentinel for unbound context, ISO-8601-ms-UTC
 * timestamp with exactly 3 fractional digits, level mapping, and exception appending.
 */
class SidecarLogFormatterTest {

  private final SidecarLogFormatter formatter = new SidecarLogFormatter();

  @AfterEach
  void clearContext() {
    AiTraceLogger.clearAll();
  }

  // -------------------------------------------------------------------------
  // Field ordering and sentinel
  // -------------------------------------------------------------------------

  @Test
  void format_noContext_allFieldsUseSentinel() {
    final LogRecord rec = record(Level.INFO, "hello");
    final String line = formatter.format(rec);

    assertThat(line).contains("match=- ");
    assertThat(line).contains("round=- ");
    assertThat(line).contains("player=- ");
    assertThat(line).contains(" hello");
    assertThat(line).endsWith("\n");
  }

  @Test
  void format_withContext_fieldsCarryRealValues() {
    AiTraceLogger.setMatchId("match-abc");
    AiTraceLogger.setRound(3);
    AiTraceLogger.setPlayer("Germans");

    final String line = formatter.format(record(Level.INFO, "msg"));

    assertThat(line).contains("match=match-abc ");
    assertThat(line).contains("round=3 ");
    assertThat(line).contains("player=Germans ");
  }

  @Test
  void format_fieldOrder_timestampLevelTagMatchRoundPlayerMessage() {
    AiTraceLogger.setMatchId("m1");
    AiTraceLogger.setRound(1);
    AiTraceLogger.setPlayer("Italians");
    final String line = formatter.format(record(Level.INFO, "payload"));

    // Verify ordering by checking index positions.
    final int tsIdx = line.indexOf("T"); // ISO timestamp starts with date "T" separator
    final int infoIdx = line.indexOf(" INFO ");
    final int tagIdx = line.indexOf(" [ai-sidecar] ");
    final int matchIdx = line.indexOf(" match=");
    final int roundIdx = line.indexOf(" round=");
    final int playerIdx = line.indexOf(" player=");
    final int msgIdx = line.indexOf(" payload");

    assertThat(tsIdx).isLessThan(infoIdx);
    assertThat(infoIdx).isLessThan(tagIdx);
    assertThat(tagIdx).isLessThan(matchIdx);
    assertThat(matchIdx).isLessThan(roundIdx);
    assertThat(roundIdx).isLessThan(playerIdx);
    assertThat(playerIdx).isLessThan(msgIdx);
  }

  // -------------------------------------------------------------------------
  // Timestamp format
  // -------------------------------------------------------------------------

  @Test
  void format_timestamp_hasExactlyThreeFractionalDigitsAndZSuffix() {
    // Use a known epoch millis (fractional part = .482)
    final LogRecord rec = new LogRecord(Level.INFO, "msg");
    rec.setInstant(Instant.parse("2026-05-17T14:03:21.482Z"));
    final String line = formatter.format(rec);

    assertThat(line).startsWith("2026-05-17T14:03:21.482Z ");
  }

  @Test
  void format_timestamp_zeroMillis_stillEmitsThreeFractionalDigits() {
    final LogRecord rec = new LogRecord(Level.INFO, "msg");
    rec.setInstant(Instant.parse("2026-05-17T14:03:21.000Z"));
    final String line = formatter.format(rec);

    // Instant.toString() would emit "2026-05-17T14:03:21Z" (no millis) — formatter must pad.
    assertThat(line).startsWith("2026-05-17T14:03:21.000Z ");
  }

  // -------------------------------------------------------------------------
  // Level mapping
  // -------------------------------------------------------------------------

  @Test
  void mapLevel_severe_mapsToError() {
    assertThat(SidecarLogFormatter.mapLevel(Level.SEVERE)).isEqualTo("ERROR");
  }

  @Test
  void mapLevel_warning_mapsToWarn() {
    assertThat(SidecarLogFormatter.mapLevel(Level.WARNING)).isEqualTo("WARN");
  }

  @Test
  void mapLevel_info_mapsToInfo() {
    assertThat(SidecarLogFormatter.mapLevel(Level.INFO)).isEqualTo("INFO");
  }

  @Test
  void mapLevel_fine_mapsToDebug() {
    assertThat(SidecarLogFormatter.mapLevel(Level.FINE)).isEqualTo("DEBUG");
  }

  @Test
  void mapLevel_finest_mapsToDebug() {
    assertThat(SidecarLogFormatter.mapLevel(Level.FINEST)).isEqualTo("DEBUG");
  }

  // -------------------------------------------------------------------------
  // Tag
  // -------------------------------------------------------------------------

  @Test
  void format_containsSidecarTag() {
    assertThat(formatter.format(record(Level.INFO, "x"))).contains("[ai-sidecar]");
  }

  // -------------------------------------------------------------------------
  // Exception handling
  // -------------------------------------------------------------------------

  @Test
  void format_thrownException_appendedOnSameLine() {
    final LogRecord rec = record(Level.SEVERE, "boom");
    rec.setThrown(new IllegalStateException("bad state"));
    final String line = formatter.format(rec);

    assertThat(line).contains("IllegalStateException");
    assertThat(line).contains("bad state");
    assertThat(line).endsWith("\n");
    // Single line — no embedded newlines before the trailing \n.
    assertThat(line.substring(0, line.length() - 1)).doesNotContain("\n");
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private static LogRecord record(final Level level, final String message) {
    return new LogRecord(level, message);
  }
}
