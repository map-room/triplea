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

class SidecarIntegrationTest {
  private static final String UPDATE_BODY =
      "{\"state\":{\"territories\":[],\"players\":[],\"round\":1,\"phase\":\"purchase\","
          + "\"currentPlayer\":\"Germans\"}}";
  private static final String DECISION_BODY =
      "{\"kind\":\"purchase\",\"state\":{\"territories\":[],\"players\":[],\"round\":1,"
          + "\"phase\":\"purchase\",\"currentPlayer\":\"Germans\"}}";

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
  }

  @Test
  void fullLifecycleRoundTrip() throws Exception {
    final HttpService svc =
        SidecarMain.startForTest(Map.of("SIDECAR_BIND_HOST", "127.0.0.1", "SIDECAR_PORT", "0"));
    try {
      final int port = svc.boundPort();
      final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
      final String base = "http://127.0.0.1:" + port;
      final String auth = "Bearer dev-token";

      // 1. create
      final HttpResponse<String> create =
          client.send(
              HttpRequest.newBuilder(URI.create(base + "/session"))
                  .header("Authorization", auth)
                  .POST(HttpRequest.BodyPublishers.ofString(
                      "{\"gameId\":\"g-1\",\"nation\":\"Germans\",\"seed\":42}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, create.statusCode());
      final String sessionId = extractSessionId(create.body());

      // 2. update
      final HttpResponse<String> update =
          client.send(
              HttpRequest.newBuilder(URI.create(base + "/session/" + sessionId + "/update"))
                  .header("Authorization", auth)
                  .POST(HttpRequest.BodyPublishers.ofString(UPDATE_BODY))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(204, update.statusCode());

      // 3. decision → 501
      final HttpResponse<String> decision =
          client.send(
              HttpRequest.newBuilder(URI.create(base + "/session/" + sessionId + "/decision"))
                  .header("Authorization", auth)
                  .POST(HttpRequest.BodyPublishers.ofString(DECISION_BODY))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(501, decision.statusCode());
      assertTrue(decision.body().contains("not-implemented"));

      // 4. delete
      final HttpResponse<String> delete =
          client.send(
              HttpRequest.newBuilder(URI.create(base + "/session/" + sessionId))
                  .header("Authorization", auth)
                  .DELETE()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(204, delete.statusCode());

      // 5. subsequent update → 404
      final HttpResponse<String> post =
          client.send(
              HttpRequest.newBuilder(URI.create(base + "/session/" + sessionId + "/update"))
                  .header("Authorization", auth)
                  .POST(HttpRequest.BodyPublishers.ofString(UPDATE_BODY))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(404, post.statusCode());
    } finally {
      svc.stop();
    }
  }

  private static String extractSessionId(final String body) {
    final int start = body.indexOf("\"sessionId\":\"") + "\"sessionId\":\"".length();
    final int end = body.indexOf('"', start);
    return body.substring(start, end);
  }
}
