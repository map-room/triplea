package org.triplea.ai.sidecar;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * <p>Starts a real {@link HttpService} via {@link SidecarMain#startForTest} on port 0 and fires two
 * purchase decision requests against the real {@code PurchaseExecutor}/{@code ProAi} path. Each
 * request constructs its own {@link games.strategy.engine.data.GameData} clone — there is no
 * session lifecycle to set up.
 *
 * <p>Asserts:
 *
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
      "{\"kind\":\"purchase\",\"state\":" + PURCHASE_WIRE_STATE + ",\"seed\":42}";

  @BeforeAll
  static void startService() throws Exception {
    ClientSetting.setPreferences(new MemoryPreferences());

    svc = SidecarMain.startForTest(Map.of("SIDECAR_BIND_HOST", "127.0.0.1", "SIDECAR_PORT", "0"));
    final int port = svc.boundPort();
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    base = "http://127.0.0.1:" + port;
    auth = "Bearer dev-token";
  }

  @AfterAll
  static void stopService() {
    if (svc != null) {
      svc.stop();
    }
  }

  @Test
  void purchase_firstCall_returns200WithNonEmptyBuys() throws Exception {
    final long start = System.nanoTime();
    final HttpResponse<String> resp = postDecision(PURCHASE_DECISION_BODY);
    final long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    System.out.println("[Phase3PurchaseIntegrationTest] first call elapsed: " + elapsedMs + " ms");

    assertEquals(200, resp.statusCode(), "purchase must return 200; body=" + resp.body());

    final JsonNode envelope = MAPPER.readTree(resp.body());
    assertEquals("ready", envelope.path("status").asText(), "envelope status must be 'ready'");

    final JsonNode plan = envelope.path("plan");
    assertEquals("purchase", plan.path("kind").asText(), "plan.kind must be 'purchase'");

    assertTrue(plan.path("buys").isArray(), "plan.buys must be an array");
    assertTrue(plan.path("repairs").isArray(), "plan.repairs must be an array");

    // Germans always have PUs to spend on round 1 — a completely empty buy list is unexpected.
    assertTrue(
        plan.path("buys").size() > 0,
        "Germans must buy at least one unit on round 1; buys=" + plan.path("buys"));
  }

  @Test
  void purchase_repeatedCall_returns200() throws Exception {
    // Two independent calls — each builds its own GameData clone + ProAi internally. Both must
    // succeed. Byte-identical equality on the FULL wire response is NOT asserted here: the
    // documented HashMap-iteration flake on the politics/combat-move side (#2376) means
    // politicalActions and combatMoves can drift across two same-(gamestate, seed) runs even
    // though buys/repairs/placements are stable. The architectural property — every call is
    // hermetic and self-contained — is what this test gates. The structural-fingerprint
    // determinism gate lives in StatelessReplayDeterminismTest.
    final HttpResponse<String> first = postDecision(PURCHASE_DECISION_BODY);
    final HttpResponse<String> second = postDecision(PURCHASE_DECISION_BODY);

    assertEquals(200, first.statusCode(), "first call must return 200; body=" + first.body());
    assertEquals(200, second.statusCode(), "second call must return 200; body=" + second.body());
  }

  private HttpResponse<String> postDecision(final String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(base + "/decision"))
            .header("Authorization", auth)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
