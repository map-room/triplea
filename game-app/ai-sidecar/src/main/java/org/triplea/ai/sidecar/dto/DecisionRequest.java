package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.triplea.ai.sidecar.wire.WireState;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind", visible = true)
@JsonSubTypes({
  @JsonSubTypes.Type(value = SelectCasualtiesRequest.class, name = "select-casualties"),
  @JsonSubTypes.Type(value = RetreatQueryRequest.class, name = "retreat-or-press"),
  @JsonSubTypes.Type(value = ScrambleRequest.class, name = "scramble"),
  @JsonSubTypes.Type(value = KamikazeRequest.class, name = "kamikaze"),
  @JsonSubTypes.Type(value = IgnoreSubsRequest.class, name = "ignore-subs"),
  @JsonSubTypes.Type(value = EngageStayHiddenRequest.class, name = "engage-stay-hidden"),
  @JsonSubTypes.Type(value = InterceptRequest.class, name = "intercept"),
  @JsonSubTypes.Type(value = SubmergeRequest.class, name = "submerge"),
  @JsonSubTypes.Type(value = PurchaseRequest.class, name = "purchase"),
  @JsonSubTypes.Type(value = PoliticsRequest.class, name = "politics"),
  @JsonSubTypes.Type(value = CombatMoveRequest.class, name = "combat-move"),
  @JsonSubTypes.Type(value = NoncombatMoveRequest.class, name = "noncombat-move"),
  @JsonSubTypes.Type(value = PlaceRequest.class, name = "place"),
})
public sealed interface DecisionRequest
    permits SelectCasualtiesRequest,
        RetreatQueryRequest,
        ScrambleRequest,
        KamikazeRequest,
        IgnoreSubsRequest,
        EngageStayHiddenRequest,
        InterceptRequest,
        SubmergeRequest,
        PurchaseRequest,
        PoliticsRequest,
        CombatMoveRequest,
        NoncombatMoveRequest,
        PlaceRequest,
        OtherOffensiveRequest {
  WireState state();
}
