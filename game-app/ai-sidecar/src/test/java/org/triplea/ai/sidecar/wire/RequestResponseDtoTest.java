package org.triplea.ai.sidecar.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class RequestResponseDtoTest {
  private final ObjectMapper om = new ObjectMapper();

  /**
   * map-room#2305 — {@code WirePlayer.purchasedUnits} carries the TS engine's {@code
   * player.purchasedUnits[]} ledger over the wire. The POJO is in place so additive emission of
   * this field by the TS bot does not trip Jackson's {@code FAIL_ON_UNKNOWN_PROPERTIES}; the
   * sidecar's executors will consume it in a follow-up change (map-room#2280 follow-up) to clamp
   * the place plan against the engine's actual ledger.
   */
  @Test
  void wirePlayerPurchasedUnitsDeserializes() throws Exception {
    final String body =
        "{\"playerId\":\"Germans\",\"pus\":24,\"tech\":[],\"capitalCaptured\":false,"
            + "\"purchasedUnits\":[{\"type\":\"infantry\",\"count\":3},"
            + "{\"type\":\"tank\",\"count\":1}]}";
    final WirePlayer p = om.readValue(body, WirePlayer.class);
    assertEquals("Germans", p.playerId());
    assertEquals(24, p.pus());
    assertEquals(2, p.purchasedUnits().size());
    assertEquals("infantry", p.purchasedUnits().get(0).type());
    assertEquals(3, p.purchasedUnits().get(0).count());
    assertEquals("tank", p.purchasedUnits().get(1).type());
    assertEquals(1, p.purchasedUnits().get(1).count());
  }

  /**
   * map-room#2305 — when {@code purchasedUnits} is absent (pre-emission, default), the field
   * deserializes to null and {@code @JsonInclude(NON_NULL)} ensures it's not echoed on serialize.
   * Backward-compat with WirePlayer constructors that don't accept the field.
   */
  @Test
  void wirePlayerWithoutPurchasedUnitsRoundTrips() throws Exception {
    final WirePlayer p = new WirePlayer("Germans", 42, java.util.List.of(), false);
    final String json = om.writeValueAsString(p);
    assertTrue(json.contains("\"playerId\":\"Germans\""));
    assertTrue(!json.contains("purchasedUnits"));
    final WirePlayer parsed = om.readValue(json, WirePlayer.class);
    assertEquals("Germans", parsed.playerId());
    assertEquals(null, parsed.purchasedUnits());
  }
}
