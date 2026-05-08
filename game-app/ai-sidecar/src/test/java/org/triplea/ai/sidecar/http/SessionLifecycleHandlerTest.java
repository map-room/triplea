package org.triplea.ai.sidecar.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.strategy.triplea.settings.ClientSetting;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.session.Session;
import org.triplea.ai.sidecar.session.SessionKey;
import org.triplea.ai.sidecar.session.SessionRegistry;

class SessionLifecycleHandlerTest {

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
  }

  private static final String UPDATE_BODY =
      "{\"state\":{\"territories\":[],\"players\":[],\"round\":1,\"phase\":\"purchase\","
          + "\"currentPlayer\":\"Germans\"}}";

  @Test
  void updateReturns204ForKnownSession() throws Exception {
    final SessionRegistry registry = new SessionRegistry(CanonicalGameData.load());
    final Session s =
        registry.createOrGet(new SessionKey("g-1", "Germans", 1), "g-1:Germans:r1", 42L).session();
    final SessionLifecycleHandler h = new SessionLifecycleHandler(registry);
    final FakeHttpExchange ex =
        new FakeHttpExchange("POST", "/session/" + s.sessionId() + "/update", UPDATE_BODY);
    h.handle(ex);
    assertEquals(204, ex.responseCode());
  }

  @Test
  void updateReturns404ForUnknownSession() throws Exception {
    final SessionLifecycleHandler h =
        new SessionLifecycleHandler(new SessionRegistry(CanonicalGameData.load()));
    final FakeHttpExchange ex =
        new FakeHttpExchange("POST", "/session/unknown/update", UPDATE_BODY);
    h.handle(ex);
    assertEquals(404, ex.responseCode());
  }

  @Test
  void updateRejectsMalformedBody() throws Exception {
    final SessionRegistry registry = new SessionRegistry(CanonicalGameData.load());
    final Session s =
        registry.createOrGet(new SessionKey("g-1", "Germans", 1), "g-1:Germans:r1", 42L).session();
    final SessionLifecycleHandler h = new SessionLifecycleHandler(registry);
    final FakeHttpExchange ex =
        new FakeHttpExchange("POST", "/session/" + s.sessionId() + "/update", "not-json");
    h.handle(ex);
    assertEquals(400, ex.responseCode());
  }

  /**
   * map-room#2305 — the 400 response body must carry the validation exception message so the bot
   * (which now logs response bodies after map-room#2306) has actionable diagnostic signal. Pre-fix,
   * the body was the opaque {@code "invalid JSON body"} string with no detail.
   */
  @Test
  void updateMalformedBodyResponseIncludesValidationDetail() throws Exception {
    final SessionRegistry registry = new SessionRegistry(CanonicalGameData.load());
    final Session s =
        registry.createOrGet(new SessionKey("g-1", "Germans"), "g-1:Germans", 42L).session();
    final SessionLifecycleHandler h = new SessionLifecycleHandler(registry);
    final FakeHttpExchange ex =
        new FakeHttpExchange("POST", "/session/" + s.sessionId() + "/update", "not-json");
    h.handle(ex);
    assertEquals(400, ex.responseCode());
    final String body = ex.responseBodyString();
    assertTrue(
        body.contains("\"error\":\"bad-request\""), "body should retain bad-request code: " + body);
    // Detail message should reference the JSON failure mode (Jackson formats this as either
    // "Unrecognized token" or "Cannot deserialize" — both indicate body-shape validation).
    assertTrue(
        body.contains("\"message\":")
            && body.length() > "{\"error\":\"bad-request\",\"message\":\"\"}".length(),
        "body should include non-empty validation detail: " + body);
  }

  /**
   * map-room#2305 regression — additive wire-schema changes (a new optional field on {@code
   * WirePlayer}) must not trigger 400 on {@code /session/{id}/update}. Pre-fix, ada's TS-side
   * emission of {@code purchasedUnits} caused {@code UnrecognizedPropertyException} which surfaced
   * as opaque 400, which the bot (in turn) handled by burning AI turns silently (map-room#2301).
   */
  @Test
  void updateAcceptsUnknownPropertiesOnWirePlayer() throws Exception {
    final SessionRegistry registry = new SessionRegistry(CanonicalGameData.load());
    final Session s =
        registry.createOrGet(new SessionKey("g-1", "Germans"), "g-1:Germans", 42L).session();
    final SessionLifecycleHandler h = new SessionLifecycleHandler(registry);
    // A WireState payload with a fabricated unknown field on WirePlayer. Pre-fix this 400d.
    final String bodyWithUnknownField =
        "{\"state\":{\"territories\":[],\"players\":[{\"playerId\":\"Germans\",\"pus\":42,"
            + "\"tech\":[],\"capitalCaptured\":false,\"thisFieldDoesNotExist\":\"on-the-pojo\"}],"
            + "\"round\":1,\"phase\":\"purchase\",\"currentPlayer\":\"Germans\"}}";
    final FakeHttpExchange ex =
        new FakeHttpExchange("POST", "/session/" + s.sessionId() + "/update", bodyWithUnknownField);
    h.handle(ex);
    assertEquals(
        204, ex.responseCode(), "unknown-property tolerance: response was " + ex.responseCode());
  }

  @Test
  void deleteReturns204ForKnownSession() throws Exception {
    final SessionRegistry registry = new SessionRegistry(CanonicalGameData.load());
    final Session s =
        registry.createOrGet(new SessionKey("g-1", "Germans", 1), "g-1:Germans:r1", 42L).session();
    final SessionLifecycleHandler h = new SessionLifecycleHandler(registry);
    final FakeHttpExchange ex = new FakeHttpExchange("DELETE", "/session/" + s.sessionId(), null);
    h.handle(ex);
    assertEquals(204, ex.responseCode());
    assertTrue(registry.get(s.sessionId()).isEmpty());
  }

  @Test
  void deleteReturns404ForUnknownSession() throws Exception {
    final SessionLifecycleHandler h =
        new SessionLifecycleHandler(new SessionRegistry(CanonicalGameData.load()));
    final FakeHttpExchange ex = new FakeHttpExchange("DELETE", "/session/unknown", null);
    h.handle(ex);
    assertEquals(404, ex.responseCode());
  }

  @Test
  void rejectsUnknownMethod() throws Exception {
    final SessionRegistry registry = new SessionRegistry(CanonicalGameData.load());
    final Session s =
        registry.createOrGet(new SessionKey("g-1", "Germans", 1), "g-1:Germans:r1", 42L).session();
    final SessionLifecycleHandler h = new SessionLifecycleHandler(registry);
    final FakeHttpExchange ex = new FakeHttpExchange("PUT", "/session/" + s.sessionId(), null);
    h.handle(ex);
    assertEquals(405, ex.responseCode());
  }
}
