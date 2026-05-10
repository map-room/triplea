package org.triplea.ai.sidecar.dto;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PurchaseRequestJsonTest {
  @Test
  void purchaseRequestDeserialisesThroughSealedDecisionRequest() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    String json =
        "{\"kind\":\"purchase\",\"seed\":42,\"state\":{"
            + "\"territories\":[],\"players\":[],\"round\":1,"
            + "\"phase\":\"purchase\",\"currentPlayer\":\"Germans\"}}";
    DecisionRequest req = mapper.readValue(json, DecisionRequest.class);
    assertInstanceOf(PurchaseRequest.class, req);
    assertEquals("Germans", ((PurchaseRequest) req).state().currentPlayer());
    assertEquals(42L, ((PurchaseRequest) req).seed());
  }
}
