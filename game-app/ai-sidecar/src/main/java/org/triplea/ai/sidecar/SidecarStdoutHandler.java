package org.triplea.ai.sidecar;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.StreamHandler;

/**
 * JUL {@link StreamHandler} that writes to {@code System.out} and flushes after every record.
 *
 * <p>Configured in {@code logging.properties} as the sole sidecar handler so all JUL output goes to
 * one stream. Docker captures stdout in arrival order; flush-per-record prevents buffered writes
 * from arriving out of sequence relative to TS-side stdout lines.
 *
 * <p>Uses {@link SidecarLogFormatter} for the shared single-line format (#2549).
 */
public final class SidecarStdoutHandler extends StreamHandler {

  public SidecarStdoutHandler() {
    super(System.out, new SidecarLogFormatter());
    setLevel(Level.ALL);
  }

  @Override
  public synchronized void publish(final LogRecord record) {
    super.publish(record);
    flush();
  }

  @Override
  public synchronized void close() {
    flush();
    // Do not close System.out — it is shared JVM infrastructure.
  }
}
