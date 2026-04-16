package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.triplea.ai.sidecar.wire.WireState;

/**
 * Request for the {@code noncombat-move} decision kind. The sidecar restores
 * {@code storedFactoryMoveMap} and {@code storedPurchaseTerritories} from the session snapshot
 * and calls {@code AbstractProAi#invokeNonCombatMoveForSidecar} to produce the move list.
 */
@JsonIgnoreProperties("kind")
public record NoncombatMoveRequest(WireState state) implements DecisionRequest {
  public String kind() {
    return "noncombat-move";
  }
}
