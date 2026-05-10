package org.triplea.ai.sidecar.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.strategy.triplea.settings.ClientSetting;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.SidecarConfig;

class HttpServiceTest {

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
  }

  @Test
  void healthEndpointReturns200OverHttp() throws Exception {
    final SidecarConfig cfg =
        new SidecarConfig("127.0.0.1", 0, 2, "test-token", "data/sessions", null);
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

  @Test
  void decisionEndpointRequiresAuth() throws Exception {
    final SidecarConfig cfg =
        new SidecarConfig("127.0.0.1", 0, 2, "test-token", "data/sessions", null);
    final HttpService svc = HttpService.start(cfg, CanonicalGameData.load());
    try {
      final int port = svc.boundPort();
      final HttpClient client =
          HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
      final HttpResponse<String> unauth =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/decision"))
                  .POST(HttpRequest.BodyPublishers.ofString("{}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(401, unauth.statusCode());
    } finally {
      svc.stop();
    }
  }

  @Test
  void deletedSessionsEndpointReturns404() throws Exception {
    final SidecarConfig cfg =
        new SidecarConfig("127.0.0.1", 0, 2, "test-token", "data/sessions", null);
    final HttpService svc = HttpService.start(cfg, CanonicalGameData.load());
    try {
      final int port = svc.boundPort();
      final HttpClient client =
          HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
      // The /sessions and /session/{id}/* routes are gone in #2386. Even with auth, the request
      // should not match any handler.
      final HttpResponse<String> resp =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/sessions"))
                  .header("Authorization", "Bearer test-token")
                  .POST(HttpRequest.BodyPublishers.ofString("{}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(404, resp.statusCode());
    } finally {
      svc.stop();
    }
  }
}
