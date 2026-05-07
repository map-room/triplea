package org.triplea.ai.sidecar.wire;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One entry on {@link WirePlayer#purchasedUnits()}: the count of units of a given type purchased by
 * the player but not yet placed (TS-side {@code player.purchasedUnits[]} ledger from {@code
 * MapRoomState}). Carried over the wire to support the sidecar's clamp of {@code
 * storedPurchaseTerritories} against the engine's actual purchase ledger (map-room#2280 follow-up,
 * enabled by map-room#2305).
 *
 * <p>Currently the sidecar's executors do not yet read this field — the POJO is in place so the
 * TS-side wire emission can be re-enabled without tripping {@code FAIL_ON_UNKNOWN_PROPERTIES}. A
 * subsequent change will wire it into {@code PurchaseExecutor} / {@code PlaceExecutor}.
 */
public record WirePurchasedUnit(String type, int count) {
  @JsonCreator
  public WirePurchasedUnit(
      @JsonProperty("type") final String type, @JsonProperty("count") final int count) {
    this.type = type;
    this.count = count;
  }
}
