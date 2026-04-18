package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * A single unit-movement order projected from a TripleA {@code MoveDescription}.
 *
 * <p>{@code unitIds} are Map Room wire IDs (the string keys from the wire state), not Java UUIDs.
 * {@code from} and {@code to} are territory names as they appear in the canonical map.
 *
 * <p>{@code kind} distinguishes the three move types the Map Room engine handles separately:
 * <ul>
 *   <li>{@code "move"} (default) — plain unit movement via {@code client.moves.moveUnit}.
 *   <li>{@code "load"} — cargo boarding a sea transport; dispatched via
 *       {@code client.moves.loadOnTransport(unitIds, from, seaZone, transportId)}.
 *   <li>{@code "unload"} — cargo leaving a sea transport; dispatched via
 *       {@code client.moves.unloadFromTransport(unitIds, seaZone, to)}.
 * </ul>
 *
 * <p>{@code transportId} is the wire ID of the sea transport involved. Required for {@code "load"};
 * populated but not consumed by the engine for {@code "unload"} (the engine infers the transport
 * from the cargo's {@code transportedBy} field). Absent/null for {@code "move"}.
 *
 * <p>Jackson backwards-compatibility: absent {@code kind} / {@code transportId} fields in the
 * JSON payload default to {@code "move"} / {@code null} respectively, so existing fixtures and
 * older TS clients don't need to be updated.
 */
public record CombatMoveOrder(
    List<String> unitIds,
    String from,
    String to,
    String kind,
    String transportId) {

  @JsonCreator
  public CombatMoveOrder(
      @JsonProperty("unitIds") final List<String> unitIds,
      @JsonProperty("from") final String from,
      @JsonProperty("to") final String to,
      @JsonProperty("kind") final String kind,
      @JsonProperty("transportId") final String transportId) {
    this.unitIds = unitIds;
    this.from = from;
    this.to = to;
    this.kind = kind == null ? "move" : kind;
    this.transportId = transportId;
  }

  /** Convenience constructor for plain moves — {@code kind} defaults to {@code "move"}. */
  public CombatMoveOrder(final List<String> unitIds, final String from, final String to) {
    this(unitIds, from, to, null, null);
  }
}
