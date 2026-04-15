package org.triplea.ai.sidecar.dto;

/**
 * Success envelope emitted by {@code POST /session/{id}/decision}.
 *
 * <p>Wire shape: {@code {"status":"ready","plan":{...}}}
 *
 * <p>The {@code plan} field is typed as the sealed {@link DecisionPlan} interface so that Jackson
 * emits the {@code kind} discriminator automatically via the {@code @JsonTypeInfo} annotation on
 * that interface.
 */
public record DecisionReady(String status, DecisionPlan plan) {
  public DecisionReady(final DecisionPlan plan) {
    this("ready", plan);
  }
}
