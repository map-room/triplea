package org.triplea.ai.sidecar.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import games.strategy.triplea.settings.ClientSetting;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.dto.CombatMoveOrder;
import org.triplea.ai.sidecar.dto.CombatMovePlan;
import org.triplea.ai.sidecar.dto.CombatMoveRequest;
import org.triplea.ai.sidecar.dto.PurchaseOrder;
import org.triplea.ai.sidecar.dto.PurchasePlan;
import org.triplea.ai.sidecar.dto.PurchaseRequest;
import org.triplea.ai.sidecar.dto.RetreatPlan;
import org.triplea.ai.sidecar.dto.RetreatQueryRequest;
import org.triplea.ai.sidecar.dto.ScramblePlan;
import org.triplea.ai.sidecar.dto.ScrambleRequest;
import org.triplea.ai.sidecar.dto.SelectCasualtiesPlan;
import org.triplea.ai.sidecar.dto.SelectCasualtiesRequest;
import org.triplea.ai.sidecar.exec.DecisionExecutor;
import org.triplea.ai.sidecar.session.Session;
import org.triplea.ai.sidecar.session.SessionKey;
import org.triplea.ai.sidecar.session.SessionRegistry;

/**
 * Wire-format conformance tests for {@link DecisionHandler}.
 *
 * <p>Asserts the EXACT JSON shape written to the HTTP response body for every code path:
 * success envelopes, 501 offensive kinds, and all error paths. Uses stub executors to drive
 * the success cases — this is a wire-format test, not an executor test.
 */
class DecisionHandlerWireFormatTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
  }

  // ---------------------------------------------------------------------
  // Shared test state
  // ---------------------------------------------------------------------

  private static final String EMPTY_STATE =
      "\"state\":{\"territories\":[],\"players\":[],\"round\":1,"
          + "\"phase\":\"combat\",\"currentPlayer\":\"Germans\"}";

  private static final String SELECT_CASUALTIES_BODY =
      "{\"kind\":\"select-casualties\","
          + EMPTY_STATE
          + ",\"battle\":{\"battleId\":\"b1\",\"territory\":\"Egypt\","
          + "\"attackerNation\":\"British\",\"defenderNation\":\"Germans\","
          + "\"hitCount\":0,\"selectFrom\":[],\"friendlyUnits\":[],\"enemyUnits\":[],"
          + "\"isAmphibious\":false,\"amphibiousLandAttackers\":[],"
          + "\"defaultCasualties\":[],\"allowMultipleHitsPerUnit\":false}}";

  private static final String RETREAT_BODY =
      "{\"kind\":\"retreat-or-press\","
          + EMPTY_STATE
          + ",\"battle\":{\"battleId\":\"b1\",\"battleTerritory\":\"Egypt\","
          + "\"canSubmerge\":false,\"possibleRetreatTerritories\":[\"Libya\"]}}";

  private static final String SCRAMBLE_BODY =
      "{\"kind\":\"scramble\","
          + EMPTY_STATE
          + ",\"battle\":{\"defendingTerritory\":\"110 Sea Zone\",\"possibleScramblers\":{}}}";

  private static String offensiveBody(final String kind) {
    return "{\"kind\":\"" + kind + "\"," + EMPTY_STATE + "}";
  }

  // ---------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------

  private SessionRegistry newRegistry() {
    return new SessionRegistry(CanonicalGameData.load());
  }

  private Session newSession(final SessionRegistry registry) {
    return registry.createOrGet(new SessionKey("g-1", "Germans"), 42L);
  }

  private DecisionHandler stubHandler(
      final SessionRegistry registry,
      final DecisionExecutor<SelectCasualtiesRequest, SelectCasualtiesPlan> sc,
      final DecisionExecutor<RetreatQueryRequest, RetreatPlan> rq,
      final DecisionExecutor<ScrambleRequest, ScramblePlan> sr) {
    // Default purchase stub: returns an empty plan so wire-format tests that don't exercise
    // purchase still compile and route correctly.
    return new DecisionHandler(
        registry, sc, rq, sr,
        (session, req) -> new PurchasePlan(List.of(), List.of()));
  }

  private JsonNode responseJson(final FakeHttpExchange ex) throws Exception {
    return MAPPER.readTree(ex.responseBodyString());
  }

  // ---------------------------------------------------------------------
  // Success envelopes — top-level keys must be exactly "status" + "plan"
  // ---------------------------------------------------------------------

  @Test
  void selectCasualties_success_wireShape() throws Exception {
    final SessionRegistry registry = newRegistry();
    final Session s = newSession(registry);
    final DecisionHandler h = stubHandler(
        registry,
        (session, req) -> new SelectCasualtiesPlan(List.of("u-inf-1"), List.of()),
        (session, req) -> { throw new AssertionError(); },
        (session, req) -> { throw new AssertionError(); });

    final FakeHttpExchange ex =
        new FakeHttpExchange("POST", "/session/" + s.sessionId() + "/decision", SELECT_CASUALTIES_BODY);
    h.handle(ex);

    assertEquals(200, ex.responseCode());
    final JsonNode root = responseJson(ex);

    // Envelope: exactly status + plan
    assertEquals("ready", root.path("status").asText(), "status must be 'ready'");
    assertTrue(root.has("plan"), "root must have 'plan'");
    assertFalse(root.has("error"), "success body must not have 'error'");

    // plan: kind discriminator + payload fields
    final JsonNode plan = root.path("plan");
    assertEquals("select-casualties", plan.path("kind").asText(), "plan.kind");
    assertTrue(plan.path("killed").isArray(), "plan.killed must be array");
    assertTrue(plan.path("damaged").isArray(), "plan.damaged must be array");
    assertEquals(1, plan.path("killed").size(), "killed count");
    assertEquals("u-inf-1", plan.path("killed").get(0).asText(), "killed[0]");
    assertEquals(0, plan.path("damaged").size(), "damaged count");

    // No extra top-level fields
    final Set<String> topKeys = Set.of("status", "plan");
    root.fieldNames().forEachRemaining(k ->
        assertTrue(topKeys.contains(k), "unexpected top-level key: " + k));
  }

  @Test
  void retreatOrPress_success_wireShape() throws Exception {
    final SessionRegistry registry = newRegistry();
    final Session s = newSession(registry);
    final DecisionHandler h = stubHandler(
        registry,
        (session, req) -> { throw new AssertionError(); },
        (session, req) -> new RetreatPlan("Libya"),
        (session, req) -> { throw new AssertionError(); });

    final FakeHttpExchange ex =
        new FakeHttpExchange("POST", "/session/" + s.sessionId() + "/decision", RETREAT_BODY);
    h.handle(ex);

    assertEquals(200, ex.responseCode());
    final JsonNode root = responseJson(ex);

    assertEquals("ready", root.path("status").asText());
    assertTrue(root.has("plan"));

    final JsonNode plan = root.path("plan");
    assertEquals("retreat-or-press", plan.path("kind").asText(), "plan.kind");
    assertTrue(plan.has("retreatTo"), "plan must have retreatTo");
    assertEquals("Libya", plan.path("retreatTo").asText(), "plan.retreatTo");
  }

  @Test
  void retreatOrPress_nullRetreatTo_wireShape() throws Exception {
    final SessionRegistry registry = newRegistry();
    final Session s = newSession(registry);
    final DecisionHandler h = stubHandler(
        registry,
        (session, req) -> { throw new AssertionError(); },
        (session, req) -> new RetreatPlan(null),
        (session, req) -> { throw new AssertionError(); });

    final FakeHttpExchange ex =
        new FakeHttpExchange("POST", "/session/" + s.sessionId() + "/decision", RETREAT_BODY);
    h.handle(ex);

    assertEquals(200, ex.responseCode());
    final JsonNode plan = responseJson(ex).path("plan");
    assertEquals("retreat-or-press", plan.path("kind").asText());
    assertTrue(plan.has("retreatTo"), "retreatTo must be present (null)");
    assertTrue(plan.get("retreatTo").isNull(), "retreatTo must be JSON null");
  }

  @Test
  void scramble_success_wireShape() throws Exception {
    final SessionRegistry registry = newRegistry();
    final Session s = newSession(registry);
    final DecisionHandler h = stubHandler(
        registry,
        (session, req) -> { throw new AssertionError(); },
        (session, req) -> { throw new AssertionError(); },
        (session, req) -> new ScramblePlan(Map.of("Western Germany", List.of("u-ftr-1"))));

    final FakeHttpExchange ex =
        new FakeHttpExchange("POST", "/session/" + s.sessionId() + "/decision", SCRAMBLE_BODY);
    h.handle(ex);

    assertEquals(200, ex.responseCode());
    final JsonNode root = responseJson(ex);

    assertEquals("ready", root.path("status").asText());
    final JsonNode plan = root.path("plan");
    assertEquals("scramble", plan.path("kind").asText(), "plan.kind");
    assertTrue(plan.path("scramblers").isObject(), "plan.scramblers must be object");
    assertTrue(plan.path("scramblers").has("Western Germany"));
    assertTrue(plan.path("scramblers").path("Western Germany").isArray());
    assertEquals("u-ftr-1", plan.path("scramblers").path("Western Germany").get(0).asText());
  }

  // ---------------------------------------------------------------------
  // Purchase → 200 with status=ready, plan.kind=purchase (Phase 3 wired)
  // ---------------------------------------------------------------------

  @Test
  void purchase_200_wireShape() throws Exception {
    final SessionRegistry registry = newRegistry();
    final Session s = newSession(registry);
    final PurchasePlan fixedPlan =
        new PurchasePlan(List.of(new PurchaseOrder("infantry", 1, null)), List.of());
    final DecisionHandler h =
        new DecisionHandler(
            registry,
            (session, req) -> { throw new AssertionError(); },
            (session, req) -> { throw new AssertionError(); },
            (session, req) -> { throw new AssertionError(); },
            (session, req) -> fixedPlan);

    final FakeHttpExchange ex =
        new FakeHttpExchange("POST", "/session/" + s.sessionId() + "/decision", offensiveBody("purchase"));
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

  // ---------------------------------------------------------------------
  // Remaining offensive kinds → 501 with status=error, error=not-implemented, kind=<kind>
  // ---------------------------------------------------------------------

  @Test
  void combatMove_success_wireShape() throws Exception {
    // combat-move is now wired to CombatMoveExecutor; verify it returns 200 + ready envelope
    final SessionRegistry registry = newRegistry();
    final Session s = newSession(registry);
    final CombatMovePlan fixedPlan = new CombatMovePlan(
        List.of(new CombatMoveOrder(List.of("unit-1"), "Germany", "Poland")),
        List.of());

    final DecisionHandler h = new DecisionHandler(
        registry,
        (session, req) -> { throw new AssertionError(); },
        (session, req) -> { throw new AssertionError(); },
        (session, req) -> { throw new AssertionError(); },
        (session, req) -> { throw new AssertionError(); },
        (session, req) -> fixedPlan);

    final FakeHttpExchange ex = new FakeHttpExchange(
        "POST", "/session/" + s.sessionId() + "/decision", offensiveBody("combat-move"));
    h.handle(ex);

    assertEquals(200, ex.responseCode(), "combat-move must return 200");
    final JsonNode root = responseJson(ex);
    assertEquals("ready", root.path("status").asText());
    assertEquals("combat-move", root.path("plan").path("kind").asText());
    assertTrue(root.path("plan").has("moves"), "plan must have 'moves'");
    assertTrue(root.path("plan").has("sbrMoves"), "plan must have 'sbrMoves'");
  }

  @Test
  void noncombatMove_501_wireShape() throws Exception {
    assertOffensive501Shape("noncombat-move");
  }

  @Test
  void place_501_wireShape() throws Exception {
    assertOffensive501Shape("place");
  }

  private void assertOffensive501Shape(final String kind) throws Exception {
    final SessionRegistry registry = newRegistry();
    final Session s = newSession(registry);
    final DecisionHandler h = stubHandler(
        registry,
        (session, req) -> { throw new AssertionError(); },
        (session, req) -> { throw new AssertionError(); },
        (session, req) -> { throw new AssertionError(); });

    final FakeHttpExchange ex =
        new FakeHttpExchange("POST", "/session/" + s.sessionId() + "/decision", offensiveBody(kind));
    h.handle(ex);

    assertEquals(501, ex.responseCode(), "kind=" + kind);
    final JsonNode root = responseJson(ex);

    assertEquals("error", root.path("status").asText(), "status must be 'error'; kind=" + kind);
    assertEquals("not-implemented", root.path("error").asText(), "error code; kind=" + kind);
    assertEquals(kind, root.path("kind").asText(), "kind must round-trip; kind=" + kind);
    assertFalse(root.has("plan"), "error body must not have 'plan'; kind=" + kind);
    assertFalse(root.has("message"), "error body must not have 'message'; kind=" + kind);
  }

  // ---------------------------------------------------------------------
  // 404 unknown session
  // ---------------------------------------------------------------------

  @Test
  void unknownSession_404_wireShape() throws Exception {
    final DecisionHandler h = stubHandler(
        newRegistry(),
        (session, req) -> new SelectCasualtiesPlan(List.of(), List.of()),
        (session, req) -> new RetreatPlan(null),
        (session, req) -> new ScramblePlan(Map.of()));

    final FakeHttpExchange ex =
        new FakeHttpExchange("POST", "/session/no-such-session/decision", SELECT_CASUALTIES_BODY);
    h.handle(ex);

    assertEquals(404, ex.responseCode());
    final JsonNode root = responseJson(ex);
    assertEquals("error", root.path("status").asText());
    assertEquals("unknown-session", root.path("error").asText());
    assertFalse(root.has("plan"));
  }

  // ---------------------------------------------------------------------
  // 400 malformed JSON
  // ---------------------------------------------------------------------

  @Test
  void malformedJson_400_wireShape() throws Exception {
    final SessionRegistry registry = newRegistry();
    final Session s = newSession(registry);
    final DecisionHandler h = stubHandler(
        registry,
        (session, req) -> new SelectCasualtiesPlan(List.of(), List.of()),
        (session, req) -> new RetreatPlan(null),
        (session, req) -> new ScramblePlan(Map.of()));

    final FakeHttpExchange ex =
        new FakeHttpExchange("POST", "/session/" + s.sessionId() + "/decision", "{not-json");
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
    final SessionRegistry registry = newRegistry();
    final Session s = newSession(registry);
    final DecisionHandler h = stubHandler(
        registry,
        (session, req) -> new SelectCasualtiesPlan(List.of(), List.of()),
        (session, req) -> new RetreatPlan(null),
        (session, req) -> new ScramblePlan(Map.of()));

    // Valid JSON but no "kind" field
    final String body = "{" + EMPTY_STATE + "}";
    final FakeHttpExchange ex =
        new FakeHttpExchange("POST", "/session/" + s.sessionId() + "/decision", body);
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
    final SessionRegistry registry = newRegistry();
    final Session s = newSession(registry);
    final DecisionHandler h = stubHandler(
        registry,
        (session, req) -> new SelectCasualtiesPlan(List.of(), List.of()),
        (session, req) -> new RetreatPlan(null),
        (session, req) -> new ScramblePlan(Map.of()));

    final FakeHttpExchange ex =
        new FakeHttpExchange("GET", "/session/" + s.sessionId() + "/decision", null);
    h.handle(ex);

    assertEquals(405, ex.responseCode());
    final JsonNode root = responseJson(ex);
    assertEquals("error", root.path("status").asText());
    assertEquals("method-not-allowed", root.path("error").asText());
    assertFalse(root.has("plan"));
  }
}
