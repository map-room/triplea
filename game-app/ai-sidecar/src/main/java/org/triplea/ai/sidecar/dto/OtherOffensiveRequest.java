package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.triplea.ai.sidecar.wire.WireState;

/**
 * Catch-all for offensive kinds not yet implemented on the sidecar. Phase 3 wires only {@code
 * purchase}; {@code combat-move}, {@code noncombat-move}, {@code place} land here and return 501.
 */
public record OtherOffensiveRequest(String kind, WireState state) implements DecisionRequest {
  @JsonCreator
  public OtherOffensiveRequest(
      @JsonProperty("kind") String kind, @JsonProperty("state") WireState state) {
    this.kind = kind;
    this.state = state;
  }
}
