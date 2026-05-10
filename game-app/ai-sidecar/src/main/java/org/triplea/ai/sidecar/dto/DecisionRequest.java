package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.triplea.ai.sidecar.wire.WireState;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind", visible = true)
@JsonSubTypes({
  @JsonSubTypes.Type(value = PurchaseRequest.class, name = "purchase"),
  @JsonSubTypes.Type(value = NoncombatMoveRequest.class, name = "noncombat-move"),
})
public sealed interface DecisionRequest
    permits PurchaseRequest, NoncombatMoveRequest, OtherOffensiveRequest {
  WireState state();
}
