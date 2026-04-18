package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Response for the {@code combat-move} decision kind.
 *
 * <p>{@code declarations} contains war declarations the AI wants to make during the politics step
 * that precedes combat movement. {@code moves} contains standard (non-bombing) move orders (batches
 * 1–3 from {@code ProCombatMoveAi.doMove}). {@code sbrMoves} contains strategic-bombing-raid
 * orders (batch 4, where {@code AbstractProAi#shouldBomberBomb} returned {@code true} at capture
 * time).
 */
public record CombatMovePlan(
    List<WarDeclaration> declarations,
    List<CombatMoveOrder> moves,
    List<CombatMoveOrder> sbrMoves)
    implements DecisionPlan {

  public String kind() {
    return "combat-move";
  }

  /** Backwards-compat: missing declarations/moves/sbrMoves fields default to empty list. */
  @JsonCreator
  public CombatMovePlan(
      @JsonProperty("declarations") final List<WarDeclaration> declarations,
      @JsonProperty("moves") final List<CombatMoveOrder> moves,
      @JsonProperty("sbrMoves") final List<CombatMoveOrder> sbrMoves) {
    this.declarations = declarations == null ? List.of() : declarations;
    this.moves = moves == null ? List.of() : moves;
    this.sbrMoves = sbrMoves == null ? List.of() : sbrMoves;
  }
}
