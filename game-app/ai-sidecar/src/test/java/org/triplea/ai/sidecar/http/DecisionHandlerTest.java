package org.triplea.ai.sidecar.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.strategy.triplea.settings.ClientSetting;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.AiTraceLogger;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.dto.PurchasePlan;
import org.triplea.ai.sidecar.dto.RetreatPlan;
import org.triplea.ai.sidecar.dto.RetreatQueryRequest;
import org.triplea.ai.sidecar.dto.ScramblePlan;
import org.triplea.ai.sidecar.dto.ScrambleRequest;
import org.triplea.ai.sidecar.exec.DecisionExecutor;
import org.triplea.ai.sidecar.session.Session;
import org.triplea.ai.sidecar.session.SessionKey;
import org.triplea.ai.sidecar.session.SessionRegistry;

class DecisionHandlerTest {

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
  }

  private static final String EMPTY_STATE =
      "\"state\":{\"territories\":[],\"players\":[],\"round\":1,"
          + "\"phase\":\"combat\",\"currentPlayer\":\"Germans\"}";

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

  private SessionRegistry newRegistry() {
    return new SessionRegistry(CanonicalGameData.load());
  }

  private Session newSession(final SessionRegistry registry) {
    return registry
        .createOrGet(new SessionKey("g-1", "Germans", 1), "g-1:Germans:r1", 42L)
        .session();
  }

  private static DecisionHandler handler(
      final SessionRegistry registry,
      final DecisionExecutor<RetreatQueryRequest, RetreatPlan> rq,
      final DecisionExecutor<ScrambleRequest, ScramblePlan> sr) {
    return new DecisionHandler(
        registry, rq, sr, (session, req) -> new PurchasePlan(List.of(), List.of()));
  }

  // ---------------------------------------------------------------------
  // Happy paths — stub executors assert they were called and return a plan
  // Response must be wrapped: {"status":"ready","plan":{...}}
  // ---------------------------------------------------------------------

  @Test
  void retreatQuery_happyPath_returnsPlanJson() throws Exception {
    final SessionRegistry registry = newRegistry();
    final Session s = newSession(registry);
    final DecisionHandler h =
        handler(
            registry,
            (session, req) -> new RetreatPlan("Libya"),
            (session, req) -> {
              throw new AssertionError();
            });

    final FakeHttpExchange ex =
        new FakeHttpExchange("POST", "/session/" + s.sessionId() + "/decision", RETREAT_BODY);
    h.handle(ex);

    assertEquals(200, ex.responseCode());
    final String body = ex.responseBodyString();
    assertTrue(body.contains("\"status\":\"ready\""), body);
    assertTrue(body.contains("\"plan\":{"), body);
    assertTrue(body.contains("\"kind\":\"retreat-or-press\""), body);
    assertTrue(body.contains("\"retreatTo\":\"Libya\""), body);
  }

  @Test
  void scramble_happyPath_returnsPlanJson() throws Exception {
    final SessionRegistry registry = newRegistry();
    final Session s = newSession(registry);
    final DecisionHandler h =
        handler(
            registry,
            (session, req) -> {
              throw new AssertionError();
            },
            (session, req) -> new ScramblePlan(Map.of("United Kingdom", List.of("u7"))));

    final FakeHttpExchange ex =
        new FakeHttpExchange("POST", "/session/" + s.sessionId() + "/decision", SCRAMBLE_BODY);
    h.handle(ex);

    assertEquals(200, ex.responseCode());
    final String body = ex.responseBodyString();
    assertTrue(body.contains("\"status\":\"ready\""), body);
    assertTrue(body.contains("\"plan\":{"), body);
    assertTrue(body.contains("\"kind\":\"scramble\""), body);
    assertTrue(body.contains("u7"), body);
  }

  // ---------------------------------------------------------------------
  // Offensive kinds → 501
  // Response must be: {"status":"error","error":"not-implemented","kind":"<kind>"}
  // ---------------------------------------------------------------------

  @Test
  void offensiveKinds_return501() throws Exception {
    // All known offensive kinds (purchase, combat-move, noncombat-move, place) are now wired
    // to real executors and return 200. No kinds return 501 — this test is a no-op guard.
    for (final String kind : new String[] {}) {
      final SessionRegistry registry = newRegistry();
      final Session s = newSession(registry);
      final DecisionHandler h =
          handler(
              registry,
              (session, req) -> {
                throw new AssertionError();
              },
              (session, req) -> {
                throw new AssertionError();
              });
      final FakeHttpExchange ex =
          new FakeHttpExchange(
              "POST", "/session/" + s.sessionId() + "/decision", offensiveBody(kind));
      h.handle(ex);
      assertEquals(501, ex.responseCode(), "kind=" + kind);
      final String responseBody = ex.responseBodyString();
      assertTrue(
          responseBody.contains("\"status\":\"error\""), "kind=" + kind + "; body=" + responseBody);
      assertTrue(
          responseBody.contains("\"error\":\"not-implemented\""),
          "kind=" + kind + "; body=" + responseBody);
      // kind must round-trip in the 501 body so clients can distinguish offensive sub-kinds
      assertTrue(
          responseBody.contains("\"kind\":\"" + kind + "\""),
          "expected kind=" + kind + " in body, got: " + responseBody);
    }
  }

  // ---------------------------------------------------------------------
  // Error paths — all must have {"status":"error","error":"<code>"}
  // ---------------------------------------------------------------------

  @Test
  void unknownSession_returns404() throws Exception {
    final DecisionHandler h =
        handler(
            newRegistry(),
            (session, req) -> new RetreatPlan(null),
            (session, req) -> new ScramblePlan(Map.of()));
    final FakeHttpExchange ex =
        new FakeHttpExchange("POST", "/session/unknown/decision", RETREAT_BODY);
    h.handle(ex);
    assertEquals(404, ex.responseCode());
    final String body = ex.responseBodyString();
    assertTrue(body.contains("\"status\":\"error\""), body);
    assertTrue(body.contains("\"error\":\"unknown-session\""), body);
  }

  @Test
  void malformedJson_returns400() throws Exception {
    final SessionRegistry registry = newRegistry();
    final Session s = newSession(registry);
    final DecisionHandler h =
        handler(
            registry,
            (session, req) -> new RetreatPlan(null),
            (session, req) -> new ScramblePlan(Map.of()));
    final FakeHttpExchange ex =
        new FakeHttpExchange("POST", "/session/" + s.sessionId() + "/decision", "{not-json");
    h.handle(ex);
    assertEquals(400, ex.responseCode());
    final String body = ex.responseBodyString();
    assertTrue(body.contains("\"status\":\"error\""), body);
    assertTrue(body.contains("\"error\":\"bad-request\""), body);
  }

  @Test
  void missingDiscriminator_returns400() throws Exception {
    final SessionRegistry registry = newRegistry();
    final Session s = newSession(registry);
    final DecisionHandler h =
        handler(
            registry,
            (session, req) -> new RetreatPlan(null),
            (session, req) -> new ScramblePlan(Map.of()));
    final String body = "{" + EMPTY_STATE + "}";
    final FakeHttpExchange ex =
        new FakeHttpExchange("POST", "/session/" + s.sessionId() + "/decision", body);
    h.handle(ex);
    assertEquals(400, ex.responseCode());
    final String responseBody = ex.responseBodyString();
    assertTrue(responseBody.contains("\"status\":\"error\""), responseBody);
  }

  @Test
  void unknownKind_returns400() throws Exception {
    final SessionRegistry registry = newRegistry();
    final Session s = newSession(registry);
    final DecisionHandler h =
        handler(
            registry,
            (session, req) -> new RetreatPlan(null),
            (session, req) -> new ScramblePlan(Map.of()));
    final FakeHttpExchange ex =
        new FakeHttpExchange(
            "POST", "/session/" + s.sessionId() + "/decision", offensiveBody("tech"));
    h.handle(ex);
    assertEquals(400, ex.responseCode());
  }

  // ---------------------------------------------------------------------
  // matchID context (#2004) — DecisionHandler must bind the session's gameId
  // into AiTraceLogger for the duration of the dispatch, then clear it.
  // ---------------------------------------------------------------------

  @Test
  void matchIdIsBoundDuringDispatchAndClearedAfter() throws Exception {
    final SessionRegistry registry = newRegistry();
    final Session s = newSession(registry); // SessionKey gameId = "g-1"
    final AtomicReference<String> seenInExecutor = new AtomicReference<>();
    final DecisionHandler h =
        handler(
            registry,
            (session, req) -> {
              seenInExecutor.set(AiTraceLogger.currentMatchId());
              return new RetreatPlan(null);
            },
            (session, req) -> {
              throw new AssertionError();
            });

    AiTraceLogger.clearMatchId();
    final FakeHttpExchange ex =
        new FakeHttpExchange("POST", "/session/" + s.sessionId() + "/decision", RETREAT_BODY);
    h.handle(ex);

    // During dispatch the executor saw the session's matchID.
    assertEquals("g-1", seenInExecutor.get());
    // After dispatch the per-thread context is cleared so a thread-pool
    // worker reused for the next request doesn't leak the previous matchID.
    assertEquals("unknown", AiTraceLogger.currentMatchId());
  }

  @Test
  void matchIdIsClearedEvenWhenExecutorThrows() throws Exception {
    final SessionRegistry registry = newRegistry();
    final Session s = newSession(registry);
    final DecisionHandler h =
        handler(
            registry,
            (session, req) -> {
              throw new IllegalArgumentException("simulated bad-request");
            },
            (session, req) -> {
              throw new AssertionError();
            });

    AiTraceLogger.clearMatchId();
    final FakeHttpExchange ex =
        new FakeHttpExchange("POST", "/session/" + s.sessionId() + "/decision", RETREAT_BODY);
    h.handle(ex);

    assertEquals(400, ex.responseCode());
    // finally block must clear the matchID even when the executor throws.
    assertEquals("unknown", AiTraceLogger.currentMatchId());
  }

  @Test
  void nonPost_returns405() throws Exception {
    final SessionRegistry registry = newRegistry();
    final Session s = newSession(registry);
    final DecisionHandler h =
        handler(
            registry,
            (session, req) -> new RetreatPlan(null),
            (session, req) -> new ScramblePlan(Map.of()));
    final FakeHttpExchange ex =
        new FakeHttpExchange("GET", "/session/" + s.sessionId() + "/decision", null);
    h.handle(ex);
    assertEquals(405, ex.responseCode());
    final String body = ex.responseBodyString();
    assertTrue(body.contains("\"status\":\"error\""), body);
    assertTrue(body.contains("\"error\":\"method-not-allowed\""), body);
  }
}
