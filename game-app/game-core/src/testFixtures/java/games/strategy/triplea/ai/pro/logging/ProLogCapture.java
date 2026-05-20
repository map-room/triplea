package games.strategy.triplea.ai.pro.logging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Test helper that redirects ProAI planner log output to an in-memory list and {@code System.out}.
 *
 * <p>Use try-with-resources to ensure the external handler is always restored:
 *
 * <pre>{@code
 * try (ProLogCapture log = new ProLogCapture()) {
 *     proAi.invokeCombatMoveForSidecar(moveDelegate, data, player);
 *     log.printAll();   // dump to stdout while debugging; remove before committing
 * }
 *
 * // Or assert on specific planner decisions:
 * try (ProLogCapture log = new ProLogCapture()) {
 *     proAi.invokeCombatMoveForSidecar(moveDelegate, data, player);
 *     assertThat(log.getLines()).anyMatch(l -> l.contains("island-power mode: true"));
 * }
 * }</pre>
 *
 * <p>Restores the prior external handler on close so nested captures work correctly.
 */
public final class ProLogCapture implements AutoCloseable {

  private final List<String> lines = new ArrayList<>();
  private final Consumer<String> previousHandler;
  private final ProLogSettings settings;

  public ProLogCapture() {
    previousHandler = ProLogUi.getExternalHandler();
    settings = ProLogSettings.loadSettings();
    settings.setLogEnabled(true);
    settings.setLogLevel(java.util.logging.Level.FINEST);
    ProLogUi.setExternalHandler(
        msg -> {
          synchronized (lines) {
            lines.add(msg);
          }
          System.out.println("[ProAI] " + msg);
        });
  }

  /** Returns a snapshot of all captured log lines at the time of the call. */
  public List<String> getLines() {
    synchronized (lines) {
      return Collections.unmodifiableList(new ArrayList<>(lines));
    }
  }

  /** Prints all captured lines to {@code System.out}. Useful during debugging. */
  public void printAll() {
    synchronized (lines) {
      lines.forEach(l -> System.out.println("[ProAI] " + l));
    }
  }

  /** Restores the previous external handler. */
  @Override
  public void close() {
    ProLogUi.setExternalHandler(previousHandler);
  }
}
