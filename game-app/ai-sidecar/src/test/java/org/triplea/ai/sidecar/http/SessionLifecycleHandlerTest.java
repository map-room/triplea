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
    final Session s = registry.createOrGet(new SessionKey("g-1", "Germans"), "g-1:Germans", 42L).session();
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
    final FakeHttpExchange ex = new FakeHttpExchange("POST", "/session/unknown/update", UPDATE_BODY);
    h.handle(ex);
    assertEquals(404, ex.responseCode());
  }

  @Test
  void updateRejectsMalformedBody() throws Exception {
    final SessionRegistry registry = new SessionRegistry(CanonicalGameData.load());
    final Session s = registry.createOrGet(new SessionKey("g-1", "Germans"), "g-1:Germans", 42L).session();
    final SessionLifecycleHandler h = new SessionLifecycleHandler(registry);
    final FakeHttpExchange ex =
        new FakeHttpExchange("POST", "/session/" + s.sessionId() + "/update", "not-json");
    h.handle(ex);
    assertEquals(400, ex.responseCode());
  }

  @Test
  void deleteReturns204ForKnownSession() throws Exception {
    final SessionRegistry registry = new SessionRegistry(CanonicalGameData.load());
    final Session s = registry.createOrGet(new SessionKey("g-1", "Germans"), "g-1:Germans", 42L).session();
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
    final Session s = registry.createOrGet(new SessionKey("g-1", "Germans"), "g-1:Germans", 42L).session();
    final SessionLifecycleHandler h = new SessionLifecycleHandler(registry);
    final FakeHttpExchange ex = new FakeHttpExchange("PUT", "/session/" + s.sessionId(), null);
    h.handle(ex);
    assertEquals(405, ex.responseCode());
  }
}
