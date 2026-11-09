package games.strategy.triplea.ai.pro.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProLogUiTest {

  @AfterEach
  void clearHandlers() {
    ProLogUi.setExternalHandler(null);
    ProLogUi.setLogSink(null);
  }

  @Test
  void externalHandlerReceivesLogMessages() {
    final List<String> captured = new ArrayList<>();
    ProLogUi.setExternalHandler(captured::add);

    // ProLogger.info maps to Level.FINE; default ProLogSettings.logLevel is FINEST,
    // so FINE passes the depth filter and notifyAiLogMessage is called synchronously
    // via the external handler (no Swing indirection).
    ProLogger.info("test message");

    assertThat(captured).hasSize(1);
    assertThat(captured.get(0)).contains("test message");
  }

  @Test
  void externalHandlerReceivesDebugMessages() {
    final List<String> captured = new ArrayList<>();
    ProLogUi.setExternalHandler(captured::add);

    ProLogger.debug("debug detail");

    assertThat(captured).hasSize(1);
    assertThat(captured.get(0)).contains("debug detail");
  }

  @Test
  void clearingHandlerStopsCapture() {
    final List<String> captured = new ArrayList<>();
    ProLogUi.setExternalHandler(captured::add);
    ProLogUi.setExternalHandler(null);

    ProLogger.info("should not appear");

    assertThat(captured).isEmpty();
  }

  @Test
  void logSinkReceivesAiLogMessages() {
    final List<String> sink = new ArrayList<>();
    ProLogUi.setLogSink(sink::add);

    ProLogUi.notifyAiLogMessage("Starting combat move phase");

    assertThat(sink).containsExactly("Starting combat move phase");
  }

  @Test
  void clearingLogSinkStopsRoutingToIt() {
    final List<String> sink = new ArrayList<>();
    ProLogUi.setLogSink(sink::add);
    ProLogUi.setLogSink(null);

    ProLogUi.notifyAiLogMessage("Starting combat move phase");

    assertThat(sink).isEmpty();
  }
}
