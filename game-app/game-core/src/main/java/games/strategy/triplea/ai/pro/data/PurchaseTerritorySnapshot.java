package games.strategy.triplea.ai.pro.data;

import java.util.List;

/**
 * Snapshot DTO for the one {@link ProPurchaseTerritory} field that must survive a sidecar purchase
 * → place turn boundary.
 *
 * <p>The territory key itself is the map key in {@link ProSessionSnapshot#purchaseTerritories()};
 * only {@code canPlaceTerritories} is encoded here.
 *
 * @param canPlaceTerritories snapshots of the placement sub-territories for this production
 *     territory ({@code ProPurchaseTerritory.canPlaceTerritories})
 */
public record PurchaseTerritorySnapshot(List<PlaceTerritorySnapshot> canPlaceTerritories) {}
