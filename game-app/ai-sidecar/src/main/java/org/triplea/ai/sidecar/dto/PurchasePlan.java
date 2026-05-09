package org.triplea.ai.sidecar.dto;

import java.util.List;

/**
 * Purchase decision plan. {@code buys} is an ordered list of {@link PurchaseOrder} entries, ordered
 * by ProAi priority (Phase 3 reverse-order trim preserves priority). {@code repairs} is the
 * captured repair bundle from the preceding {@code purchaseRepair()} call. {@code placements} lists
 * the intended placement territories in ProPurchaseAi.place dispatch order (land non-construction →
 * water non-construction → land construction → water construction).
 */
public record PurchasePlan(
    List<PurchaseOrder> buys, List<RepairOrder> repairs, List<PlacementGroup> placements)
    implements DecisionPlan {}
