package org.triplea.ai.sidecar.wire;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Wire serialization of a Map Room player. For British, {@code europePus} and {@code pacificPus}
 * carry the split IPC pools (both are present iff Map Room's {@code G.ukEconomies} is set); {@code
 * pus} remains the combined total for backwards compatibility. Absent for non-British players.
 *
 * <p>For the Chinese player, {@code productionFrontier} names the TripleA {@link
 * games.strategy.engine.data.ProductionFrontier} that should be set before running ProPurchaseAi.
 * Either {@code "productionChinese_Burma_Road_Open"} (Infantry + Artillery) or {@code
 * "productionChinese_Burma_Road_Closed"} (Infantry only). Absent for all other players. The
 * canonical GameData defaults to the Open frontier; without this field the sidecar cannot know the
 * road is closed and always allows Artillery (see map-room#2174).
 */
public record WirePlayer(
    String playerId,
    int pus,
    List<String> tech,
    boolean capitalCaptured,
    @JsonInclude(JsonInclude.Include.NON_NULL) Integer europePus,
    @JsonInclude(JsonInclude.Include.NON_NULL) Integer pacificPus,
    @JsonInclude(JsonInclude.Include.NON_NULL) String productionFrontier) {
  @JsonCreator
  public WirePlayer(
      @JsonProperty("playerId") final String playerId,
      @JsonProperty("pus") final int pus,
      @JsonProperty("tech") final List<String> tech,
      @JsonProperty("capitalCaptured") final boolean capitalCaptured,
      @JsonProperty("europePus") final Integer europePus,
      @JsonProperty("pacificPus") final Integer pacificPus,
      @JsonProperty("productionFrontier") final String productionFrontier) {
    this.playerId = playerId;
    this.pus = pus;
    this.tech = tech == null ? List.of() : tech;
    this.capitalCaptured = capitalCaptured;
    this.europePus = europePus;
    this.pacificPus = pacificPus;
    this.productionFrontier = productionFrontier;
  }

  public WirePlayer(
      final String playerId,
      final int pus,
      final List<String> tech,
      final boolean capitalCaptured) {
    this(playerId, pus, tech, capitalCaptured, null, null, null);
  }

  public WirePlayer(
      final String playerId,
      final int pus,
      final List<String> tech,
      final boolean capitalCaptured,
      final Integer europePus,
      final Integer pacificPus) {
    this(playerId, pus, tech, capitalCaptured, europePus, pacificPus, null);
  }
}
