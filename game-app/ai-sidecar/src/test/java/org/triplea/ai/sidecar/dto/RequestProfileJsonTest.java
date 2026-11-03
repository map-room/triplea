package org.triplea.ai.sidecar.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequestProfileJsonTest {
  private final ObjectMapper mapper = new ObjectMapper();

  private static String body(final String kind, final String extra) {
    return "{\"kind\":\""
        + kind
        + "\",\"seed\":1,\"state\":{\"territories\":[],\"players\":[],\"round\":1,"
        + "\"phase\":\"purchase\",\"currentPlayer\":\"Germans\","
        + "\"gameDataKey\":\"ww2global40_2nd_edition\"}"
        + extra
        + "}";
  }

  @Test
  void profileAbsentDefaultsToNull() throws Exception {
    assertNull(((PurchaseRequest) read(body("purchase", ""))).profile());
    assertNull(((NoncombatMoveRequest) read(body("noncombat-move", ""))).profile());
  }

  @Test
  void profileNullIsNull() throws Exception {
    assertNull(((PurchaseRequest) read(body("purchase", ",\"profile\":null"))).profile());
  }

  @Test
  void profileIsBoundOnBothKinds() throws Exception {
    final String extra = ",\"profile\":\"hard-beta\"";
    assertEquals("hard-beta", ((PurchaseRequest) read(body("purchase", extra))).profile());
    assertEquals(
        "hard-beta", ((NoncombatMoveRequest) read(body("noncombat-move", extra))).profile());
  }

  @Test
  void unknownProfileStillDeserialisesSoTheExecutorOwnsTheRejection() throws Exception {
    assertEquals(
        "beta", ((PurchaseRequest) read(body("purchase", ",\"profile\":\"beta\""))).profile());
  }

  @Test
  void strafingTerritoriesOmittedWhenEmptyAndPresentWhenSet() throws Exception {
    final PurchasePlan none = new PurchasePlan(List.of(), List.of(), List.of());
    assertFalse(mapper.writeValueAsString((DecisionPlan) none).contains("strafingTerritories"));
    assertFalse(mapper.writeValueAsString((DecisionPlan) none).contains("strafeRetreats"));

    final PurchasePlan some =
        new PurchasePlan(
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of("Baltic States"));
    final String json = mapper.writeValueAsString((DecisionPlan) some);
    assertTrue(json.contains("\"strafingTerritories\":[\"Baltic States\"]"));
    final PurchasePlan back = (PurchasePlan) mapper.readValue(json, DecisionPlan.class);
    assertEquals(List.of("Baltic States"), back.strafingTerritories());
    // Other lists stay always-present.
    assertTrue(json.contains("\"sbrMoves\":[]"));
  }

  @Test
  void strafeRetreatsRoundTripAsTerritoryRoundsObjects() throws Exception {
    final PurchasePlan some =
        new PurchasePlan(
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(new StrafeRetreat("Karelia", 1)));
    final String json = mapper.writeValueAsString((DecisionPlan) some);
    assertTrue(json.contains("\"strafeRetreats\":[{\"territory\":\"Karelia\",\"rounds\":1}]"));
    assertFalse(json.contains("strafingTerritories"));
    final PurchasePlan back = (PurchasePlan) mapper.readValue(json, DecisionPlan.class);
    assertEquals(List.of(new StrafeRetreat("Karelia", 1)), back.strafeRetreats());
  }

  @Test
  void oldPlanJsonWithoutStrafingTerritoriesDeserialisesToEmpty() throws Exception {
    final PurchasePlan back =
        (PurchasePlan)
            mapper.readValue(
                "{\"kind\":\"purchase\",\"buys\":[],\"repairs\":[],\"placements\":[]}",
                DecisionPlan.class);
    assertEquals(List.of(), back.strafingTerritories());
    assertEquals(List.of(), back.strafeRetreats());
  }

  private DecisionRequest read(final String json) throws Exception {
    return mapper.readValue(json, DecisionRequest.class);
  }
}
