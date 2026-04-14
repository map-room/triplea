package org.triplea.ai.sidecar;

import games.strategy.triplea.settings.ClientSetting;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.triplea.ai.sidecar.http.HttpService;
import org.triplea.ai.sidecar.session.SessionRegistry;

public final class SidecarMain {
  private SidecarMain() {}

  public static void main(final String[] args) throws IOException, InterruptedException {
    ClientSetting.initialize();

    final SidecarConfig cfg = SidecarConfig.fromEnv(System.getenv());
    final CanonicalGameData canonical = CanonicalGameData.load();
    final SessionRegistry registry = new SessionRegistry(canonical);

    final HttpService svc = HttpService.start(cfg, registry);
    System.out.printf(
        "[ai-sidecar] listening on %s:%d%n", cfg.bindHost(), svc.boundPort());

    final CountDownLatch latch = new CountDownLatch(1);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  svc.stop();
                  latch.countDown();
                }));
    latch.await();
  }

  // Test hook — lets the integration test call the same wiring as main() without blocking.
  static HttpService startForTest(final Map<String, String> env) throws IOException {
    ClientSetting.initialize();
    final SidecarConfig cfg = SidecarConfig.fromEnv(env);
    final CanonicalGameData canonical = CanonicalGameData.load();
    return HttpService.start(cfg, new SessionRegistry(canonical));
  }
}
