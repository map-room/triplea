package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.triplea.ai.sidecar.wire.WireState;

/**
 * Request for the {@code place} decision kind. The sidecar restores
 * {@code storedPurchaseTerritories} from the session snapshot and calls
 * {@code AbstractProAi#invokePlaceForSidecar} to produce the placement list.
 */
@JsonIgnoreProperties("kind")
public record PlaceRequest(WireState state) implements DecisionRequest {
  public String kind() {
    return "place";
  }
}
