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

class DecisionHandlerTest {

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
  }

  private static final String[] KNOWN_KINDS = {
    "purchase", "combat-move", "noncombat-move", "place",
    "select-casualties", "retreat-or-press", "scramble", "kamikaze",
  };

  private static String bodyFor(final String kind) {
    return "{\"kind\":\"" + kind + "\",\"state\":{\"territories\":[],\"players\":[],\"round\":1,"
        + "\"phase\":\"purchase\",\"currentPlayer\":\"Germans\"}}";
  }

  @Test
  void every_known_kind_returns_501() throws Exception {
    final SessionRegistry registry = new SessionRegistry(CanonicalGameData.load());
    final Session s = registry.createOrGet(new SessionKey("g-1", "Germans"), 42L);
    final DecisionHandler h = new DecisionHandler(registry);
    for (final String kind : KNOWN_KINDS) {
      final FakeHttpExchange ex =
          new FakeHttpExchange("POST", "/session/" + s.sessionId() + "/decision", bodyFor(kind));
      h.handle(ex);
      assertEquals(501, ex.responseCode(), "kind=" + kind);
      assertTrue(ex.responseBodyString().contains("\"status\":\"error\""), "kind=" + kind);
      assertTrue(ex.responseBodyString().contains("not-implemented"), "kind=" + kind);
    }
  }

  @Test
  void unknown_kind_returns_400() throws Exception {
    final SessionRegistry registry = new SessionRegistry(CanonicalGameData.load());
    final Session s = registry.createOrGet(new SessionKey("g-1", "Germans"), 42L);
    final DecisionHandler h = new DecisionHandler(registry);
    final FakeHttpExchange ex =
        new FakeHttpExchange("POST", "/session/" + s.sessionId() + "/decision", bodyFor("tech"));
    h.handle(ex);
    assertEquals(400, ex.responseCode());
  }

  @Test
  void unknown_session_returns_404() throws Exception {
    final DecisionHandler h = new DecisionHandler(new SessionRegistry(CanonicalGameData.load()));
    final FakeHttpExchange ex =
        new FakeHttpExchange("POST", "/session/unknown/decision", bodyFor("purchase"));
    h.handle(ex);
    assertEquals(404, ex.responseCode());
  }

  @Test
  void non_post_returns_405() throws Exception {
    final SessionRegistry registry = new SessionRegistry(CanonicalGameData.load());
    final Session s = registry.createOrGet(new SessionKey("g-1", "Germans"), 42L);
    final DecisionHandler h = new DecisionHandler(registry);
    final FakeHttpExchange ex =
        new FakeHttpExchange("GET", "/session/" + s.sessionId() + "/decision", null);
    h.handle(ex);
    assertEquals(405, ex.responseCode());
  }
}
