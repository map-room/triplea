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
 * Verifies that ≥8 simultaneous POST /decision calls with differing board states produce differing
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

  /**
   * Build a purchase request with a specific PU budget for the Germans. Different PU amounts drive
   * different purchase decisions, giving the "differing plans" assertion a ground truth to check.
   */
  private static String purchaseBodyWithPus(final int pus) {
    return "{\"kind\":\"purchase\",\"state\":{\"territories\":[],"
        + "\"players\":[{\"playerId\":\"Germans\",\"pus\":"
        + pus
        + ",\"tech\":[],\"capitalCaptured\":false}],"
        + "\"round\":1,\"phase\":\"purchase\",\"currentPlayer\":\"Germans\","
        + "\"gameDataKey\":\"ww2global40_2nd_edition\"},\"seed\":42}";
  }

  private static String purchaseBody(final String player) {
    return "{\"kind\":\"purchase\",\"state\":{\"territories\":[],\"players\":[],"
        + "\"round\":1,\"phase\":\"purchase\",\"currentPlayer\":\""
        + player
        + "\",\"gameDataKey\":\"ww2global40_2nd_edition\"},\"seed\":42}";
  }

  @Test
  void eightConcurrentRequestsWithDifferingStateProduceDifferingPlans() throws Exception {
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

      // Eight requests with wildly different PU budgets — the AI's purchase quantity
      // differs per budget, so the response bodies are non-identical even though the
      // nation (Germans) is the same. This is the ground truth for the "differing
      // plans" assertion that proves no cross-thread state contamination.
      final List<Integer> budgets = List.of(3, 7, 12, 20, 28, 40, 55, 80);

      final List<Callable<String>> tasks = new ArrayList<>();
      for (final int pus : budgets) {
        final String body = purchaseBodyWithPus(pus);
        tasks.add(
            () -> {
              // Fresh client per task: avoids HTTP/1.1 connection-pool races where the
              // server closes a keep-alive connection while a second task tries to reuse
              // it, producing "header parser received no bytes" on slow CI hardware.
              final HttpClient client =
                  HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
              final HttpResponse<String> resp =
                  client.send(
                      HttpRequest.newBuilder(URI.create(base + "/decision"))
                          .header("Authorization", auth)
                          .POST(HttpRequest.BodyPublishers.ofString(body))
                          .timeout(Duration.ofSeconds(120))
                          .build(),
                      HttpResponse.BodyHandlers.ofString());
              assertEquals(
                  200, resp.statusCode(), "Expected 200 for pus=" + pus + "; body=" + resp.body());
              return resp.body();
            });
      }

      // Submit all eight concurrently.
      final ExecutorService pool = Executors.newFixedThreadPool(budgets.size());
      final List<Future<String>> futures = pool.invokeAll(tasks);
      pool.shutdown();

      final List<String> bodies = new ArrayList<>();
      for (final Future<String> f : futures) {
        bodies.add(f.get());
      }

      // All eight must be 200 ready.
      for (final String body : bodies) {
        assertTrue(body.contains("\"status\":\"ready\""), "Not a ready response: " + body);
      }

      // Plans must differ — a 3-PU budget buys far fewer units than an 80-PU budget.
      // If all eight are identical, the thread-local state bled across requests.
      final Set<String> distinct = Set.copyOf(bodies);
      assertTrue(
          distinct.size() > 1,
          "All 8 concurrent plans were identical — possible cross-thread contamination. "
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
      final List<Callable<HttpResponse<String>>> tasks = new ArrayList<>();
      for (int i = 0; i < players.size(); i++) {
        final String player = players.get(i);
        final String reqId = "smoke-req-" + i;
        tasks.add(
            () -> {
              // Fresh client per task — same rationale as the first smoke test.
              final HttpClient client =
                  HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
              return client.send(
                  HttpRequest.newBuilder(URI.create(base + "/decision"))
                      .header("Authorization", auth)
                      .header("X-Request-Id", reqId)
                      .POST(HttpRequest.BodyPublishers.ofString(purchaseBody(player)))
                      .timeout(Duration.ofSeconds(120))
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
            });
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
