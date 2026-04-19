package games.strategy.triplea.ai.pro.logging;

import games.strategy.engine.framework.GameShutdownRegistry;
import games.strategy.triplea.ui.menubar.debug.AiPlayerDebugAction;
import games.strategy.triplea.ui.menubar.debug.AiPlayerDebugOption;
import games.strategy.ui.Util;
import java.awt.Frame;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import lombok.experimental.UtilityClass;
import org.triplea.swing.key.binding.KeyCode;

/** Class to manage log window display. */
@UtilityClass
public final class ProLogUi {
  private static ProLogWindow settingsWindow = null;
  private static String currentName = "";
  private static int currentRound = 0;
  /**
   * Optional external sink for AI log messages. Set by headless consumers (the AI sidecar) that
   * need to route ProLogger output to stdout / System.Logger instead of the Swing settings window.
   * When set, messages are delivered to this handler <em>in addition to</em> the settings window
   * (if any) — it never replaces the existing UI path.
   */
  private static Consumer<String> externalHandler = null;

  /** Set or clear the external log sink. Pass {@code null} to restore UI-only behavior. */
  public static void setExternalHandler(final Consumer<String> handler) {
    externalHandler = handler;
  }

  public static List<AiPlayerDebugOption> buildDebugOptions(final Frame frame) {
    Util.ensureOnEventDispatchThread();
    if (settingsWindow == null) {
      settingsWindow = new ProLogWindow(frame);
      GameShutdownRegistry.registerShutdownAction(ProLogUi::clearCachedInstances);
    }
    ProLogger.info("Initialized Hard AI");
    return List.of(
        AiPlayerDebugOption.builder()
            .title("Show Logs")
            .actionListener(ProLogUi::showSettingsWindow)
            .mnemonic(KeyCode.X.getInputEventCode())
            .build());
  }

  public static void clearCachedInstances() {
    if (settingsWindow != null) {
      settingsWindow.dispose();
    }
    settingsWindow = null;
  }

  public static void showSettingsWindow(AiPlayerDebugAction aiPlayerDebugAction) {
    if (settingsWindow == null) {
      return;
    }
    ProLogger.info("Showing Hard AI settings window");
    settingsWindow.setVisible(true);
  }

  static void notifyAiLogMessage(final String message) {
    final Consumer<String> handler = externalHandler;
    if (handler != null) {
      // Direct delivery — no Swing indirection. Safe to call from any thread.
      handler.accept(message);
    }
    SwingUtilities.invokeLater(
        () -> {
          if (settingsWindow != null) {
            settingsWindow.addMessage(message);
          }
        });
  }

  public static void notifyStartOfRound(final int round, final String name) {
    if (settingsWindow == null) {
      return;
    }
    if (round != currentRound || !name.equals(currentName)) {
      currentRound = round;
      currentName = name;
      settingsWindow.notifyNewRound(round, name);
    }
  }
}
