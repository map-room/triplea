package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One entry of ProAi's {@code storedStrafeRetreats}: fight {@code rounds} rounds at {@code
 * territory}, then retreat once {@code battle.round >= rounds}. {@code territory} is the engine
 * territory name; the map-room worker applies the rule at retreat time (there is no retreat RPC).
 */
public record StrafeRetreat(String territory, int rounds) {
  @JsonCreator
  public StrafeRetreat(
      @JsonProperty("territory") final String territory, @JsonProperty("rounds") final int rounds) {
    this.territory = territory;
    this.rounds = rounds;
  }
}
