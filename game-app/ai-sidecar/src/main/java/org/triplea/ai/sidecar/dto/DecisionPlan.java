package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
  @JsonSubTypes.Type(value = SelectCasualtiesPlan.class, name = "select-casualties"),
  @JsonSubTypes.Type(value = RetreatPlan.class, name = "retreat-or-press"),
  @JsonSubTypes.Type(value = ScramblePlan.class, name = "scramble"),
  @JsonSubTypes.Type(value = KamikazePlan.class, name = "kamikaze"),
  @JsonSubTypes.Type(value = IgnoreSubsPlan.class, name = "ignore-subs"),
  @JsonSubTypes.Type(value = EngageStayHiddenPlan.class, name = "engage-stay-hidden"),
  @JsonSubTypes.Type(value = InterceptPlan.class, name = "intercept"),
  @JsonSubTypes.Type(value = SubmergePlan.class, name = "submerge"),
  @JsonSubTypes.Type(value = PurchasePlan.class, name = "purchase"),
  @JsonSubTypes.Type(value = PoliticsPlan.class, name = "politics"),
  @JsonSubTypes.Type(value = CombatMovePlan.class, name = "combat-move"),
  @JsonSubTypes.Type(value = NoncombatMovePlan.class, name = "noncombat-move"),
  @JsonSubTypes.Type(value = PlacePlan.class, name = "place"),
})
public sealed interface DecisionPlan
    permits SelectCasualtiesPlan,
        RetreatPlan,
        ScramblePlan,
        KamikazePlan,
        IgnoreSubsPlan,
        EngageStayHiddenPlan,
        InterceptPlan,
        SubmergePlan,
        PurchasePlan,
        PoliticsPlan,
        CombatMovePlan,
        NoncombatMovePlan,
        PlacePlan {}
