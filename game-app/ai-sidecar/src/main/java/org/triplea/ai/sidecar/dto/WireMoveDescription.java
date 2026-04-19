package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Raw ProAi {@link games.strategy.engine.data.MoveDescription} shipped to the bot. Classification
 * (load/unload/move) happens on the TS side in {@code move-translator.ts}.
 *
 * <p>{@code cargoToTransport} is a sparse map (empty for non-load MDs). Keys are wire IDs of cargo
 * units; values are wire IDs of transports. Air units MUST NOT appear as keys — filtered at build
 * time in {@link org.triplea.ai.sidecar.exec.WireMoveDescriptionBuilder}.
 *
 * <p>{@code airTransportsDependents} is reserved for paratroopers, which are unsupported. Always
 * serialised as empty.
 */
public record WireMoveDescription(
    List<String> unitIds,
    String from,
    String to,
    Map<String, String> cargoToTransport,
    Map<String, WireUnitClassification> classifications,
    Map<String, List<String>> airTransportsDependents) {

  @JsonCreator
  public WireMoveDescription(
      @JsonProperty("unitIds") final List<String> unitIds,
      @JsonProperty("from") final String from,
      @JsonProperty("to") final String to,
      @JsonProperty("cargoToTransport") final Map<String, String> cargoToTransport,
      @JsonProperty("classifications") final Map<String, WireUnitClassification> classifications,
      @JsonProperty("airTransportsDependents")
          final Map<String, List<String>> airTransportsDependents) {
    this.unitIds = unitIds == null ? List.of() : unitIds;
    this.from = from;
    this.to = to;
    this.cargoToTransport = cargoToTransport == null ? Map.of() : cargoToTransport;
    this.classifications = classifications == null ? Map.of() : classifications;
    this.airTransportsDependents =
        airTransportsDependents == null ? Map.of() : airTransportsDependents;
  }
}
