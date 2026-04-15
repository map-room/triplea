package org.triplea.ai.sidecar.dto;

import java.util.List;

/** Purchase decision plan. {@code buys} is an ordered list of {@link PurchaseOrder} entries,
 * ordered by ProAi priority (Phase 3 reverse-order trim preserves priority). {@code repairs}
 * is the captured repair bundle from the preceding {@code purchaseRepair()} call. */
public record PurchasePlan(List<PurchaseOrder> buys, List<RepairOrder> repairs)
    implements DecisionPlan {}
