package org.triplea.ai.sidecar.dto;

import java.util.List;

/**
 * Response for the {@code place} decision kind.
 *
 * <p>{@code placements} is ordered land-first then sea-first, matching the iteration order of
 * {@code ProPurchaseAi.place()}: land territories are processed before sea territories.
 */
public record PlacePlan(List<PlaceOrder> placements) implements DecisionPlan {
  public String kind() {
    return "place";
  }
}
