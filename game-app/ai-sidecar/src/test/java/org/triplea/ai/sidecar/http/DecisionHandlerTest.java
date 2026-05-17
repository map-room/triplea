package org.triplea.ai.sidecar.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.strategy.triplea.settings.ClientSetting;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.AiTraceLogger;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.dto.NoncombatMovePlan;
import org.triplea.ai.sidecar.dto.PurchasePlan;

class DecisionHandlerTest {

  private static CanonicalGameData canonical;

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load();
  }

  private static final String EMPTY_STATE =
      "\"state\":{\"territories\":[],\"players\":[],\"round\":1,"
          + "\"phase\":\"combat\",\"currentPlayer\":\"Germans\"}";

  private static String body(final String kind) {
    return "{\"kind\":\"" + kind + "\"," + EMPTY_STATE + ",\"seed\":42}";
  }

  private static String bodyWithMatchId(final String kind, final String matchId) {
    return "{\"kind\":\""
        + kind
        + "\","
        + EMPTY_STATE
        + ",\"seed\":42,\"matchId\":\""
        + matchId
        + "\"}";
  }

  private static DecisionHandler stubHandler() {
    return new DecisionHandler(
        canonical,
        (canonical, req) -> new PurchasePlan(List.of(), List.of(), List.of()),
        (canonical, req) -> new NoncombatMovePlan(List.of()));
  }

  // ---------------------------------------------------------------------
  // Error paths — all must have {"status":"error","error":"<code>"}
  // ---------------------------------------------------------------------

  @Test
  void wrongPath_returns404() throws Exception {
    final DecisionHandler h = stubHandler();
    final FakeHttpExchange ex = new FakeHttpExchange("POST", "/wrong/path", body("purchase"));
    h.handle(ex);
    assertEquals(404, ex.responseCode());
    final String b = ex.responseBodyString();
    assertTrue(b.contains("\"status\":\"error\""), b);
    assertTrue(b.contains("\"error\":\"not-found\""), b);
  }

  @Test
  void malformedJson_returns400() throws Exception {
    final DecisionHandler h = stubHandler();
    final FakeHttpExchange ex = new FakeHttpExchange("POST", "/decision", "{not-json");
    h.handle(ex);
    assertEquals(400, ex.responseCode());
    final String b = ex.responseBodyString();
    assertTrue(b.contains("\"status\":\"error\""), b);
    assertTrue(b.contains("\"error\":\"bad-request\""), b);
  }

  @Test
  void missingDiscriminator_returns400() throws Exception {
    final DecisionHandler h = stubHandler();
    final String b = "{" + EMPTY_STATE + ",\"seed\":42}";
    final FakeHttpExchange ex = new FakeHttpExchange("POST", "/decision", b);
    h.handle(ex);
    assertEquals(400, ex.responseCode());
    assertTrue(ex.responseBodyString().contains("\"status\":\"error\""));
  }

  @Test
  void unknownKind_returns400() throws Exception {
    final DecisionHandler h = stubHandler();
    final FakeHttpExchange ex = new FakeHttpExchange("POST", "/decision", body("tech"));
    h.handle(ex);
    assertEquals(400, ex.responseCode());
  }

  // ---------------------------------------------------------------------
  // matchID context (#2004) — DecisionHandler must bind a per-request matchID
  // into AiTraceLogger for the duration of the dispatch, then clear it.
  // ---------------------------------------------------------------------

  @Test
  void matchId_fromEnvelope_usedDirectly() throws Exception {
    // Regression for #2555: when the wire envelope carries a real bgio matchId,
    // it must appear verbatim in the log context — never a synthetic composite.
    final AtomicReference<String> seenInExecutor = new AtomicReference<>();
    final DecisionHandler h =
        new DecisionHandler(
            canonical,
            (canonical, req) -> {
              seenInExecutor.set(AiTraceLogger.currentMatchId());
              return new PurchasePlan(List.of(), List.of(), List.of());
            },
            (canonical, req) -> {
              throw new AssertionError();
            });

    AiTraceLogger.clearAll();
    final FakeHttpExchange ex =
        new FakeHttpExchange("POST", "/decision", bodyWithMatchId("purchase", "bgio-abc123"));
    h.handle(ex);

    assertEquals("bgio-abc123", seenInExecutor.get());
    assertEquals(AiTraceLogger.SENTINEL, AiTraceLogger.currentMatchId());
  }

  @Test
  void matchId_absentFromEnvelope_usesSentinel() throws Exception {
    // Regression for #2555: when matchId is absent, the log context must use '-',
    // never a synthetic player:r{round} fallback.
    final AtomicReference<String> seenInExecutor = new AtomicReference<>();
    final DecisionHandler h =
        new DecisionHandler(
            canonical,
            (canonical, req) -> {
              seenInExecutor.set(AiTraceLogger.currentMatchId());
              return new PurchasePlan(List.of(), List.of(), List.of());
            },
            (canonical, req) -> {
              throw new AssertionError();
            });

    AiTraceLogger.clearAll();
    final FakeHttpExchange ex = new FakeHttpExchange("POST", "/decision", body("purchase"));
    h.handle(ex);

    assertEquals(AiTraceLogger.SENTINEL, seenInExecutor.get());
    assertEquals(AiTraceLogger.SENTINEL, AiTraceLogger.currentMatchId());
  }

  @Test
  void matchIdIsClearedEvenWhenExecutorThrows() throws Exception {
    final DecisionHandler h =
        new DecisionHandler(
            canonical,
            (canonical, req) -> {
              throw new IllegalArgumentException("simulated bad-request");
            },
            (canonical, req) -> {
              throw new AssertionError();
            });

    AiTraceLogger.clearAll();
    final FakeHttpExchange ex = new FakeHttpExchange("POST", "/decision", body("purchase"));
    h.handle(ex);

    assertEquals(400, ex.responseCode());
    // finally block must clear the matchID even when the executor throws.
    assertEquals(AiTraceLogger.SENTINEL, AiTraceLogger.currentMatchId());
  }

  @Test
  void nonPost_returns405() throws Exception {
    final DecisionHandler h = stubHandler();
    final FakeHttpExchange ex = new FakeHttpExchange("GET", "/decision", null);
    h.handle(ex);
    assertEquals(405, ex.responseCode());
    final String b = ex.responseBodyString();
    assertTrue(b.contains("\"status\":\"error\""), b);
    assertTrue(b.contains("\"error\":\"method-not-allowed\""), b);
  }
}
