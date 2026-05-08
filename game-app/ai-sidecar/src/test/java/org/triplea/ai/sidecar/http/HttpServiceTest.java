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
import org.triplea.ai.sidecar.session.SessionRegistry;

class HttpServiceTest {

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
  }

  @Test
  void healthEndpointReturns200OverHttp() throws Exception {
    final SidecarConfig cfg =
        new SidecarConfig("127.0.0.1", 0, 2, "test-token", "data/sessions", null);
    final SessionRegistry reg = new SessionRegistry(CanonicalGameData.load());
    final HttpService svc = HttpService.start(cfg, reg);
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
  void sessionsEndpointRequiresAuth() throws Exception {
    final SidecarConfig cfg =
        new SidecarConfig("127.0.0.1", 0, 2, "test-token", "data/sessions", null);
    final SessionRegistry reg = new SessionRegistry(CanonicalGameData.load());
    final HttpService svc = HttpService.start(cfg, reg);
    try {
      final int port = svc.boundPort();
      final HttpClient client =
          HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
      final HttpResponse<String> unauth =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/sessions"))
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          "{\"sessionId\":\"g:Germans:r1\",\"gameId\":\"g\",\"nation\":\"Germans\",\"round\":1,\"seed\":1}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(401, unauth.statusCode());

      final HttpResponse<String> ok =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/sessions"))
                  .header("Authorization", "Bearer test-token")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          "{\"sessionId\":\"g:Germans:r1\",\"gameId\":\"g\",\"nation\":\"Germans\",\"round\":1,\"seed\":1}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, ok.statusCode());
      assertTrue(ok.body().contains("\"sessionId\":\"g:Germans:r1\""));
    } finally {
      svc.stop();
    }
  }
}
