package org.triplea.ai.sidecar.dto;

import java.util.List;

/**
 * Response for the {@code noncombat-move} decision kind.
 *
 * <p>{@code moves} contains all move orders produced by
 * {@code ProNonCombatMoveAi.doNonCombatMove}. There are no SBR moves in the noncombat phase —
 * {@code isBombing} is always {@code false} for every captured {@code MoveDescription}.
 *
 * <p>Reuses {@link CombatMoveOrder} for the per-move shape; #1761 will expand the shape later.
 */
public record NoncombatMovePlan(List<CombatMoveOrder> moves) implements DecisionPlan {
  public String kind() {
    return "noncombat-move";
  }
}
