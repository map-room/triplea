package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Per-unit classification shipped on each {@link WireMoveDescription} so the bot's move-translator
 * does not need to cross-reference the WireState for every unit.
 *
 * <p>Computed from {@link games.strategy.triplea.attachments.UnitAttachment}: {@code isAir} is
 * {@code attachment.isAir()} and {@code isTransport} is {@code attachment.getTransportCapacity() >
 * 0}.
 */
public record WireUnitClassification(boolean isAir, boolean isTransport) {

  @JsonCreator
  public WireUnitClassification(
      @JsonProperty("isAir") final boolean isAir,
      @JsonProperty("isTransport") final boolean isTransport) {
    this.isAir = isAir;
    this.isTransport = isTransport;
  }
}
