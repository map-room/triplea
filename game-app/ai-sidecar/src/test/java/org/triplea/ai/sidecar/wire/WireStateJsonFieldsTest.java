package org.triplea.ai.sidecar.wire;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class WireStateJsonFieldsTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void wireTerritoryConqueredThisTurnDefaultsFalse() throws Exception {
    String json = "{\"territoryId\":\"Egypt\",\"owner\":\"British\",\"units\":[]}";
    WireTerritory t = mapper.readValue(json, WireTerritory.class);
    assertFalse(t.conqueredThisTurn());
  }

  @Test
  void wireTerritoryConqueredThisTurnHonoured() throws Exception {
    String json = "{\"territoryId\":\"Egypt\",\"owner\":\"Germans\",\"units\":[],\"conqueredThisTurn\":true}";
    WireTerritory t = mapper.readValue(json, WireTerritory.class);
    assertTrue(t.conqueredThisTurn());
  }

  @Test
  void wireUnitBombingDamageDefaultsZero() throws Exception {
    String json = "{\"unitId\":\"u1\",\"unitType\":\"factory_major\"}";
    WireUnit u = mapper.readValue(json, WireUnit.class);
    assertEquals(0, u.bombingDamage());
  }

  @Test
  void wireUnitBombingDamageHonoured() throws Exception {
    String json = "{\"unitId\":\"u1\",\"unitType\":\"factory_major\",\"bombingDamage\":3}";
    WireUnit u = mapper.readValue(json, WireUnit.class);
    assertEquals(3, u.bombingDamage());
  }
}
