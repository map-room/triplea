package org.triplea.ai.sidecar.dto;

/**
 * Error envelope emitted by {@code POST /session/{id}/decision}.
 *
 * <p>Wire shape: {@code {"status":"error","error":"<code>"}}
 */
public record DecisionError(String status, String error) {
  public DecisionError(final String error) {
    this("error", error);
  }
}
