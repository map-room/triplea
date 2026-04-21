package org.triplea.ai.sidecar.exec;

import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Unit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.triplea.ai.sidecar.dto.WireMoveDescription;
import org.triplea.ai.sidecar.dto.WireUnitClassification;

/**
 * Converts a captured {@link MoveDescription} into a {@link WireMoveDescription} for shipment to
 * the bot. The bot's move-translator performs classification (load/unload/move) — this builder
 * merely carries raw MD data across the wire plus per-unit {isAir, isTransport} classification.
 *
 * <p>Two sidecar-side invariants are enforced here:
 *
 * <ul>
 *   <li>Air units never appear as keys in {@code cargoToTransport}. TripleA's internal
 *       representation of carrier-fighter pairs reuses this map; we filter them out.
 *   <li>{@code airTransportsDependents} is always empty — paratroopers are unsupported.
 * </ul>
 */
public final class WireMoveDescriptionBuilder {
  private WireMoveDescriptionBuilder() {}

  public static WireMoveDescription build(
      final MoveDescription md, final Map<UUID, String> uuidToWireId) {
    final List<String> unitIds =
        md.getUnits().stream()
            .map(u -> uuidToWireId.get(u.getId()))
            .filter(Objects::nonNull)
            .toList();

    final Map<String, String> cargoToTransport = new LinkedHashMap<>();
    for (final Map.Entry<Unit, Unit> e : md.getUnitsToSeaTransports().entrySet()) {
      final Unit cargo = e.getKey();
      final Unit transport = e.getValue();
      if (cargo.getUnitAttachment().isAir()) {
        continue; // carrier-fighter pair; fighter flies, not cargo
      }
      final String cargoId = uuidToWireId.get(cargo.getId());
      final String transportId = uuidToWireId.get(transport.getId());
      if (cargoId == null || transportId == null) continue;
      cargoToTransport.put(cargoId, transportId);
    }

    final Map<String, WireUnitClassification> classifications = new LinkedHashMap<>();
    for (final Unit u : md.getUnits()) {
      final String wireId = uuidToWireId.get(u.getId());
      if (wireId == null) continue;
      classifications.put(wireId, classify(u));
    }
    for (final Unit transport : md.getUnitsToSeaTransports().values()) {
      final String wireId = uuidToWireId.get(transport.getId());
      if (wireId == null || classifications.containsKey(wireId)) continue;
      classifications.put(wireId, classify(transport));
    }

    return new WireMoveDescription(
        unitIds,
        md.getRoute().getStart().getName(),
        md.getRoute().getEnd().getName(),
        cargoToTransport,
        classifications,
        Map.of());
  }

  private static WireUnitClassification classify(final Unit u) {
    // Bombers have transportCapacity > 0 (paratroop XML field) but are air units,
    // not sea transports. isTransport must be false for air to prevent the translator
    // from splitting a mixed-air MD into two overlapping moveUnit dispatches (#1908).
    return new WireUnitClassification(
        u.getUnitAttachment().isAir(),
        u.getUnitAttachment().getTransportCapacity() > 0 && !u.getUnitAttachment().isAir());
  }
}
