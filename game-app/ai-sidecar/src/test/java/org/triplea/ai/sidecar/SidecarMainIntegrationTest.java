package org.triplea.ai.sidecar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.strategy.triplea.settings.ClientSetting;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.http.HttpService;

class SidecarMainIntegrationTest {

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
  }

  @Test
  void bootsEndToEnd() throws Exception {
    final SidecarConfig cfg =
        SidecarConfig.fromEnv(Map.of("SIDECAR_PORT", "0", "SIDECAR_BIND_HOST", "127.0.0.1"));
    final HttpService svc = HttpService.start(cfg, CanonicalGameData.load());
    try {
      final int port = svc.boundPort();
      final HttpClient client =
          HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
      final HttpResponse<String> health =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, health.statusCode());
      assertTrue(health.body().contains("\"status\":\"ok\""));
    } finally {
      svc.stop();
    }
  }
}
