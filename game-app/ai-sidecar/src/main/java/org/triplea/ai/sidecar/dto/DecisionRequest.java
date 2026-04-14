package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.triplea.ai.sidecar.wire.WireState;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind", visible = true)
@JsonSubTypes({
  @JsonSubTypes.Type(value = SelectCasualtiesRequest.class, name = "select-casualties"),
  @JsonSubTypes.Type(value = RetreatQueryRequest.class, name = "retreat-or-press"),
  @JsonSubTypes.Type(value = ScrambleRequest.class, name = "scramble"),
  @JsonSubTypes.Type(value = OffensiveRequest.class, name = "purchase"),
  @JsonSubTypes.Type(value = OffensiveRequest.class, name = "combat-move"),
  @JsonSubTypes.Type(value = OffensiveRequest.class, name = "noncombat-move"),
  @JsonSubTypes.Type(value = OffensiveRequest.class, name = "place"),
})
public sealed interface DecisionRequest
    permits SelectCasualtiesRequest, RetreatQueryRequest, ScrambleRequest, OffensiveRequest {
  WireState state();
}
