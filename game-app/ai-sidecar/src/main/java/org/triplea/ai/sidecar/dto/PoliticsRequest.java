package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.triplea.ai.sidecar.wire.WireState;

/**
 * Request for the {@code politics} decision kind. The bot sends this before the {@code combat-move}
 * request to resolve war declarations. The sidecar runs {@link
 * games.strategy.triplea.ai.pro.ProAi#invokePoliticsForSidecar}, captures any attempted war
 * declarations, and returns them as a {@link PoliticsPlan}. The bot then dispatches {@code
 * declareWar} moves to Map Room's engine (cascade runs server-side), fetches a fresh post-war
 * WireState, and sends it in the subsequent {@code combat-move} request.
 */
@JsonIgnoreProperties("kind")
public record PoliticsRequest(WireState state) implements DecisionRequest {
  public String kind() {
    return "politics";
  }
}
