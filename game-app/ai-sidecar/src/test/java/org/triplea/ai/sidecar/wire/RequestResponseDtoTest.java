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
  void sessionUpdateRequestWrapsState() throws Exception {
    final String body =
        "{\"state\":{\"territories\":[],\"players\":[],\"round\":2,\"phase\":\"purchase\","
            + "\"currentPlayer\":\"Germans\"}}";
    final SessionUpdateRequest r = om.readValue(body, SessionUpdateRequest.class);
    assertEquals(2, r.state().round());
  }
}
