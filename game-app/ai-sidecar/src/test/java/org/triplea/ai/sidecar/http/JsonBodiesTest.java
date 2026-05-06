package org.triplea.ai.sidecar.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.triplea.ai.sidecar.wire.SessionCreateRequest;

class JsonBodiesTest {
  @Test
  void readValueParsesKnownDto() throws Exception {
    final SessionCreateRequest r =
        JsonBodies.readValue(
            "{\"sessionId\":\"g-1:Germans\",\"gameId\":\"g-1\",\"nation\":\"Germans\",\"seed\":7}",
            SessionCreateRequest.class);
    assertEquals("g-1:Germans", r.sessionId());
    assertEquals("g-1", r.gameId());
  }

  @Test
  void writeValueSerializes() throws Exception {
    final String s =
        JsonBodies.writeValue(new SessionCreateRequest("g-1:Germans", "g-1", "Germans", 7));
    assertTrue(s.contains("\"gameId\":\"g-1\""));
    assertTrue(s.contains("\"sessionId\":\"g-1:Germans\""));
  }

  @Test
  void errorEnvelopeShape() throws Exception {
    final String s = JsonBodies.errorBody("bad-request", "missing gameId");
    assertTrue(s.contains("\"error\":\"bad-request\""));
    assertTrue(s.contains("\"message\":\"missing gameId\""));
  }

  @Test
  void decisionErrorEnvelopeShape() throws Exception {
    final String s = JsonBodies.errorBody("bad-request");
    assertTrue(s.contains("\"status\":\"error\""));
    assertTrue(s.contains("\"error\":\"bad-request\""));
  }

  /**
   * Regression for map-room#2305: the wire-surface ObjectMapper must tolerate unknown properties so
   * additive TS-side wire-schema changes (a new optional field on a Wire DTO) deserialize cleanly
   * even before the Java POJO catches up. Pre-fix, an unknown property triggered {@code
   * UnrecognizedPropertyException} which surfaced as opaque 400 on every {@code
   * /session/{id}/update} call (map-room#2301).
   */
  @Test
  void readValueIgnoresUnknownProperties() throws Exception {
    final SessionCreateRequest r =
        JsonBodies.readValue(
            "{\"sessionId\":\"g-1:Germans\",\"gameId\":\"g-1\",\"nation\":\"Germans\","
                + "\"seed\":7,\"unknownExtraField\":\"ignored\","
                + "\"anotherUnknown\":{\"nested\":42}}",
            SessionCreateRequest.class);
    assertEquals("g-1:Germans", r.sessionId());
    assertEquals("g-1", r.gameId());
    assertEquals("Germans", r.nation());
    assertEquals(7L, r.seed());
  }
}
