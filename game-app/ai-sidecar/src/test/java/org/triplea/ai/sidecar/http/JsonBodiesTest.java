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
    final String s = JsonBodies.writeValue(new SessionCreateRequest("g-1:Germans", "g-1", "Germans", 7));
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
}
