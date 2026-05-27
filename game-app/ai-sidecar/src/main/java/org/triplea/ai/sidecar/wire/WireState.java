package org.triplea.ai.sidecar.wire;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import javax.annotation.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WireState(
    List<WireTerritory> territories,
    List<WirePlayer> players,
    int round,
    String phase,
    String currentPlayer,
    List<WireRelationship> relationships,
    @Nullable String setupVariant,
    @Nullable String aiTheaterPriority) {

  /**
   * Backwards-compat constructor: TS clients shipped before fields existed may omit them.
   *
   * <ul>
   *   <li>{@code relationships} absent → empty list (not null) so downstream walks are safe.
   *   <li>{@code setupVariant} absent → null (closes the half-done Phase 0 pass-through gap; the
   *       executor reads it and falls back to {@code "1940"} via Map Room's GameOptions default).
   *   <li>{@code aiTheaterPriority} absent → null. PR-B (map-room#2755) adds this field. The
   *       executor reads it and falls back to env var {@code AI_THEATER_PRIORITY} (debug-only),
   *       then to {@code "KGF"} via {@link
   *       games.strategy.triplea.ai.pro.data.AiTheaterPriority#fromWire}.
   * </ul>
   */
  @JsonCreator
  public WireState(
      @JsonProperty("territories") final List<WireTerritory> territories,
      @JsonProperty("players") final List<WirePlayer> players,
      @JsonProperty("round") final int round,
      @JsonProperty("phase") final String phase,
      @JsonProperty("currentPlayer") final String currentPlayer,
      @JsonProperty("relationships") final List<WireRelationship> relationships,
      @JsonProperty("setupVariant") final @Nullable String setupVariant,
      @JsonProperty("aiTheaterPriority") final @Nullable String aiTheaterPriority) {
    this.territories = territories;
    this.players = players;
    this.round = round;
    this.phase = phase;
    this.currentPlayer = currentPlayer;
    this.relationships = relationships == null ? List.of() : relationships;
    this.setupVariant = setupVariant;
    this.aiTheaterPriority = aiTheaterPriority;
  }

  /**
   * Backwards-compat 6-arg constructor for call sites that haven't been updated to the PR-B 8-arg
   * form. Delegates to the canonical constructor with {@code null} for both new fields, which the
   * executors treat as "absent → use env-fallback / default". Test code in particular relies on
   * this — many fixtures predate the new fields and the test concerns are orthogonal.
   */
  public WireState(
      final List<WireTerritory> territories,
      final List<WirePlayer> players,
      final int round,
      final String phase,
      final String currentPlayer,
      final List<WireRelationship> relationships) {
    this(territories, players, round, phase, currentPlayer, relationships, null, null);
  }
}
