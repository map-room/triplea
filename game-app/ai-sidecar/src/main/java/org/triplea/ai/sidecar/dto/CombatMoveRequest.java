package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.triplea.ai.sidecar.wire.WireState;

/**
 * Request for the {@code combat-move} decision kind. The sidecar restores the
 * {@code storedCombatMoveMap} from the session snapshot and calls
 * {@code AbstractProAi#invokeCombatMoveForSidecar} to produce the move list.
 */
@JsonIgnoreProperties("kind")
public record CombatMoveRequest(WireState state) implements DecisionRequest {
  public String kind() {
    return "combat-move";
  }
}
