package games.strategy.triplea.ai.pro.data;

import java.util.Map;

/**
 * Top-level snapshot DTO for the three stored ProAi maps that must survive a sidecar turn
 * boundary (purchase → combat-move → noncombat-move → place).
 *
 * <p>Produced by {@code AbstractProAi.snapshotForSidecar()} after the purchase phase and consumed
 * by {@code AbstractProAi.restoreFromSnapshot(ProSessionSnapshot, GameData)} before the first
 * offensive kind that needs stored state. Both maps may be empty (never null) when the
 * corresponding ProAi stored field was null at snapshot time.
 *
 * <p>All territory keys are territory names; all unit references are UUID strings. See
 * {@link ProTerritorySnapshot} and {@link PurchaseTerritorySnapshot} for field-level detail.
 *
 * @param combatMoveMap territory name → {@link ProTerritorySnapshot} for {@code
 *     AbstractProAi.storedCombatMoveMap}
 * @param factoryMoveMap territory name → {@link ProTerritorySnapshot} for {@code
 *     AbstractProAi.storedFactoryMoveMap}
 * @param purchaseTerritories territory name → {@link PurchaseTerritorySnapshot} for {@code
 *     AbstractProAi.storedPurchaseTerritories}
 */
public record ProSessionSnapshot(
    Map<String, ProTerritorySnapshot> combatMoveMap,
    Map<String, ProTerritorySnapshot> factoryMoveMap,
    Map<String, PurchaseTerritorySnapshot> purchaseTerritories) {}
