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
import org.triplea.ai.sidecar.session.SessionRegistry;

class SidecarMainIntegrationTest {

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
  }

  @Test
  void bootsEndToEnd() throws Exception {
    final SidecarConfig cfg = SidecarConfig.fromEnv(Map.of("SIDECAR_PORT", "0", "SIDECAR_BIND_HOST", "127.0.0.1"));
    final SessionRegistry registry = new SessionRegistry(CanonicalGameData.load());
    final HttpService svc = HttpService.start(cfg, registry);
    try {
      final int port = svc.boundPort();
      final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
      final HttpResponse<String> health =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health")).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, health.statusCode());

      final HttpResponse<String> create =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/sessions"))
                  .header("Authorization", "Bearer dev-token")
                  .POST(HttpRequest.BodyPublishers.ofString(
                      "{\"sessionId\":\"g-1:Germans\",\"gameId\":\"g-1\",\"nation\":\"Germans\",\"seed\":42}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, create.statusCode());
      assertTrue(create.body().contains("\"sessionId\":\"g-1:Germans\""));
    } finally {
      svc.stop();
    }
  }
}
