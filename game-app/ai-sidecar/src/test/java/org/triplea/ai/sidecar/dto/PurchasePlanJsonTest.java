package org.triplea.ai.sidecar.dto;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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
}
