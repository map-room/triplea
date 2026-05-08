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

/**
 * Integration test: sessions persist to disk and rehydrate after a sidecar "restart" (creating a
 * new HttpService instance with the same data directory).
 */
class SidecarPersistenceIntegrationTest {

  @TempDir Path dataDir;

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
  }

  @Test
  void sessionSurvivesRestart() throws Exception {
    final Map<String, String> env =
        Map.of(
            "SIDECAR_BIND_HOST", "127.0.0.1",
            "SIDECAR_PORT", "0",
            "SIDECAR_DATA_DIR", dataDir.toString());
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    // --- First sidecar instance ---
    final HttpService svc1 = SidecarMain.startForTest(env);
    final int port1 = svc1.boundPort();
    try {
      // Create session
      final HttpResponse<String> create =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port1 + "/sessions"))
                  .header("Authorization", "Bearer dev-token")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          "{\"sessionId\":\"m1:Germans:r1\",\"gameId\":\"m1\",\"nation\":\"Germans\",\"round\":1,\"seed\":42}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, create.statusCode());
      assertTrue(create.body().contains("\"created\":true"));
    } finally {
      svc1.stop();
    }

    // --- Second sidecar instance (same data dir = simulated restart) ---
    final HttpService svc2 = SidecarMain.startForTest(env);
    final int port2 = svc2.boundPort();
    try {
      // Re-open session — should return created=false (rehydrated from disk)
      final HttpResponse<String> reopen =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port2 + "/sessions"))
                  .header("Authorization", "Bearer dev-token")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          "{\"sessionId\":\"m1:Germans:r1\",\"gameId\":\"m1\",\"nation\":\"Germans\",\"round\":1,\"seed\":42}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, reopen.statusCode());
      assertTrue(
          reopen.body().contains("\"created\":false"),
          "expected created=false after restart, got: " + reopen.body());
    } finally {
      svc2.stop();
    }
  }
}
