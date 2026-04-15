package games.strategy.triplea.ai.pro.data;

import java.util.List;
import java.util.Map;

/**
 * Snapshot DTO for the five {@link ProTerritory} fields that must survive a sidecar purchase →
 * combat-move turn boundary.
 *
 * <p>Each {@link ProTerritory} instance in {@code storedCombatMoveMap} and {@code
 * storedFactoryMoveMap} is projected to one of these records by {@code
 * AbstractProAi.snapshotForSidecar()}.
 *
 * <p>Unit references are encoded as UUID strings. Territory references are encoded as territory
 * names. Type-safe lookup against live {@link games.strategy.engine.data.GameData} is performed
 * by {@code AbstractProAi.restoreFromSnapshot(ProSessionSnapshot, GameData)}.
 *
 * @param unitIds UUIDs of units assigned to this territory ({@code ProTerritory.units})
 * @param bomberIds UUIDs of bomber units assigned to this territory ({@code ProTerritory.bombers})
 * @param amphibAttackMap transport UUID → list of carried unit UUIDs ({@code
 *     ProTerritory.amphibAttackMap})
 * @param transportTerritoryMap transport UUID → originating territory name ({@code
 *     ProTerritory.transportTerritoryMap})
 * @param bombardTerritoryMap bombard-unit UUID → target territory name ({@code
 *     ProTerritory.bombardTerritoryMap})
 */
public record ProTerritorySnapshot(
    List<String> unitIds,
    List<String> bomberIds,
    Map<String, List<String>> amphibAttackMap,
    Map<String, String> transportTerritoryMap,
    Map<String, String> bombardTerritoryMap) {}
