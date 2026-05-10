package org.triplea.ai.sidecar.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.triplea.ai.sidecar.dto.PurchaseRequest;
import org.triplea.ai.sidecar.wire.WireState;

class JsonBodiesTest {
  @Test
  void readValueParsesKnownDto() throws Exception {
    final PurchaseRequest r =
        JsonBodies.readValue(
            "{\"kind\":\"purchase\",\"state\":{\"territories\":[],\"players\":[],\"round\":1,"
                + "\"phase\":\"purchase\",\"currentPlayer\":\"Germans\"},\"seed\":7}",
            PurchaseRequest.class);
    assertEquals("Germans", r.state().currentPlayer());
    assertEquals(1, r.state().round());
    assertEquals(7L, r.seed());
  }

  @Test
  void writeValueSerializes() throws Exception {
    final WireState state =
        new WireState(List.of(), List.of(), 1, "purchase", "Germans", List.of());
    final String s = JsonBodies.writeValue(new PurchaseRequest(state, 7L));
    assertTrue(s.contains("\"currentPlayer\":\"Germans\""));
    assertTrue(s.contains("\"seed\":7"));
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
   * even before the Java POJO catches up.
   */
  @Test
  void readValueIgnoresUnknownProperties() throws Exception {
    final PurchaseRequest r =
        JsonBodies.readValue(
            "{\"kind\":\"purchase\",\"state\":{\"territories\":[],\"players\":[],\"round\":1,"
                + "\"phase\":\"purchase\",\"currentPlayer\":\"Germans\"},\"seed\":7,"
                + "\"unknownExtraField\":\"ignored\",\"anotherUnknown\":{\"nested\":42}}",
            PurchaseRequest.class);
    assertEquals("Germans", r.state().currentPlayer());
    assertEquals(7L, r.seed());
  }
}
