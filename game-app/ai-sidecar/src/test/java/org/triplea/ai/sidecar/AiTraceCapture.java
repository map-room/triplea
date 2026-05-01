package org.triplea.ai.sidecar;

import java.text.MessageFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Test utility for capturing {@link AiTraceLogger} emissions in unit tests.
 *
 * <p>System.Logger delegates to JUL by default in JDK; this attaches a {@link Handler} to the
 * underlying JUL logger named after {@link AiTraceLogger} so {@code LOG.log(...)} calls land here
 * as {@link LogRecord}s the test can assert on.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * final AiTraceCapture cap = AiTraceCapture.attach();
 * try {
 *   AiTraceLogger.logRetreatDecision(...);
 * } finally {
 *   cap.detach();
 * }
 * assertThat(cap.formatted()).hasSize(1).first().asString()
 *     .startsWith("[AI-TRACE] matchID=...");
 * }</pre>
 *
 * <p>{@link #attach()} captures the JUL logger's prior {@link Level} and {@code useParentHandlers}
 * flag; {@link #detach()} restores both. Without restoration the handler-removal still leaks the
 * raised log level into subsequent tests in the same JVM (#2103 felix-review nit).
 */
public final class AiTraceCapture {

  private final Logger jul;
  private final Level originalLevel;
  private final boolean originalUseParent;
  private final CapturingHandler handler;

  private AiTraceCapture(
      final Logger jul,
      final Level originalLevel,
      final boolean originalUseParent,
      final CapturingHandler handler) {
    this.jul = jul;
    this.originalLevel = originalLevel;
    this.originalUseParent = originalUseParent;
    this.handler = handler;
  }

  /** Attach a fresh capture to the AiTraceLogger backend. Pair with {@link #detach()}. */
  public static AiTraceCapture attach() {
    final Logger jul = Logger.getLogger(AiTraceLogger.class.getName());
    final Level originalLevel = jul.getLevel();
    final boolean originalUseParent = jul.getUseParentHandlers();
    final CapturingHandler h = new CapturingHandler();
    h.setLevel(Level.ALL);
    jul.setLevel(Level.ALL);
    jul.addHandler(h);
    jul.setUseParentHandlers(false);
    return new AiTraceCapture(jul, originalLevel, originalUseParent, h);
  }

  /** Restore the JUL logger to its prior state. Always call from a finally block. */
  public void detach() {
    jul.removeHandler(handler);
    jul.setLevel(originalLevel);
    jul.setUseParentHandlers(originalUseParent);
  }

  /** Captured records, formatted via {@link MessageFormat} like the System.Logger backend would. */
  public List<String> formatted() {
    return handler.records.stream()
        .map(
            r ->
                r.getParameters() == null
                    ? r.getMessage()
                    : MessageFormat.format(r.getMessage(), r.getParameters()))
        .toList();
  }

  private static final class CapturingHandler extends Handler {
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();

    @Override
    public void publish(final LogRecord record) {
      records.add(record);
    }

    @Override
    public void flush() {}

    @Override
    public void close() throws SecurityException {}
  }
}
