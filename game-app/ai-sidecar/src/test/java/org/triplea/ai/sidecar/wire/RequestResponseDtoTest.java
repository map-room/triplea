package org.triplea.ai.sidecar.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class RequestResponseDtoTest {
  private final ObjectMapper om = new ObjectMapper();

  @Test
  void sessionCreateRequest() throws Exception {
    final SessionCreateRequest r =
        om.readValue("{\"gameId\":\"m-8812\",\"nation\":\"Germans\",\"seed\":42}", SessionCreateRequest.class);
    assertEquals("m-8812", r.gameId());
    assertEquals("Germans", r.nation());
    assertEquals(42L, r.seed());
  }

  @Test
  void sessionCreateResponseSerializesSessionId() throws Exception {
    final String json = om.writeValueAsString(new SessionCreateResponse("s-abc"));
    assertTrue(json.contains("\"sessionId\":\"s-abc\""));
  }

  @Test
  void decisionRequestWithKindAndState() throws Exception {
    final String body =
        "{\"kind\":\"purchase\",\"state\":{\"territories\":[],\"players\":[],\"round\":1,"
            + "\"phase\":\"purchase\",\"currentPlayer\":\"Germans\",\"battleContext\":null}}";
    final DecisionRequest r = om.readValue(body, DecisionRequest.class);
    assertEquals("purchase", r.kind());
    assertEquals("Germans", r.state().currentPlayer());
  }

  @Test
  void decisionResponsePendingSerializes() throws Exception {
    final String json = om.writeValueAsString(DecisionResponse.pending("j-7781"));
    assertTrue(json.contains("\"status\":\"pending\""));
    assertTrue(json.contains("\"jobId\":\"j-7781\""));
  }

  @Test
  void decisionResponseErrorSerializes() throws Exception {
    final String json = om.writeValueAsString(DecisionResponse.error("not-implemented"));
    assertTrue(json.contains("\"status\":\"error\""));
    assertTrue(json.contains("\"error\":\"not-implemented\""));
  }

  @Test
  void sessionUpdateRequestWrapsState() throws Exception {
    final String body =
        "{\"state\":{\"territories\":[],\"players\":[],\"round\":2,\"phase\":\"purchase\","
            + "\"currentPlayer\":\"Germans\",\"battleContext\":null}}";
    final SessionUpdateRequest r = om.readValue(body, SessionUpdateRequest.class);
    assertEquals(2, r.state().round());
    assertTrue(r.state().battleContext() == null || r.state().battleContext().isNull());
  }
}
