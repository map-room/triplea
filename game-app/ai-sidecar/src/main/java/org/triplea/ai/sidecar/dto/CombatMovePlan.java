package org.triplea.ai.sidecar.dto;

import java.util.List;

/**
 * Response for the {@code combat-move} decision kind.
 *
 * <p>{@code moves} contains standard (non-bombing) move orders (batches 1–3 from
 * {@code ProCombatMoveAi.doMove}). {@code sbrMoves} contains strategic-bombing-raid orders (batch
 * 4, where {@code AbstractProAi#shouldBomberBomb} returned {@code true} at capture time).
 */
public record CombatMovePlan(List<CombatMoveOrder> moves, List<CombatMoveOrder> sbrMoves)
    implements DecisionPlan {
  public String kind() {
    return "combat-move";
  }
}
