package org.triplea.ai.sidecar.dto;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PurchasePlanJsonTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void purchasePlanRoundTripsWithKindDiscriminator() throws Exception {
    PurchasePlan plan =
        new PurchasePlan(
            List.of(new PurchaseOrder("infantry", 3, null), new PurchaseOrder("armour", 1, null)),
            List.of(new RepairOrder("Germany", "factory_major", 2)),
            List.of());
    String json = mapper.writeValueAsString((DecisionPlan) plan);
    assertTrue(json.contains("\"kind\":\"purchase\""));
    DecisionPlan decoded = mapper.readValue(json, DecisionPlan.class);
    assertInstanceOf(PurchasePlan.class, decoded);
    PurchasePlan back = (PurchasePlan) decoded;
    assertEquals(2, back.buys().size());
    assertEquals("infantry", back.buys().get(0).unitType());
    assertEquals(3, back.buys().get(0).count());
    assertEquals("Germany", back.repairs().get(0).territory());
  }

  @Test
  void purchasePlanRoundTripsWithPlacements() throws Exception {
    PurchasePlan plan =
        new PurchasePlan(
            List.of(new PurchaseOrder("infantry", 2, "Germany")),
            List.of(),
            List.of(
                new PlacementGroup("Germany", List.of("infantry", "infantry"), false, false),
                new PlacementGroup("5 Sea Zone", List.of("destroyer"), true, false),
                new PlacementGroup("Germany", List.of("factory_major"), false, true)));
    String json = mapper.writeValueAsString((DecisionPlan) plan);
    assertTrue(json.contains("\"placements\""));
    assertTrue(json.contains("\"isWater\""));
    assertTrue(json.contains("\"isConstruction\""));
    DecisionPlan decoded = mapper.readValue(json, DecisionPlan.class);
    assertInstanceOf(PurchasePlan.class, decoded);
    PurchasePlan back = (PurchasePlan) decoded;
    assertEquals(3, back.placements().size());
    assertEquals("Germany", back.placements().get(0).territory());
    assertFalse(back.placements().get(0).isWater());
    assertFalse(back.placements().get(0).isConstruction());
    assertTrue(back.placements().get(1).isWater());
    assertTrue(back.placements().get(2).isConstruction());
  }

  @Test
  void purchasePlanRoundTripsWithPoliticalActions() throws Exception {
    PurchasePlan plan =
        new PurchasePlan(
            List.of(new PurchaseOrder("infantry", 2, "Germany")),
            List.of(),
            List.of(),
            List.of(new WarDeclaration("Russians"), new WarDeclaration("British")));
    String json = mapper.writeValueAsString((DecisionPlan) plan);
    assertTrue(json.contains("\"politicalActions\""));
    assertTrue(json.contains("\"Russians\""));
    DecisionPlan decoded = mapper.readValue(json, DecisionPlan.class);
    assertInstanceOf(PurchasePlan.class, decoded);
    PurchasePlan back = (PurchasePlan) decoded;
    assertEquals(2, back.politicalActions().size());
    assertEquals("Russians", back.politicalActions().get(0).target());
    assertEquals("British", back.politicalActions().get(1).target());
  }

  @Test
  void purchasePlanWithMissingPoliticalActionsDefaultsToEmpty() throws Exception {
    String json =
        "{\"kind\":\"purchase\",\"buys\":[{\"unitType\":\"infantry\",\"count\":1}],"
            + "\"repairs\":[],\"placements\":[]}";
    DecisionPlan decoded = mapper.readValue(json, DecisionPlan.class);
    assertInstanceOf(PurchasePlan.class, decoded);
    PurchasePlan back = (PurchasePlan) decoded;
    assertEquals(0, back.politicalActions().size());
  }

  @Test
  void purchasePlanRoundTripsWithCombatMoves() throws Exception {
    PurchasePlan plan =
        new PurchasePlan(
            List.of(new PurchaseOrder("infantry", 2, "Germany")),
            List.of(),
            List.of(),
            List.of(),
            List.of(
                new WireMoveDescription(
                    List.of("u1"),
                    "Germany",
                    "Poland",
                    Map.of(),
                    Map.of("u1", new WireUnitClassification(false, false)),
                    Map.of()),
                new WireMoveDescription(
                    List.of("u2"),
                    "Sea Zone 5",
                    "Sea Zone 6",
                    Map.of(),
                    Map.of("u2", new WireUnitClassification(false, true)),
                    Map.of())));
    String json = mapper.writeValueAsString((DecisionPlan) plan);
    assertTrue(json.contains("\"combatMoves\""));
    assertTrue(json.contains("\"Germany\""));
    DecisionPlan decoded = mapper.readValue(json, DecisionPlan.class);
    assertInstanceOf(PurchasePlan.class, decoded);
    PurchasePlan back = (PurchasePlan) decoded;
    assertEquals(2, back.combatMoves().size());
    assertEquals("Germany", back.combatMoves().get(0).from());
    assertEquals("Poland", back.combatMoves().get(0).to());
    assertEquals(List.of("u1"), back.combatMoves().get(0).unitIds());
  }

  @Test
  void purchasePlanWithMissingCombatMovesDefaultsToEmpty() throws Exception {
    String json =
        "{\"kind\":\"purchase\",\"buys\":[{\"unitType\":\"infantry\",\"count\":1}],"
            + "\"repairs\":[],\"placements\":[],\"politicalActions\":[]}";
    DecisionPlan decoded = mapper.readValue(json, DecisionPlan.class);
    assertInstanceOf(PurchasePlan.class, decoded);
    PurchasePlan back = (PurchasePlan) decoded;
    assertEquals(0, back.combatMoves().size());
  }
}
