package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
  @JsonSubTypes.Type(value = SelectCasualtiesPlan.class, name = "select-casualties"),
  @JsonSubTypes.Type(value = RetreatPlan.class,          name = "retreat-or-press"),
  @JsonSubTypes.Type(value = ScramblePlan.class,         name = "scramble"),
  @JsonSubTypes.Type(value = PurchasePlan.class,         name = "purchase"),
  @JsonSubTypes.Type(value = CombatMovePlan.class,       name = "combat-move"),
  @JsonSubTypes.Type(value = NoncombatMovePlan.class,    name = "noncombat-move"),
  @JsonSubTypes.Type(value = PlacePlan.class,            name = "place"),
})
public sealed interface DecisionPlan
    permits SelectCasualtiesPlan, RetreatPlan, ScramblePlan, PurchasePlan, CombatMovePlan,
        NoncombatMovePlan, PlacePlan {}
