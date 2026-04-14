package org.triplea.ai.sidecar.wire;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record WireUnit(
    String unitId, String unitType, int hitsTaken, int movesUsed) {
  @JsonCreator
  public static WireUnit of(
      @JsonProperty("unitId") final String unitId,
      @JsonProperty("unitType") final String unitType,
      @JsonProperty("hitsTaken") final Integer hitsTaken,
      @JsonProperty("movesUsed") final Integer movesUsed) {
    return new WireUnit(
        unitId, unitType, hitsTaken == null ? 0 : hitsTaken, movesUsed == null ? 0 : movesUsed);
  }
}
