package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
  @JsonSubTypes.Type(value = SelectCasualtiesPlan.class, name = "select-casualties"),
  @JsonSubTypes.Type(value = RetreatPlan.class,          name = "retreat-or-press"),
  @JsonSubTypes.Type(value = ScramblePlan.class,         name = "scramble"),
})
public sealed interface DecisionPlan permits SelectCasualtiesPlan, RetreatPlan, ScramblePlan {}
