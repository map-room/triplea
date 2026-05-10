package org.triplea.ai.sidecar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.strategy.triplea.settings.ClientSetting;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.http.HttpService;

class SidecarIntegrationTest {
  private static final String DECISION_BODY =
      "{\"kind\":\"purchase\",\"state\":{\"territories\":[],\"players\":[],\"round\":1,"
          + "\"phase\":\"purchase\",\"currentPlayer\":\"Germans\"},\"seed\":42}";

  @TempDir Path tempDir;

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
  }

  @Test
  void statelessDecisionRoundTrip() throws Exception {
    final HttpService svc =
        SidecarMain.startForTest(
            Map.of(
                "SIDECAR_BIND_HOST", "127.0.0.1",
                "SIDECAR_PORT", "0",
                "SIDECAR_DATA_DIR", tempDir.toString()));
    try {
      final int port = svc.boundPort();
      final HttpClient client =
          HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
      final String base = "http://127.0.0.1:" + port;
      final String auth = "Bearer dev-token";

      // Stateless: a single POST /decision with seed in the body produces the plan.
      final HttpResponse<String> decision =
          client.send(
              HttpRequest.newBuilder(URI.create(base + "/decision"))
                  .header("Authorization", auth)
                  .POST(HttpRequest.BodyPublishers.ofString(DECISION_BODY))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, decision.statusCode(), "body=" + decision.body());
      assertTrue(decision.body().contains("\"status\":\"ready\""));
      assertTrue(decision.body().contains("\"kind\":\"purchase\""));

      // Deleted /sessions and /session/{id}/* routes return 404 (no handler context registered).
      final HttpResponse<String> deletedSessions =
          client.send(
              HttpRequest.newBuilder(URI.create(base + "/sessions"))
                  .header("Authorization", auth)
                  .POST(HttpRequest.BodyPublishers.ofString("{}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(404, deletedSessions.statusCode());
    } finally {
      svc.stop();
    }
  }
}
