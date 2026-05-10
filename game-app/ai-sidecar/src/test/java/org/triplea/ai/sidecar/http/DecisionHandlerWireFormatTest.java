package org.triplea.ai.sidecar.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import games.strategy.triplea.settings.ClientSetting;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.dto.NoncombatMovePlan;
import org.triplea.ai.sidecar.dto.PurchaseOrder;
import org.triplea.ai.sidecar.dto.PurchasePlan;
import org.triplea.ai.sidecar.dto.WireMoveDescription;

/**
 * Wire-format conformance tests for {@link DecisionHandler}.
 *
 * <p>Asserts the EXACT JSON shape written to the HTTP response body for every code path: success
 * envelopes and all error paths. Uses stub executors to drive the success cases — this is a
 * wire-format test, not an executor test.
 */
class DecisionHandlerWireFormatTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static CanonicalGameData canonical;

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load();
  }

  // ---------------------------------------------------------------------
  // Shared test state
  // ---------------------------------------------------------------------

  private static final String EMPTY_STATE =
      "\"state\":{\"territories\":[],\"players\":[],\"round\":1,"
          + "\"phase\":\"combat\",\"currentPlayer\":\"Germans\"}";

  private static String offensiveBody(final String kind) {
    return "{\"kind\":\"" + kind + "\"," + EMPTY_STATE + ",\"seed\":42}";
  }

  // ---------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------

  private static DecisionHandler stubHandler() {
    return new DecisionHandler(
        canonical,
        (canonical, req) -> new PurchasePlan(List.of(), List.of(), List.of()),
        (canonical, req) -> new NoncombatMovePlan(List.of()));
  }

  private static JsonNode responseJson(final FakeHttpExchange ex) throws Exception {
    return MAPPER.readTree(ex.responseBodyString());
  }

  // ---------------------------------------------------------------------
  // Success envelopes — top-level keys must be exactly "status" + "plan"
  // ---------------------------------------------------------------------

  @Test
  void purchase_200_wireShape() throws Exception {
    final PurchasePlan fixedPlan =
        new PurchasePlan(List.of(new PurchaseOrder("infantry", 1, null)), List.of(), List.of());
    final DecisionHandler h =
        new DecisionHandler(
            canonical,
            (canonical, req) -> fixedPlan,
            (canonical, req) -> new NoncombatMovePlan(List.of()));

    final FakeHttpExchange ex =
        new FakeHttpExchange("POST", "/decision", offensiveBody("purchase"));
    h.handle(ex);

    assertEquals(200, ex.responseCode(), "purchase must return 200");
    final JsonNode root = responseJson(ex);
    assertEquals("ready", root.path("status").asText(), "status must be 'ready'");
    assertTrue(root.has("plan"), "root must have 'plan'");
    assertFalse(root.has("error"), "success body must not have 'error'");

    final JsonNode plan = root.path("plan");
    assertEquals("purchase", plan.path("kind").asText(), "plan.kind must be 'purchase'");
    assertTrue(plan.path("buys").isArray(), "plan.buys must be array");
    assertTrue(plan.path("repairs").isArray(), "plan.repairs must be array");
  }

  @Test
  void noncombatMove_success_wireShape() throws Exception {
    final NoncombatMovePlan fixedPlan =
        new NoncombatMovePlan(
            List.of(
                new WireMoveDescription(
                    List.of("unit-2"),
                    "Germany",
                    "Eastern Europe",
                    java.util.Map.of(),
                    java.util.Map.of(),
                    java.util.Map.of())));

    final DecisionHandler h =
        new DecisionHandler(
            canonical,
            (canonical, req) -> new PurchasePlan(List.of(), List.of(), List.of()),
            (canonical, req) -> fixedPlan);

    final FakeHttpExchange ex =
        new FakeHttpExchange("POST", "/decision", offensiveBody("noncombat-move"));
    h.handle(ex);

    assertEquals(200, ex.responseCode(), "noncombat-move must return 200");
    final JsonNode root = responseJson(ex);
    assertEquals("ready", root.path("status").asText());
    assertEquals("noncombat-move", root.path("plan").path("kind").asText());
    assertTrue(root.path("plan").has("moves"), "plan must have 'moves'");
  }

  // ---------------------------------------------------------------------
  // 404 wrong path
  // ---------------------------------------------------------------------

  @Test
  void wrongPath_404_wireShape() throws Exception {
    final DecisionHandler h = stubHandler();

    final FakeHttpExchange ex =
        new FakeHttpExchange("POST", "/some/wrong/path", offensiveBody("purchase"));
    h.handle(ex);

    assertEquals(404, ex.responseCode());
    final JsonNode root = responseJson(ex);
    assertEquals("error", root.path("status").asText());
    assertEquals("not-found", root.path("error").asText());
    assertFalse(root.has("plan"));
  }

  // ---------------------------------------------------------------------
  // 400 malformed JSON
  // ---------------------------------------------------------------------

  @Test
  void malformedJson_400_wireShape() throws Exception {
    final DecisionHandler h = stubHandler();

    final FakeHttpExchange ex = new FakeHttpExchange("POST", "/decision", "{not-json");
    h.handle(ex);

    assertEquals(400, ex.responseCode());
    final JsonNode root = responseJson(ex);
    assertEquals("error", root.path("status").asText());
    assertEquals("bad-request", root.path("error").asText());
    assertFalse(root.has("plan"));
  }

  // ---------------------------------------------------------------------
  // 400 missing discriminator (no "kind" field)
  // ---------------------------------------------------------------------

  @Test
  void missingDiscriminator_400_wireShape() throws Exception {
    final DecisionHandler h = stubHandler();

    // Valid JSON but no "kind" field
    final String body = "{" + EMPTY_STATE + ",\"seed\":42}";
    final FakeHttpExchange ex = new FakeHttpExchange("POST", "/decision", body);
    h.handle(ex);

    assertEquals(400, ex.responseCode());
    final JsonNode root = responseJson(ex);
    assertEquals("error", root.path("status").asText());
    assertNotNull(root.get("error"), "error code must be present");
    assertFalse(root.has("plan"));
  }

  // ---------------------------------------------------------------------
  // 405 GET → method-not-allowed
  // ---------------------------------------------------------------------

  @Test
  void nonPost_405_wireShape() throws Exception {
    final DecisionHandler h = stubHandler();

    final FakeHttpExchange ex = new FakeHttpExchange("GET", "/decision", null);
    h.handle(ex);

    assertEquals(405, ex.responseCode());
    final JsonNode root = responseJson(ex);
    assertEquals("error", root.path("status").asText());
    assertEquals("method-not-allowed", root.path("error").asText());
    assertFalse(root.has("plan"));
  }
}
