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
            "{\"sessionId\":\"g-1:Germans:r1\",\"gameId\":\"g-1\",\"nation\":\"Germans\",\"round\":1,\"seed\":42}");
    handler.handle(ex);
    assertEquals(200, ex.responseCode());
    assertTrue(ex.responseBodyString().contains("\"sessionId\":\"g-1:Germans:r1\""));
    assertTrue(ex.responseBodyString().contains("\"created\":true"));
  }

  @Test
  void idempotentOpenReturnsFalse() throws Exception {
    final String body =
        "{\"sessionId\":\"g-1:Germans:r1\",\"gameId\":\"g-1\",\"nation\":\"Germans\",\"round\":1,\"seed\":42}";
    final FakeHttpExchange ex1 = new FakeHttpExchange("POST", "/sessions", body);
    handler.handle(ex1);
    assertTrue(ex1.responseBodyString().contains("\"created\":true"));

    final FakeHttpExchange ex2 = new FakeHttpExchange("POST", "/sessions", body);
    handler.handle(ex2);
    assertEquals(200, ex2.responseCode());
    assertTrue(ex2.responseBodyString().contains("\"sessionId\":\"g-1:Germans:r1\""));
    assertTrue(ex2.responseBodyString().contains("\"created\":false"));
  }

  @Test
  void rejectsSessionIdMismatch() throws Exception {
    // wrong prefix but has :r suffix — still a mismatch
    final FakeHttpExchange ex =
        new FakeHttpExchange(
            "POST",
            "/sessions",
            "{\"sessionId\":\"wrong:Germans:r1\",\"gameId\":\"g-1\",\"nation\":\"Germans\",\"round\":1,\"seed\":42}");
    handler.handle(ex);
    assertEquals(400, ex.responseCode());
    assertTrue(ex.responseBodyString().contains("sessionId"));
  }

  @Test
  void rejectsMissingSessionId() throws Exception {
    // body without sessionId — must be rejected
    final FakeHttpExchange ex =
        new FakeHttpExchange(
            "POST",
            "/sessions",
            "{\"gameId\":\"g-1\",\"nation\":\"Germans\",\"round\":1,\"seed\":42}");
    handler.handle(ex);
    assertEquals(400, ex.responseCode());
  }

  @Test
  void persistsSessionToDisk() throws Exception {
    final FakeHttpExchange ex =
        new FakeHttpExchange(
            "POST",
            "/sessions",
            "{\"sessionId\":\"g-1:Germans:r1\",\"gameId\":\"g-1\",\"nation\":\"Germans\",\"round\":1,\"seed\":42}");
    handler.handle(ex);
    assertEquals(200, ex.responseCode());

    // Session manifest should exist at dataDir/g-1/Germans_r1.json (includes round)
    final Path manifest = dataDir.resolve("g-1").resolve("Germans_r1.json");
    assertTrue(Files.exists(manifest), "manifest file not written at " + manifest);
  }

  // --- per-round session ID tests (#2318) ---

  /**
   * RED: Un-suffixed {@code gameId:nation} sessionId must be rejected after the per-round fix.
   * Currently returns 200 (old validator accepts it); after the fix must return 400.
   */
  @Test
  void rejectsUnSuffixedSessionId_perRoundRequired() throws Exception {
    final FakeHttpExchange ex =
        new FakeHttpExchange(
            "POST",
            "/sessions",
            "{\"sessionId\":\"g-1:Germans\",\"gameId\":\"g-1\",\"nation\":\"Germans\",\"seed\":42}");
    handler.handle(ex);
    assertEquals(400, ex.responseCode());
    assertTrue(
        ex.responseBodyString().contains("sessionId"),
        "error body should mention sessionId but got: " + ex.responseBodyString());
  }

  /**
   * RED: Per-round sessionId {@code gameId:nation:r{round}} must be accepted. Currently returns 400
   * (old validator sees "g-1:Germans:r1" ≠ "g-1:Germans"); after the fix must return 200.
   */
  @Test
  void acceptsPerRoundSessionId() throws Exception {
    final FakeHttpExchange ex =
        new FakeHttpExchange(
            "POST",
            "/sessions",
            "{\"sessionId\":\"g-1:Germans:r1\",\"gameId\":\"g-1\",\"nation\":\"Germans\",\"round\":1,\"seed\":42}");
    handler.handle(ex);
    assertEquals(200, ex.responseCode());
    assertTrue(ex.responseBodyString().contains("\"sessionId\":\"g-1:Germans:r1\""));
    assertTrue(ex.responseBodyString().contains("\"created\":true"));
  }

  /** Snapshot file includes round in filename after per-round fix. */
  @Test
  void persistsPerRoundSessionToDisk() throws Exception {
    final FakeHttpExchange ex =
        new FakeHttpExchange(
            "POST",
            "/sessions",
            "{\"sessionId\":\"g-1:Germans:r2\",\"gameId\":\"g-1\",\"nation\":\"Germans\",\"round\":2,\"seed\":42}");
    handler.handle(ex);
    assertEquals(200, ex.responseCode());

    // Manifest file path includes round: dataDir/g-1/Germans_r2.json
    final Path manifest = dataDir.resolve("g-1").resolve("Germans_r2.json");
    assertTrue(Files.exists(manifest), "per-round manifest not written at " + manifest);
  }
}
