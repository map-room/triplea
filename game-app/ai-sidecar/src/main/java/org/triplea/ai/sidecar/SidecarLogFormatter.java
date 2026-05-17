package org.triplea.ai.sidecar;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * JUL {@link Formatter} emitting a single stdout line per record in the shared logging convention
 * (see map-room issue #2549):
 *
 * <pre>
 * {@code <ISO8601-ms-UTC> <LEVEL> [ai-sidecar] match=<id> round=<n> player=<nation> <message>}
 * </pre>
 *
 * <p>Reads per-thread context (match/round/player) from {@link AiTraceLogger}. Fields not bound on
 * the current thread emit the {@code -} sentinel so grep patterns like {@code grep
 * 'player=Germans'} never miss a line due to field absence.
 *
 * <p>JUL levels map to the fixed vocabulary: SEVERE→ERROR, WARNING→WARN, INFO→INFO, anything below
 * INFO→DEBUG.
 * <!-- TODO(#2549/#2551): verify exact format string byte-matches alpha's TS impl before merge -->
 */
public final class SidecarLogFormatter extends Formatter {

  static final String TAG = "[ai-sidecar]";

  // Always 3 fractional digits (yyyy-MM-dd'T'HH:mm:ss.SSS'Z') for lexical sort correctness.
  private static final DateTimeFormatter TS_FMT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  @Override
  public String format(final LogRecord record) {
    final String ts = TS_FMT.format(record.getInstant());
    final String level = mapLevel(record.getLevel());
    final String match = AiTraceLogger.currentMatchId();
    final String round = AiTraceLogger.currentRound();
    final String player = AiTraceLogger.currentPlayer();
    final String message = formatMessage(record);

    final StringBuilder sb = new StringBuilder(160);
    sb.append(ts)
        .append(' ')
        .append(level)
        .append(' ')
        .append(TAG)
        .append(' ')
        .append("match=")
        .append(match)
        .append(' ')
        .append("round=")
        .append(round)
        .append(' ')
        .append("player=")
        .append(player)
        .append(' ')
        .append(message);

    final Throwable thrown = record.getThrown();
    if (thrown != null) {
      sb.append(' ').append(thrown.getClass().getSimpleName());
      final String msg = thrown.getMessage();
      if (msg != null) {
        sb.append(": ").append(msg.replace('\n', ' ').replace('\r', ' '));
      }
    }
    sb.append('\n');
    return sb.toString();
  }

  static String mapLevel(final Level level) {
    if (level.intValue() >= Level.SEVERE.intValue()) {
      return "ERROR";
    }
    if (level.intValue() >= Level.WARNING.intValue()) {
      return "WARN";
    }
    if (level.intValue() >= Level.INFO.intValue()) {
      return "INFO";
    }
    return "DEBUG";
  }
}
