package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.triplea.ai.sidecar.wire.WireState;

/**
 * Request for the {@code purchase} decision kind. Phase 3 is the first offensive kind wired
 * end-to-end; sidecar hydrates a cached {@code GameData} from {@code state} and invokes {@code
 * AbstractProAi#purchase}.
 */
@JsonIgnoreProperties("kind")
public record PurchaseRequest(WireState state) implements DecisionRequest {
  public String kind() {
    return "purchase";
  }
}
