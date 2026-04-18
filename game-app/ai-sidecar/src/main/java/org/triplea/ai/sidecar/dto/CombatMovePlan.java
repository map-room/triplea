package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Response for the {@code combat-move} decision kind.
 *
 * <p>{@code moves} contains standard (non-bombing) move orders (batches 1–3 from {@code
 * ProCombatMoveAi.doMove}). {@code sbrMoves} contains strategic-bombing-raid orders (batch 4,
 * where {@code AbstractProAi#shouldBomberBomb} returned {@code true} at capture time).
 *
 * <p>War declarations are no longer embedded here — they are returned by the preceding {@code
 * politics} decision kind ({@link PoliticsPlan}).
 */
public record CombatMovePlan(List<CombatMoveOrder> moves, List<CombatMoveOrder> sbrMoves)
    implements DecisionPlan {

  public String kind() {
    return "combat-move";
  }

  /** Backwards-compat: missing moves/sbrMoves fields default to empty list. */
  @JsonCreator
  public CombatMovePlan(
      @JsonProperty("moves") final List<CombatMoveOrder> moves,
      @JsonProperty("sbrMoves") final List<CombatMoveOrder> sbrMoves) {
    this.moves = moves == null ? List.of() : moves;
    this.sbrMoves = sbrMoves == null ? List.of() : sbrMoves;
  }
}
