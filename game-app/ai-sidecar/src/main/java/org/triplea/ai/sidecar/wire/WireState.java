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
    String gameDataKey) {

  /**
   * Backwards-compat constructor: TS clients shipped before the relationships field exists may omit
   * it. Treat absent field as empty list (not null) so downstream walks are safe.
   *
   * <p>{@code gameDataKey} gets no such default. It selects which {@link
   * org.triplea.ai.sidecar.CanonicalGameData} the request is decided against; an absent
   * relationship list is safe (a smaller graph), but an absent game key would silently select the
   * WRONG MAP and nothing downstream would error. Fail loud instead.
   */
  @JsonCreator
  public WireState(
      @JsonProperty("territories") final List<WireTerritory> territories,
      @JsonProperty("players") final List<WirePlayer> players,
      @JsonProperty("round") final int round,
      @JsonProperty("phase") final String phase,
      @JsonProperty("currentPlayer") final String currentPlayer,
      @JsonProperty("relationships") final List<WireRelationship> relationships,
      @JsonProperty("gameDataKey") final String gameDataKey) {
    this.territories = territories;
    this.players = players;
    this.round = round;
    this.phase = phase;
    this.currentPlayer = currentPlayer;
    this.relationships = relationships == null ? List.of() : relationships;
    if (gameDataKey == null || gameDataKey.isBlank()) {
      throw new IllegalArgumentException("WireState.gameDataKey is required");
    }
    this.gameDataKey = gameDataKey;
  }
}
