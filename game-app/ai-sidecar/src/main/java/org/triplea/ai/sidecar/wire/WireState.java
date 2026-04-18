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
    List<WireRelationship> relationships) {

  /**
   * Backwards-compat constructor: TS clients shipped before the relationships field exists may omit
   * it. Treat absent field as empty list (not null) so downstream walks are safe.
   */
  @JsonCreator
  public WireState(
      @JsonProperty("territories") final List<WireTerritory> territories,
      @JsonProperty("players") final List<WirePlayer> players,
      @JsonProperty("round") final int round,
      @JsonProperty("phase") final String phase,
      @JsonProperty("currentPlayer") final String currentPlayer,
      @JsonProperty("relationships") final List<WireRelationship> relationships) {
    this.territories = territories;
    this.players = players;
    this.round = round;
    this.phase = phase;
    this.currentPlayer = currentPlayer;
    this.relationships = relationships == null ? List.of() : relationships;
  }
}
