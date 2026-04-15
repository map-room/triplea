package org.triplea.ai.sidecar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import games.strategy.triplea.settings.ClientSetting;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.http.HttpService;

/**
 * End-to-end integration test for Phase 3 purchase decisions.
 *
 * <p>Starts a real {@link HttpService} via {@link SidecarMain#startForTest} on port 0, creates a
 * Germans session, then fires two purchase decision requests in sequence (cold then warm) against
 * the real {@code PurchaseExecutor}/{@code ProAi} path. Timings are printed to stdout so they are
 * visible in the Gradle test report.
 *
 * <p>Asserts:
 * <ul>
 *   <li>{@code status == "ready"}
 *   <li>{@code plan.kind == "purchase"}
 *   <li>{@code plan.buys} is a non-empty array (Germans always have PUs to spend on R1)
 *   <li>{@code plan.repairs} is an array (may be empty — no factories damaged on R1)
 * </ul>
 */
class Phase3PurchaseIntegrationTest {

  private static HttpService svc;
  private static HttpClient client;
  private static String base;
  private static String auth;
  private static String sessionId;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Germans is the purchasing nation; seed 42 gives deterministic ProAi decisions. */
  private static final String NATION = "Germans";

  /** Minimal purchase-phase WireState: empty territories/players, round 1, phase=purchase. */
  private static final String PURCHASE_WIRE_STATE =
      "{"
          + "\"territories\":[],"
          + "\"players\":[],"
          + "\"round\":1,"
          + "\"phase\":\"purchase\","
          + "\"currentPlayer\":\""
          + NATION
          + "\""
          + "}";

  private static final String PURCHASE_DECISION_BODY =
      "{\"kind\":\"purchase\",\"state\":" + PURCHASE_WIRE_STATE + "}";

  @BeforeAll
  static void startServiceAndCreateSession() throws Exception {
    ClientSetting.setPreferences(new MemoryPreferences());

    svc =
        SidecarMain.startForTest(
            Map.of("SIDECAR_BIND_HOST", "127.0.0.1", "SIDECAR_PORT", "0"));
    final int port = svc.boundPort();
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    base = "http://127.0.0.1:" + port;
    auth = "Bearer dev-token";

    final HttpResponse<String> create =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/session"))
                .header("Authorization", auth)
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"gameId\":\"integration-purchase\",\"nation\":\""
                            + NATION
                            + "\",\"seed\":42}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, create.statusCode(), "Session create must return 200");
    sessionId = extractField(create.body(), "sessionId");
    assertNotNull(sessionId, "sessionId must be present in create response");
  }

  @AfterAll
  static void stopService() {
    if (svc != null) {
      svc.stop();
    }
  }

  // ---------------------------------------------------------------------------
  // Test 1: cold call — first invocation loads game data from canonical clone
  // ---------------------------------------------------------------------------

  @Test
  void purchase_coldCall_returns200WithNonEmptyBuys() throws Exception {
    final long start = System.nanoTime();
    final HttpResponse<String> resp = postDecision(PURCHASE_DECISION_BODY);
    final long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    System.out.println("[Phase3PurchaseIntegrationTest] cold call elapsed: " + elapsedMs + " ms");

    assertEquals(200, resp.statusCode(), "purchase must return 200; body=" + resp.body());

    final JsonNode envelope = MAPPER.readTree(resp.body());
    assertEquals(
        "ready", envelope.path("status").asText(), "envelope status must be 'ready'");

    final JsonNode plan = envelope.path("plan");
    assertEquals("purchase", plan.path("kind").asText(), "plan.kind must be 'purchase'");

    assertTrue(plan.path("buys").isArray(), "plan.buys must be an array");
    assertTrue(plan.path("repairs").isArray(), "plan.repairs must be an array");

    // Germans always have PUs to spend on round 1 — a completely empty buy list is unexpected.
    assertTrue(
        plan.path("buys").size() > 0,
        "Germans must buy at least one unit on round 1; buys=" + plan.path("buys"));
  }

  // ---------------------------------------------------------------------------
  // Test 2: warm call — second invocation (session already initialised)
  // ---------------------------------------------------------------------------

  @Test
  void purchase_warmCall_returns200WithNonEmptyBuys() throws Exception {
    // Ensure the cold path has been exercised first (test ordering not guaranteed by JUnit, but
    // both tests share the same session; the warm call may in fact run first — ProAi handles
    // repeated calls on the same GameData clone correctly because PurchaseExecutor calls
    // WireStateApplier.apply() at the top of each execute() invocation).
    final long start = System.nanoTime();
    final HttpResponse<String> resp = postDecision(PURCHASE_DECISION_BODY);
    final long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    System.out.println("[Phase3PurchaseIntegrationTest] warm call elapsed: " + elapsedMs + " ms");

    assertEquals(200, resp.statusCode(), "purchase must return 200 on warm call; body=" + resp.body());

    final JsonNode envelope = MAPPER.readTree(resp.body());
    assertEquals("ready", envelope.path("status").asText());

    final JsonNode plan = envelope.path("plan");
    assertEquals("purchase", plan.path("kind").asText());
    assertTrue(plan.path("buys").isArray(), "plan.buys must be an array");
    assertTrue(plan.path("repairs").isArray(), "plan.repairs must be an array");
    assertTrue(
        plan.path("buys").size() > 0,
        "warm call: Germans must still buy at least one unit; buys=" + plan.path("buys"));
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private HttpResponse<String> postDecision(final String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(base + "/session/" + sessionId + "/decision"))
            .header("Authorization", auth)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String extractField(final String json, final String field) throws Exception {
    final JsonNode node = MAPPER.readTree(json);
    final JsonNode value = node.get(field);
    return value != null && !value.isNull() ? value.asText() : null;
  }
}
