package org.triplea.ai.sidecar;

import games.strategy.triplea.settings.ClientSetting;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.triplea.ai.sidecar.http.HttpService;
import org.triplea.ai.sidecar.session.SessionReaper;
import org.triplea.ai.sidecar.session.SessionRegistry;

public final class SidecarMain {
  private SidecarMain() {}

  public static void main(final String[] args) throws IOException, InterruptedException {
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
}
