package org.triplea.ai.sidecar.wire;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record WireState(
    List<WireTerritory> territories,
    List<WirePlayer> players,
    int round,
    String phase,
    String currentPlayer,
    List<WireRelationship> relationships,
    boolean amphibiousMovementEnabled) {

  /**
   * Jackson deserializer. Uses a static factory so we can accept {@code @Nullable Boolean} for
   * {@code amphibiousMovementEnabled} and {@code relationships} (absent implies false / empty-list)
   * while keeping the canonical record constructor strictly typed.
   */
  @JsonCreator
  public static WireState of(
      @JsonProperty("territories") final List<WireTerritory> territories,
      @JsonProperty("players") final List<WirePlayer> players,
      @JsonProperty("round") final int round,
      @JsonProperty("phase") final String phase,
      @JsonProperty("currentPlayer") final String currentPlayer,
      @JsonProperty("relationships") final List<WireRelationship> relationships,
      @JsonProperty("amphibiousMovementEnabled") final Boolean amphibiousMovementEnabled) {
    return new WireState(
        territories,
        players,
        round,
        phase,
        currentPlayer,
        relationships == null ? List.of() : relationships,
        amphibiousMovementEnabled != null && amphibiousMovementEnabled);
  }

  /**
   * Backward-compat constructor for callers that pre-date {@code amphibiousMovementEnabled}.
   * Defaults the toggle to {@code false}.
   */
  public WireState(
      final List<WireTerritory> territories,
      final List<WirePlayer> players,
      final int round,
      final String phase,
      final String currentPlayer,
      final List<WireRelationship> relationships) {
    this(territories, players, round, phase, currentPlayer, relationships, false);
  }
}
