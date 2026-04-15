package org.triplea.ai.sidecar.dto;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PurchaseRequestJsonTest {
  @Test
  void purchaseRequestDeserialisesThroughSealedDecisionRequest() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    String json =
        "{\"kind\":\"purchase\",\"state\":{"
            + "\"territories\":[],\"players\":[],\"round\":1,"
            + "\"phase\":\"purchase\",\"currentPlayer\":\"Germans\"}}";
    DecisionRequest req = mapper.readValue(json, DecisionRequest.class);
    assertInstanceOf(PurchaseRequest.class, req);
    assertEquals("Germans", ((PurchaseRequest) req).state().currentPlayer());
  }

  @Test
  void combatMoveDeserialisesAsOtherOffensive() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    String json =
        "{\"kind\":\"combat-move\",\"state\":{"
            + "\"territories\":[],\"players\":[],\"round\":1,"
            + "\"phase\":\"combatMove\",\"currentPlayer\":\"Germans\"}}";
    DecisionRequest req = mapper.readValue(json, DecisionRequest.class);
    assertInstanceOf(OtherOffensiveRequest.class, req);
    assertEquals("combat-move", ((OtherOffensiveRequest) req).kind());
  }
}
