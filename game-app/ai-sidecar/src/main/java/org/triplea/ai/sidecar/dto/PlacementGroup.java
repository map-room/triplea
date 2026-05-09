package org.triplea.ai.sidecar.dto;

import java.util.List;

/**
 * One batch of units to place in a single territory. Part of {@link PurchasePlan#placements()}.
 *
 * <p>Ordered in the array per ProPurchaseAi.place dispatch order: land non-construction → water
 * non-construction → land construction → water construction. The {@code isWater} / {@code
 * isConstruction} flags are belt-and-braces — the bot can re-sort if needed.
 */
public record PlacementGroup(
    String territory, List<String> unitTypes, boolean isWater, boolean isConstruction) {}
