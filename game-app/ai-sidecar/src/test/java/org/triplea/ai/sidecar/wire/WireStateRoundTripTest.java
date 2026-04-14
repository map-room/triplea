package org.triplea.ai.sidecar.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class WireStateRoundTripTest {

  private static final String SAMPLE =
      "{"
          + "\"territories\":["
          + "  {\"territoryId\":\"Germany\",\"owner\":\"Germans\","
          + "   \"units\":["
          + "     {\"unitId\":\"u-4102\",\"unitType\":\"infantry\",\"hitsTaken\":0,\"movesUsed\":0},"
          + "     {\"unitId\":\"u-4103\",\"unitType\":\"armour\"}"
          + "   ]}"
          + "],"
          + "\"players\":["
          + "  {\"playerId\":\"Germans\",\"pus\":30,\"tech\":[],\"capitalCaptured\":false}"
          + "],"
          + "\"round\":3,"
          + "\"phase\":\"combat-move\","
          + "\"currentPlayer\":\"Germans\","
          + "\"battleContext\":null"
          + "}";

  @Test
  void deserializeSample() throws Exception {
    final ObjectMapper om = new ObjectMapper();
    final WireState s = om.readValue(SAMPLE, WireState.class);
    assertEquals(1, s.territories().size());
    assertEquals("Germany", s.territories().get(0).territoryId());
    assertEquals(2, s.territories().get(0).units().size());
    assertEquals("infantry", s.territories().get(0).units().get(0).unitType());
    // hitsTaken / movesUsed default to 0 when omitted
    assertEquals(0, s.territories().get(0).units().get(1).hitsTaken());
    assertEquals(0, s.territories().get(0).units().get(1).movesUsed());
    assertEquals(1, s.players().size());
    assertEquals("Germans", s.players().get(0).playerId());
    assertEquals(30, s.players().get(0).pus());
    assertEquals(3, s.round());
    assertEquals("combat-move", s.phase());
    assertEquals("Germans", s.currentPlayer());
  }

  @Test
  void reserializeMatchesDeserializedShape() throws Exception {
    final ObjectMapper om = new ObjectMapper();
    final WireState parsed = om.readValue(SAMPLE, WireState.class);
    final String json = om.writeValueAsString(parsed);
    final WireState reparsed = om.readValue(json, WireState.class);
    assertEquals(parsed, reparsed);
  }
}
