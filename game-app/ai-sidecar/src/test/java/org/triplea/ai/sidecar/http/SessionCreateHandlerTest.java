package org.triplea.ai.sidecar.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.strategy.triplea.settings.ClientSetting;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.session.SessionRegistry;

class SessionCreateHandlerTest {

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
  }

  @Test
  void createReturnsSessionId() throws Exception {
    final SessionRegistry registry = new SessionRegistry(CanonicalGameData.load());
    final SessionCreateHandler h = new SessionCreateHandler(registry);
    final FakeHttpExchange ex =
        new FakeHttpExchange(
            "POST", "/session", "{\"gameId\":\"g-1\",\"nation\":\"Germans\",\"seed\":42}");
    h.handle(ex);
    assertEquals(200, ex.responseCode());
    assertTrue(ex.responseBodyString().contains("\"sessionId\":\"s-"));
  }

  @Test
  void rejectsNonPost() throws Exception {
    final SessionCreateHandler h =
        new SessionCreateHandler(new SessionRegistry(CanonicalGameData.load()));
    final FakeHttpExchange ex = new FakeHttpExchange("GET", "/session", null);
    h.handle(ex);
    assertEquals(405, ex.responseCode());
  }

  @Test
  void rejectsMalformedBody() throws Exception {
    final SessionCreateHandler h =
        new SessionCreateHandler(new SessionRegistry(CanonicalGameData.load()));
    final FakeHttpExchange ex = new FakeHttpExchange("POST", "/session", "not-json");
    h.handle(ex);
    assertEquals(400, ex.responseCode());
  }

  @Test
  void idempotentCreateReturnsSameId() throws Exception {
    final SessionRegistry registry = new SessionRegistry(CanonicalGameData.load());
    final SessionCreateHandler h = new SessionCreateHandler(registry);
    final String body = "{\"gameId\":\"g-1\",\"nation\":\"Germans\",\"seed\":42}";
    final FakeHttpExchange ex1 = new FakeHttpExchange("POST", "/session", body);
    h.handle(ex1);
    final FakeHttpExchange ex2 = new FakeHttpExchange("POST", "/session", body);
    h.handle(ex2);
    assertEquals(ex1.responseBodyString(), ex2.responseBodyString());
  }
}
