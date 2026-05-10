package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
  @JsonSubTypes.Type(value = PurchasePlan.class, name = "purchase"),
  @JsonSubTypes.Type(value = NoncombatMovePlan.class, name = "noncombat-move"),
})
public sealed interface DecisionPlan permits PurchasePlan, NoncombatMovePlan {}
