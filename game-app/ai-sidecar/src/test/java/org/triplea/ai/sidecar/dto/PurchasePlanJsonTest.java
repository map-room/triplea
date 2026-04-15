package org.triplea.ai.sidecar.dto;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class PurchasePlanJsonTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void purchasePlanRoundTripsWithKindDiscriminator() throws Exception {
    PurchasePlan plan = new PurchasePlan(
        List.of(
            new PurchaseOrder("infantry", 3, null),
            new PurchaseOrder("armour", 1, null)),
        List.of(new RepairOrder("Germany", "factory_major", 2)));
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
}
