package games.strategy.triplea.ai.pro.data;

import java.util.List;

/**
 * Snapshot DTO for the two {@link ProPlaceTerritory} fields that must survive a sidecar purchase →
 * place turn boundary.
 *
 * <p>Units are encoded as unit-type names (Strings) rather than UUIDs because {@code
 * ProPurchaseAi.place()} matches {@code placeUnits} entries by {@code getType().equals()} — UUID
 * identity is never consulted at placement time. This makes the snapshot more resilient to mid-turn
 * session restarts.
 *
 * @param territoryName name of the placement territory ({@code
 *     ProPlaceTerritory.territory.getName()})
 * @param placeUnitTypes unit-type names of units to place ({@code ProPlaceTerritory.placeUnits})
 */
public record PlaceTerritorySnapshot(String territoryName, List<String> placeUnitTypes) {}
