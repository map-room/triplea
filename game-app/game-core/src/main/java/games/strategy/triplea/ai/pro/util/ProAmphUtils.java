package games.strategy.triplea.ai.pro.util;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.util.BreadthFirstSearch;
import games.strategy.triplea.attachments.TerritoryAttachment;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.experimental.UtilityClass;

/**
 * Sea-bridging helpers for amphib-enabled value scoring (Phase 2.3).
 *
 * <p>Both methods are gated by the caller ({@link ProTerritoryValueUtils}) on {@code
 * AmphContext.isEnabled()} — callers must not invoke these methods when the amphib toggle is off.
 */
@UtilityClass
public final class ProAmphUtils {

  /** Maximum sea-hop depth searched when scoring amphib reachability. */
  static final int MAX_SEA_HOPS = 4;

  /**
   * Returns the maximum connected land-mass size when sea zones act as one-hop bridges between land
   * territories. Canal-aware.
   *
   * <p>The BFS expands through both land and sea territories; only land territories are counted.
   * Used as the normalisation denominator in {@link ProTerritoryValueUtils#findTerritoryValues} so
   * that amphib value contributions scale consistently across different map sizes.
   */
  public static int findMaxLandMassSizeSeaBridging(final GamePlayer player) {
    final Set<Territory> visitedLand = new HashSet<>();
    int maxSize = 1;

    for (final Territory t : player.getData().getMap().getTerritories()) {
      if (!t.isWater() && !visitedLand.contains(t)) {
        final int[] size = {0};
        new BreadthFirstSearch(
                List.of(t),
                (from, to) -> {
                  if (!to.isWater()) {
                    return ProMatches.territoryCanPotentiallyMoveLandUnits(player).test(to);
                  }
                  // land→sea or sea→sea: allow passable sea zones, canal-aware
                  return ProMatches.territoryCanMoveSeaUnits(player, true).test(to)
                      && ProMatches.noCanalsBetweenTerritories(player).test(from, to);
                })
            .traverse(
                (territory, distance) -> {
                  if (!territory.isWater()) {
                    visitedLand.add(territory);
                    size[0]++;
                  }
                  return true; // no depth limit — full connectivity
                });
        if (size[0] > maxSize) {
          maxSize = size[0];
        }
      }
    }
    return maxSize;
  }

  /**
   * For a staging sea zone, returns the weighted value of enemy land territories reachable by
   * amphib movement from that zone.
   *
   * <p>BFS expands through sea zones (canal-aware, up to {@link #MAX_SEA_HOPS}). For each enemy
   * land territory adjacent to any visited sea zone the value is:
   *
   * <pre>landValue / 2^(seaHops + 1)</pre>
   *
   * where {@code seaHops} is the number of sea-zone hops from {@code seaZone} to the sea zone
   * adjacent to the enemy land territory. The {@code +1} accounts for the final land-unload hop.
   * Each enemy territory is counted at most once (nearest sea zone wins).
   *
   * @param seaZone starting staging sea zone
   * @param player the moving player
   * @param enemyCapitalsAndFactoriesMap pre-computed capital/factory values (from
   *     ProTerritoryValueUtils)
   * @param territoriesThatCantBeHeld territories excluded from the enemy check
   * @param territoriesToAttack territories already committed to attack (excluded from scoring)
   * @return raw weighted sum; caller is responsible for normalisation
   */
  public static double findAmphReachableLandValue(
      final Territory seaZone,
      final GamePlayer player,
      final Map<Territory, Double> enemyCapitalsAndFactoriesMap,
      final List<Territory> territoriesThatCantBeHeld,
      final List<Territory> territoriesToAttack) {

    if (!seaZone.isWater()) {
      return 0.0;
    }

    final double[] totalValue = {0.0};
    final Set<Territory> countedLand = new HashSet<>();

    // BreadthFirstSearch never calls the visitor for the starting territory, so score land
    // adjacent to seaZone itself (seaDistance = 0, formula = val / 2^1) manually.
    for (final Territory adj : player.getData().getMap().getNeighbors(seaZone)) {
      if (!adj.isWater()
          && !countedLand.contains(adj)
          && !territoriesToAttack.contains(adj)
          && ProMatches.territoryIsEnemyOrCantBeHeld(player, territoriesThatCantBeHeld).test(adj)) {
        final double val =
            enemyCapitalsAndFactoriesMap.containsKey(adj)
                ? enemyCapitalsAndFactoriesMap.get(adj)
                : TerritoryAttachment.getProduction(adj);
        if (val > 0) {
          totalValue[0] += val / 2.0;
          countedLand.add(adj);
        }
      }
    }

    new BreadthFirstSearch(
            List.of(seaZone),
            (from, to) ->
                to.isWater()
                    && ProMatches.territoryCanMoveSeaUnits(player, true).test(to)
                    && ProMatches.noCanalsBetweenTerritories(player).test(from, to))
        .traverse(
            (territory, seaDistance) -> {
              // Accumulate enemy land adjacent to this sea zone
              for (final Territory adj : player.getData().getMap().getNeighbors(territory)) {
                if (!adj.isWater()
                    && !countedLand.contains(adj)
                    && !territoriesToAttack.contains(adj)
                    && ProMatches.territoryIsEnemyOrCantBeHeld(player, territoriesThatCantBeHeld)
                        .test(adj)) {
                  final double val =
                      enemyCapitalsAndFactoriesMap.containsKey(adj)
                          ? enemyCapitalsAndFactoriesMap.get(adj)
                          : TerritoryAttachment.getProduction(adj);
                  if (val > 0) {
                    totalValue[0] += val / Math.pow(2, seaDistance + 1);
                    countedLand.add(adj);
                  }
                }
              }
              return seaDistance < MAX_SEA_HOPS;
            });

    return totalValue[0];
  }
}
