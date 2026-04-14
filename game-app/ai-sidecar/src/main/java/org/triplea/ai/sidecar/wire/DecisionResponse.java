package org.triplea.ai.sidecar.wire;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DecisionResponse(String status, String jobId, JsonNode plan, String error) {
  public static DecisionResponse pending(final String jobId) {
    return new DecisionResponse("pending", jobId, null, null);
  }

  public static DecisionResponse ready(final JsonNode plan) {
    return new DecisionResponse("ready", null, plan, null);
  }

  public static DecisionResponse error(final String message) {
    return new DecisionResponse("error", null, null, message);
  }
}
