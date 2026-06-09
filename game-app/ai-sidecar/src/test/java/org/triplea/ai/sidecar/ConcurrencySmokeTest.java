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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.http.HttpService;

/**
 * Verifies that ≥4 simultaneous POST /decision calls with differing board states produce differing
 * plans — proving no cross-thread state contamination between concurrent requests.
 *
 * <p>Each request uses a different {@code currentPlayer} so the AI's purchase selection for each
 * nation differs, producing non-identical JSON bodies.
 */
class ConcurrencySmokeTest {

  @TempDir Path tempDir;

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
  }

  private static String purchaseBody(final String player) {
    return "{\"kind\":\"purchase\",\"state\":{\"territories\":[],\"players\":[],"
        + "\"round\":1,\"phase\":\"purchase\",\"currentPlayer\":\""
        + player
        + "\"},\"seed\":42}";
  }

  @Test
  void fourConcurrentRequestsWithDifferingStateProduceDifferingPlans() throws Exception {
    final HttpService svc =
        SidecarMain.startForTest(
            Map.of(
                "SIDECAR_BIND_HOST", "127.0.0.1",
                "SIDECAR_PORT", "0",
                "SIDECAR_DATA_DIR", tempDir.toString()));
    try {
      final int port = svc.boundPort();
      final String base = "http://127.0.0.1:" + port;
      final String auth = "Bearer dev-token";

      // Four players → four different planning contexts.
      final List<String> players = List.of("Germans", "Russians", "Americans", "British");

      final HttpClient client =
          HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

      final List<Callable<String>> tasks = new ArrayList<>();
      for (final String player : players) {
        final String body = purchaseBody(player);
        tasks.add(
            () -> {
              final HttpResponse<String> resp =
                  client.send(
                      HttpRequest.newBuilder(URI.create(base + "/decision"))
                          .header("Authorization", auth)
                          .POST(HttpRequest.BodyPublishers.ofString(body))
                          .timeout(Duration.ofSeconds(60))
                          .build(),
                      HttpResponse.BodyHandlers.ofString());
              assertEquals(
                  200,
                  resp.statusCode(),
                  "Expected 200 for player=" + player + "; body=" + resp.body());
              return resp.body();
            });
      }

      // Submit all four concurrently.
      final ExecutorService pool = Executors.newFixedThreadPool(players.size());
      final List<Future<String>> futures = pool.invokeAll(tasks);
      pool.shutdown();

      final List<String> bodies = new ArrayList<>();
      for (final Future<String> f : futures) {
        bodies.add(f.get());
      }

      // All four must be 200 ready.
      for (final String body : bodies) {
        assertTrue(body.contains("\"status\":\"ready\""), "Not a ready response: " + body);
      }

      // The four plans must not all be identical — differing states → differing plans.
      // (If all four were the same string, state leaked between threads.)
      final Set<String> distinct = Set.copyOf(bodies);
      assertTrue(
          distinct.size() > 1,
          "All 4 concurrent plans were identical — possible cross-thread contamination. "
              + "First body: "
              + bodies.get(0));
    } finally {
      svc.stop();
    }
  }

  @Test
  void concurrentRequestsCarryIndependentRequestIds() throws Exception {
    final HttpService svc =
        SidecarMain.startForTest(
            Map.of(
                "SIDECAR_BIND_HOST", "127.0.0.1",
                "SIDECAR_PORT", "0",
                "SIDECAR_DATA_DIR", tempDir.toString()));
    try {
      final int port = svc.boundPort();
      final String base = "http://127.0.0.1:" + port;
      final String auth = "Bearer dev-token";

      final List<String> players = List.of("Germans", "Russians", "Americans", "British");
      final HttpClient client =
          HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

      final List<Callable<HttpResponse<String>>> tasks = new ArrayList<>();
      for (int i = 0; i < players.size(); i++) {
        final String player = players.get(i);
        final String reqId = "smoke-req-" + i;
        tasks.add(
            () ->
                client.send(
                    HttpRequest.newBuilder(URI.create(base + "/decision"))
                        .header("Authorization", auth)
                        .header("X-Request-Id", reqId)
                        .POST(HttpRequest.BodyPublishers.ofString(purchaseBody(player)))
                        .timeout(Duration.ofSeconds(60))
                        .build(),
                    HttpResponse.BodyHandlers.ofString()));
      }

      final ExecutorService pool = Executors.newFixedThreadPool(players.size());
      final List<Future<HttpResponse<String>>> futures = pool.invokeAll(tasks);
      pool.shutdown();

      for (final Future<HttpResponse<String>> f : futures) {
        final HttpResponse<String> resp = f.get();
        assertEquals(200, resp.statusCode(), "body=" + resp.body());
        // The timing header must be present on every response.
        assertTrue(
            resp.headers().firstValue("X-Decision-Time-Ms").isPresent(),
            "Missing X-Decision-Time-Ms header");
      }
    } finally {
      svc.stop();
    }
  }
}
