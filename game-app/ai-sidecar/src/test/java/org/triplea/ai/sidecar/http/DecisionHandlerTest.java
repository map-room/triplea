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
  void matchIdIsBoundDuringDispatchAndClearedAfter() throws Exception {
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

    AiTraceLogger.clearMatchId();
    final FakeHttpExchange ex = new FakeHttpExchange("POST", "/decision", body("purchase"));
    h.handle(ex);

    // During dispatch the executor saw a request-scoped matchID derived from
    // currentPlayer:r{round}.
    assertEquals("Germans:r1", seenInExecutor.get());
    // After dispatch the per-thread context is cleared so a thread-pool worker reused for the
    // next request doesn't leak the previous matchID.
    assertEquals("unknown", AiTraceLogger.currentMatchId());
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

    AiTraceLogger.clearMatchId();
    final FakeHttpExchange ex = new FakeHttpExchange("POST", "/decision", body("purchase"));
    h.handle(ex);

    assertEquals(400, ex.responseCode());
    // finally block must clear the matchID even when the executor throws.
    assertEquals("unknown", AiTraceLogger.currentMatchId());
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
