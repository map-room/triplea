package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Response for the {@code combat-move} decision kind.
 *
 * <p>{@code moves} contains standard (non-bombing) move descriptions. {@code sbrMoves} contains
 * strategic-bombing-raid descriptions. Classification into load/unload/move is performed on the TS
 * side by {@code move-translator.ts}.
 */
public record CombatMovePlan(List<WireMoveDescription> moves, List<WireMoveDescription> sbrMoves)
    implements DecisionPlan {

  public String kind() {
    return "combat-move";
  }

  @JsonCreator
  public CombatMovePlan(
      @JsonProperty("moves") final List<WireMoveDescription> moves,
      @JsonProperty("sbrMoves") final List<WireMoveDescription> sbrMoves) {
    this.moves = moves == null ? List.of() : moves;
    this.sbrMoves = sbrMoves == null ? List.of() : sbrMoves;
  }
}
