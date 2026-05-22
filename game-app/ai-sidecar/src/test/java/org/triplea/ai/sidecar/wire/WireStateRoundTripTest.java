package org.triplea.ai.sidecar.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
          + "\"currentPlayer\":\"Germans\""
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

  private static final String AMPHIB_SAMPLE =
      "{"
          + "\"territories\":["
          + "  {\"territoryId\":\"SZ 110\",\"owner\":\"Neutral\","
          + "   \"units\":["
          + "     {\"unitId\":\"inf-1\",\"unitType\":\"infantry\","
          + "      \"embarked\":true,\"embarkedThisTurn\":false},"
          + "     {\"unitId\":\"inf-2\",\"unitType\":\"infantry\","
          + "      \"embarked\":true,\"embarkedThisTurn\":true}"
          + "   ]}"
          + "],"
          + "\"players\":["
          + "  {\"playerId\":\"Americans\",\"pus\":52,\"tech\":[],\"capitalCaptured\":false}"
          + "],"
          + "\"round\":2,"
          + "\"phase\":\"noncombat-move\","
          + "\"currentPlayer\":\"Americans\","
          + "\"relationships\":[],"
          + "\"amphibiousMovementEnabled\":true"
          + "}";

  @Test
  void deserializeAmphib() throws Exception {
    final ObjectMapper om = new ObjectMapper();
    final WireState s = om.readValue(AMPHIB_SAMPLE, WireState.class);
    assertTrue(s.amphibiousMovementEnabled());
    assertEquals(1, s.territories().size());
    assertEquals(2, s.territories().get(0).units().size());
    final WireUnit staged = s.territories().get(0).units().get(0);
    assertTrue(staged.embarked());
    assertFalse(staged.embarkedThisTurn());
    final WireUnit freshlyEmbarked = s.territories().get(0).units().get(1);
    assertTrue(freshlyEmbarked.embarked());
    assertTrue(freshlyEmbarked.embarkedThisTurn());
  }

  @Test
  void amphibRoundTripPreservesEmbarkedFields() throws Exception {
    final ObjectMapper om = new ObjectMapper();
    final WireState parsed = om.readValue(AMPHIB_SAMPLE, WireState.class);
    final String json = om.writeValueAsString(parsed);
    final WireState reparsed = om.readValue(json, WireState.class);
    assertEquals(parsed, reparsed);
    assertTrue(reparsed.amphibiousMovementEnabled());
    assertTrue(reparsed.territories().get(0).units().get(0).embarked());
    assertFalse(reparsed.territories().get(0).units().get(0).embarkedThisTurn());
    assertTrue(reparsed.territories().get(0).units().get(1).embarkedThisTurn());
  }

  @Test
  void legacyStateWithoutAmphFieldsDeserializesWithDefaults() throws Exception {
    final ObjectMapper om = new ObjectMapper();
    final WireState s = om.readValue(SAMPLE, WireState.class);
    assertFalse(s.amphibiousMovementEnabled());
    for (final var unit : s.territories().get(0).units()) {
      assertFalse(unit.embarked());
      assertFalse(unit.embarkedThisTurn());
    }
  }
}
