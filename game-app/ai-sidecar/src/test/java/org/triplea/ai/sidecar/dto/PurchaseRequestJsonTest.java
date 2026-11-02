package org.triplea.ai.sidecar.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PurchaseRequestJsonTest {
  @Test
  void purchaseRequestDeserialisesThroughSealedDecisionRequest() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    String json =
        "{\"kind\":\"purchase\",\"seed\":42,\"state\":{"
            + "\"territories\":[],\"players\":[],\"round\":1,"
            + "\"phase\":\"purchase\",\"currentPlayer\":\"Germans\","
            + "\"gameDataKey\":\"ww2global40_2nd_edition\"}}";
    DecisionRequest req = mapper.readValue(json, DecisionRequest.class);
    assertInstanceOf(PurchaseRequest.class, req);
    assertEquals("Germans", ((PurchaseRequest) req).state().currentPlayer());
    assertEquals(42L, ((PurchaseRequest) req).seed());
  }
}
