package org.triplea.ai.sidecar.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.strategy.triplea.settings.ClientSetting;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.session.SessionRegistry;

/**
 * TDD tests for the v2 session-create contract: - sessionId is deterministic and supplied by the
 * caller. - POST /sessions is idempotent: re-opening an existing session returns created=false. -
 * sessionId is validated: must equal gameId + ":" + nation. - Sessions are persisted to disk after
 * creation.
 */
class SessionCreateHandlerV2Test {

  @TempDir Path dataDir;

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
  }

  private SessionRegistry registry;
  private SessionCreateHandler handler;

  @BeforeEach
  void setUp() throws IOException {
    registry = new SessionRegistry(CanonicalGameData.load(), dataDir);
    handler = new SessionCreateHandler(registry);
  }

  @Test
  void createsSessionWithDeterministicId() throws Exception {
    final FakeHttpExchange ex =
        new FakeHttpExchange(
            "POST",
            "/sessions",
            "{\"sessionId\":\"g-1:Germans\",\"gameId\":\"g-1\",\"nation\":\"Germans\",\"seed\":42}");
    handler.handle(ex);
    assertEquals(200, ex.responseCode());
    assertTrue(ex.responseBodyString().contains("\"sessionId\":\"g-1:Germans\""));
    assertTrue(ex.responseBodyString().contains("\"created\":true"));
  }

  @Test
  void idempotentOpenReturnsFalse() throws Exception {
    final String body =
        "{\"sessionId\":\"g-1:Germans\",\"gameId\":\"g-1\",\"nation\":\"Germans\",\"seed\":42}";
    final FakeHttpExchange ex1 = new FakeHttpExchange("POST", "/sessions", body);
    handler.handle(ex1);
    assertTrue(ex1.responseBodyString().contains("\"created\":true"));

    final FakeHttpExchange ex2 = new FakeHttpExchange("POST", "/sessions", body);
    handler.handle(ex2);
    assertEquals(200, ex2.responseCode());
    assertTrue(ex2.responseBodyString().contains("\"sessionId\":\"g-1:Germans\""));
    assertTrue(ex2.responseBodyString().contains("\"created\":false"));
  }

  @Test
  void rejectsSessionIdMismatch() throws Exception {
    final FakeHttpExchange ex =
        new FakeHttpExchange(
            "POST",
            "/sessions",
            "{\"sessionId\":\"wrong:Germans\",\"gameId\":\"g-1\",\"nation\":\"Germans\",\"seed\":42}");
    handler.handle(ex);
    assertEquals(400, ex.responseCode());
    assertTrue(ex.responseBodyString().contains("sessionId"));
  }

  @Test
  void rejectsMissingSessionId() throws Exception {
    // v1 body without sessionId — must be rejected by v2 handler
    final FakeHttpExchange ex =
        new FakeHttpExchange(
            "POST", "/sessions", "{\"gameId\":\"g-1\",\"nation\":\"Germans\",\"seed\":42}");
    handler.handle(ex);
    assertEquals(400, ex.responseCode());
  }

  @Test
  void persistsSessionToDisk() throws Exception {
    final FakeHttpExchange ex =
        new FakeHttpExchange(
            "POST",
            "/sessions",
            "{\"sessionId\":\"g-1:Germans\",\"gameId\":\"g-1\",\"nation\":\"Germans\",\"seed\":42}");
    handler.handle(ex);
    assertEquals(200, ex.responseCode());

    // Session manifest should exist at dataDir/g-1/Germans.json
    final Path manifest = dataDir.resolve("g-1").resolve("Germans.json");
    assertTrue(Files.exists(manifest), "manifest file not written at " + manifest);
  }
}
