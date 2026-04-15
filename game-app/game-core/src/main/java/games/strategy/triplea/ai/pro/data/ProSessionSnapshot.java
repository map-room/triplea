package games.strategy.triplea.ai.pro.data;

import java.util.Map;

/**
 * Top-level snapshot DTO for the three stored ProAi maps that must survive a sidecar turn
 * boundary (purchase → combat-move → noncombat-move → place).
 *
 * <p>Produced by {@code AbstractProAi.snapshotForSidecar()} after the purchase phase and consumed
 * by the per-kind restore methods ({@code AbstractProAi.restoreCombatMoveMap},
 * {@code restoreFactoryMoveMap}, {@code restorePurchaseTerritories}) before each offensive phase.
 * Maps may be empty (never null) when the corresponding stored field was null at snapshot time.
 *
 * <p>All territory keys are territory names; all unit references are UUID strings. The
 * {@code unitIdMap} field is the sidecar's Map-Room-wire-ID → Java-UUID mapping captured at
 * purchase time — it must be pre-populated into the session's live {@code unitIdMap} before
 * {@code WireStateApplier.apply()} runs in each subsequent executor, so that the same wire unit
 * IDs resolve to the same UUIDs that appear in the territory snapshots.
 *
 * @param combatMoveMap territory name → {@link ProTerritorySnapshot} for {@code
 *     AbstractProAi.storedCombatMoveMap}
 * @param factoryMoveMap territory name → {@link ProTerritorySnapshot} for {@code
 *     AbstractProAi.storedFactoryMoveMap}
 * @param purchaseTerritories territory name → {@link PurchaseTerritorySnapshot} for {@code
 *     AbstractProAi.storedPurchaseTerritories}
 * @param unitIdMap wire unit ID (Map Room string) → Java UUID string; must be applied to the
 *     session's live {@code ConcurrentMap<String, UUID>} before {@code WireStateApplier.apply()}
 *     so that UUID references in the territory snapshots remain resolvable after a process restart
 */
public record ProSessionSnapshot(
    Map<String, ProTerritorySnapshot> combatMoveMap,
    Map<String, ProTerritorySnapshot> factoryMoveMap,
    Map<String, PurchaseTerritorySnapshot> purchaseTerritories,
    Map<String, String> unitIdMap) {}
