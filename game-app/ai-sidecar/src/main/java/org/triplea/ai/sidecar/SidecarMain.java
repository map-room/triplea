package org.triplea.ai.sidecar;

import games.strategy.triplea.ai.pro.logging.ProLogSettings;
import games.strategy.triplea.ai.pro.logging.ProLogUi;
import games.strategy.triplea.settings.ClientSetting;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import org.triplea.ai.sidecar.http.HttpService;
import org.triplea.ai.sidecar.session.SessionReaper;
import org.triplea.ai.sidecar.session.SessionRegistry;

public final class SidecarMain {
  private SidecarMain() {}

  public static void main(final String[] args) throws IOException, InterruptedException {
    initializeLogging();
    ClientSetting.initialize();

    final SidecarConfig cfg = SidecarConfig.fromEnv(System.getenv());
    final CanonicalGameData canonical = CanonicalGameData.load();
    final Path dataDir = Path.of(cfg.dataDir());
    final SessionRegistry registry = new SessionRegistry(canonical, dataDir);

    // Restore sessions persisted in a previous run.
    registry.rehydrate();

    final HttpService svc = HttpService.start(cfg, registry);
    System.out.printf(
        "[ai-sidecar] listening on %s:%d%n", cfg.bindHost(), svc.boundPort());

    // Start reaper — runs every 5 minutes, cleans stale sessions (>30 days).
    final SessionReaper reaper = new SessionReaper(registry, Clock.systemUTC(), cfg.serverUrl());
    reaper.start();

    final CountDownLatch latch = new CountDownLatch(1);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  reaper.stop();
                  svc.stop();
                  latch.countDown();
                }));
    latch.await();
  }

  /**
   * Test hook — starts the sidecar with the given env overrides.
   * Uses SIDECAR_DATA_DIR from env to point to a temp dir so tests are isolated.
   */
  static HttpService startForTest(final Map<String, String> env) throws IOException {
    ClientSetting.initialize();
    final SidecarConfig cfg = SidecarConfig.fromEnv(env);
    final CanonicalGameData canonical = CanonicalGameData.load();
    final Path dataDir = Path.of(cfg.dataDir());
    final SessionRegistry registry = new SessionRegistry(canonical, dataDir);
    registry.rehydrate();
    return HttpService.start(cfg, registry);
  }

  private static void initializeLogging() {
    // Load baseline config from classpath resource first.
    try (final var is = SidecarMain.class.getResourceAsStream("/logging.properties")) {
      if (is != null) {
        LogManager.getLogManager().readConfiguration(is);
      }
    } catch (final IOException e) {
      System.err.println("Failed to load sidecar logging.properties: " + e.getMessage());
    }

    // Apply SIDECAR_LOG_LEVEL env var override on top of the baseline.
    final Level julLevel = parseJulLevel(System.getenv("SIDECAR_LOG_LEVEL"));
    final Logger root = Logger.getLogger("");
    root.setLevel(julLevel);
    for (final var handler : root.getHandlers()) {
      handler.setLevel(julLevel);
    }

    // Route ProLogger messages (game-core ProAi debug output) to stdout via System.Logger.
    final System.Logger proLogger = System.getLogger("ProAi");
    ProLogUi.setExternalHandler(
        message -> proLogger.log(System.Logger.Level.INFO, "[ProAI] {0}", message));

    // Mirror the level into ProLogSettings so ProLogger's own depth filter matches.
    ProLogSettings.loadSettings().setLogLevel(julLevel);

    System.getLogger(SidecarMain.class.getName())
        .log(
            System.Logger.Level.INFO,
            "Sidecar logging initialized: level={0}",
            julLevel.getName());
  }

  static Level parseJulLevel(final String envValue) {
    if (envValue == null || envValue.isBlank()) {
      return Level.INFO;
    }
    try {
      return Level.parse(envValue.toUpperCase(Locale.ROOT));
    } catch (final IllegalArgumentException e) {
      System.err.println(
          "SIDECAR_LOG_LEVEL="
              + envValue
              + " is not a valid java.util.logging level; "
              + "defaulting to INFO. Valid: SEVERE, WARNING, INFO, CONFIG, FINE, FINER, FINEST.");
      return Level.INFO;
    }
  }
}
