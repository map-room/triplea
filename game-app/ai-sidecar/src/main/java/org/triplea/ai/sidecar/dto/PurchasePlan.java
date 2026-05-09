package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Purchase decision plan. {@code buys} is an ordered list of {@link PurchaseOrder} entries, ordered
 * by ProAi priority (Phase 3 reverse-order trim preserves priority). {@code repairs} is the
 * captured repair bundle from the preceding {@code purchaseRepair()} call. {@code placements} lists
 * the intended placement territories in ProPurchaseAi.place dispatch order (land non-construction →
 * water non-construction → land construction → water construction). {@code politicalActions} lists
 * war declarations the AI decided to make during purchase simulation; absent or {@code null} in
 * older responses is treated as an empty list on the TS side.
 */
public record PurchasePlan(
    List<PurchaseOrder> buys,
    List<RepairOrder> repairs,
    List<PlacementGroup> placements,
    List<WarDeclaration> politicalActions)
    implements DecisionPlan {

  @JsonCreator
  public PurchasePlan(
      @JsonProperty("buys") final List<PurchaseOrder> buys,
      @JsonProperty("repairs") final List<RepairOrder> repairs,
      @JsonProperty("placements") final List<PlacementGroup> placements,
      @JsonProperty("politicalActions") final List<WarDeclaration> politicalActions) {
    this.buys = buys;
    this.repairs = repairs;
    this.placements = placements;
    this.politicalActions = politicalActions != null ? politicalActions : List.of();
  }

  /** Convenience constructor for callsites that do not populate politicalActions. */
  public PurchasePlan(
      final List<PurchaseOrder> buys,
      final List<RepairOrder> repairs,
      final List<PlacementGroup> placements) {
    this(buys, repairs, placements, List.of());
  }
}
