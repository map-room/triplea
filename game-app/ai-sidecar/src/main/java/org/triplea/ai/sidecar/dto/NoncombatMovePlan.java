package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Response for the {@code noncombat-move} decision kind. */
public record NoncombatMovePlan(List<WireMoveDescription> moves) implements DecisionPlan {

  public String kind() {
    return "noncombat-move";
  }

  @JsonCreator
  public NoncombatMovePlan(@JsonProperty("moves") final List<WireMoveDescription> moves) {
    this.moves = moves == null ? List.of() : moves;
  }
}
