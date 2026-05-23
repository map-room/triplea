package games.strategy.triplea.ai.pro.util;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.util.BreadthFirstSearch;
import games.strategy.triplea.ai.pro.data.AmphContext;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.Matches;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import lombok.experimental.UtilityClass;

/**
 * Sea-bridging helpers for amphib-enabled value scoring (Phase 2.3) and embark route planning
 * (Phase 2.5).
 *
 * <p>Value-scoring methods are gated by the caller ({@link ProTerritoryValueUtils}) on {@code
 * AmphContext.isEnabled()} — callers must not invoke these methods when the amphib toggle is off.
 */
@UtilityClass
public final class ProAmphUtils {

  /** Maximum sea-hop depth searched when scoring amphib reachability. */
  static final int MAX_SEA_HOPS = 4;

  /**
   * Naval movement allowance for embarked land units under the no-transports amphib rule.
   *
   * <p>Mirrors {@code NAVAL_MOVE = 2} in {@code tech-effects.ts:6}. An embarked land unit gets
   * exactly 2 sea-hop transits per turn (+ 1 if starting adjacent to a friendly naval base). This
   * budget is independent of the unit's land-movement value.
   */
  public static final int NAVAL_MOVE = 2;

  /**
   * Survival-probability threshold below which a staging sea zone is considered too dangerous for
   * transit or continued staging.
   *
   * <p>A sea zone with {@code survivalProbability(stack, sz) < DISEMBARK_SURVIVAL_THRESHOLD} will
   * either trigger a retreat to a friendly coast (if one is adjacent and available) or be rejected
   * as a destination during planning. Single named constant so callers can tune it without hunting
   * for magic numbers.
   *
   * <p>Design §5.2-1: "P ≥ 0.70 → progress/stay; P < 0.70 → retreat."
   */
  public static final double DISEMBARK_SURVIVAL_THRESHOLD = 0.70;

  /**
   * Estimates the probability that {@code embarkedStack} survives a single-turn enemy attack on the
   * staging sea zone {@code sz}.
   *
   * <p>The implementation converts the existing {@link ProBattleUtils#estimateStrengthDifference}
   * metric to a probability in {@code [0, 1]}:
   *
   * <pre>  P = clamp(1.0 − sd / 100.0, 0.0, 1.0)</pre>
   *
   * where {@code sd = estimateStrengthDifference(sz, attackers, defenders)}.
   *
   * <ul>
   *   <li>{@code sd = 0} (no attack threat) → P = 1.0.
   *   <li>{@code sd = 50} (balanced) → P = 0.50.
   *   <li>{@code sd ≥ 100} (overwhelming attack) → P = 0.0.
   *   <li>{@code sd = 30} → P = 0.70 (right at the threshold).
   * </ul>
   *
   * <p><b>Attacker scope:</b> all enemy units in {@code sz} itself and all enemy units in adjacent
   * territories (sea zones for naval threats, land territories for air threats). This is a
   * single-turn, static-disposition estimate — it does not model multi-turn enemy manoeuvring.
   *
   * <p><b>Defender scope:</b> all units in {@code embarkedStack} plus any friendly sea units
   * already present in {@code sz} (escorts).
   *
   * @param embarkedStack the land units staged at or destined for {@code sz}
   * @param sz the staging sea zone to evaluate
   * @param player the moving player
   * @return survival probability in {@code [0.0, 1.0]}; 1.0 when no enemy threat is reachable
   */
  public static double survivalProbability(
      final java.util.Collection<Unit> embarkedStack, final Territory sz, final GamePlayer player) {

    // Defenders: the embarked stack + any friendly sea units already present as escorts
    final List<Unit> defenders = new ArrayList<>(embarkedStack);
    defenders.addAll(sz.getMatches(Matches.isUnitAllied(player).and(Matches.unitIsSea())));

    // Attackers: enemy units in sz itself + adjacent territories (naval + air threats)
    final List<Unit> attackers = new ArrayList<>();
    attackers.addAll(sz.getMatches(ProMatches.unitIsEnemyNotNeutral(player)));
    for (final Territory adj : player.getData().getMap().getNeighbors(sz)) {
      attackers.addAll(adj.getMatches(ProMatches.unitIsEnemyNotNeutral(player)));
    }

    if (attackers.isEmpty()) {
      return 1.0; // No enemy threat
    }

    final double sd = ProBattleUtils.estimateStrengthDifference(sz, attackers, defenders);
    return Math.max(0.0, Math.min(1.0, 1.0 - sd / 100.0));
  }

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

  /**
   * Returns the full set of destinations reachable by a land unit under the amphib-no-transports
   * movement model, as a direct Java mirror of the TS engine's {@code
   * getValidDestinationsForSingle} (movement-validator.ts).
   *
   * <p>The primitive is <em>reachability-only</em> — it does not score destinations and does not
   * apply the survival-probability threshold. Callers (NCM pass, CM pass) apply survival gating and
   * scoring on the returned set.
   *
   * <h2>Branches (match TS engine §3.2)</h2>
   *
   * <ul>
   *   <li><b>(A) Non-embarked NCM:</b> adjacent sea zones (free embark, 0 budget) plus all sea
   *       zones reachable within {@link #NAVAL_MOVE} sea-hop transits from any adjacent SZ (mirror
   *       of {@code addEmbarkTransitTargets}). No land disembark — fresh embark and disembark in
   *       the same turn is not permitted.
   *   <li><b>(B) Embarked NCM:</b> sea zones reachable within {@code NAVAL_MOVE} hops from the
   *       current staging SZ (transit) plus adjacent friendly coasts if {@code !embarkedThisTurn}
   *       (mirror of {@code addDirectDisembarkTargets}).
   *   <li><b>(C) Embarked CM:</b> adjacent hostile coasts only, provided {@code !embarkedThisTurn}
   *       and the unit has an attack value ≥ 1. AA / attack-0 units return empty (mirror of {@code
   *       getEmbarkCombatDestinations}).
   *   <li><b>(D) Non-embarked CM:</b> empty — no amphib in CM for non-embarked units.
   * </ul>
   *
   * <p>Guards: returns empty when (a) {@code amphContext.enabled == false}, (b) the unit has
   * already moved this turn ({@code unit.hasMoved()} — mirrors TS {@code movedThisTurn > 0} guard),
   * or (c) applicable per-branch conditions above.
   *
   * <p>All sea-BFS hops apply the canal guard ({@link ProMatches#noCanalsBetweenTerritories}) and
   * skip contested sea zones (any enemy sea unit present).
   *
   * @param unit the land unit to plan for
   * @param from the unit's current territory
   * @param isCombatMove {@code true} if this is the combat-move phase
   * @param amphContext current AmphContext (must be enabled)
   * @param player the moving player
   * @return map of destination territory → proposable {from, to} route; empty if no amphib
   *     destinations are reachable
   */
  public static Map<Territory, Route> getAmphReachableTerritories(
      final Unit unit,
      final Territory from,
      final boolean isCombatMove,
      final AmphContext amphContext,
      final GamePlayer player) {

    // Guard: toggle must be on
    if (!amphContext.isEnabled()) {
      return Map.of();
    }

    final boolean embarked = amphContext.isEmbarked(unit);
    final boolean embarkedThisTurn = amphContext.isEmbarkedThisTurn(unit);

    // (D) non-embarked CM: no amphib destinations
    if (isCombatMove && !embarked) {
      return Map.of();
    }

    // (C) embarked CM: adjacent hostile coasts, !embarkedThisTurn, attack>0 only
    if (isCombatMove) {
      if (embarkedThisTurn) {
        return Map.of();
      }
      // AA / attack-0 units cannot assault
      if (unit.getUnitAttachment().getAttack(player) == 0) {
        return Map.of();
      }
      final Map<Territory, Route> result = new LinkedHashMap<>();
      for (final Territory adj : player.getData().getMap().getNeighbors(from)) {
        if (!adj.isWater()
            && ProMatches.territoryIsEnemyOrCantBeHeld(player, List.of()).test(adj)) {
          result.put(adj, new Route(from, adj));
        }
      }
      return result;
    }

    // NCM from here — hasMoved guard (mirrors TS :1473)
    if (unit.hasMoved()) {
      return Map.of();
    }

    // (A) non-embarked NCM: sea-zone embark destinations
    if (!embarked) {
      return buildNonEmbarkedNcmDestinations(from, player);
    }

    // (B) embarked NCM: transit + optional friendly-coast disembark
    return buildEmbarkedNcmDestinations(from, embarkedThisTurn, player);
  }

  /**
   * Case (A): non-embarked land unit, NCM. Returns reachable sea zones.
   *
   * <p>The embark hop (land → adjacent SZ) is free (0 budget). Each adjacent SZ seeds a sea BFS
   * with the {@link #NAVAL_MOVE} budget. Contested SZs (enemy sea units) and canal-blocked hops are
   * excluded.
   */
  private static Map<Territory, Route> buildNonEmbarkedNcmDestinations(
      final Territory landFrom, final GamePlayer player) {

    final Map<Territory, Route> result = new LinkedHashMap<>();
    // Hops consumed so far in the sea BFS; keyed by SZ
    final Map<Territory, Integer> seaHopsUsed = new HashMap<>();
    final Queue<Map.Entry<Territory, List<Territory>>> queue = new ArrayDeque<>();

    // Seed: adjacent sea zones — embark is free (0 sea hops consumed)
    for (final Territory adjSz : player.getData().getMap().getNeighbors(landFrom)) {
      if (!adjSz.isWater()) {
        continue;
      }
      if (adjSz.anyUnitsMatch(Matches.enemyUnit(player).and(Matches.unitIsSea()))) {
        continue;
      }
      final List<Territory> path = new ArrayList<>(List.of(landFrom, adjSz));
      result.put(adjSz, new Route(path));
      seaHopsUsed.put(adjSz, 0); // 0 sea-transit hops used (embark is free)
      queue.add(Map.entry(adjSz, path));
    }

    // Sea-transit BFS up to NAVAL_MOVE hops from each adjacent SZ
    while (!queue.isEmpty()) {
      final Map.Entry<Territory, List<Territory>> entry = queue.poll();
      final Territory current = entry.getKey();
      final List<Territory> currentPath = entry.getValue();
      final int hops = seaHopsUsed.get(current);
      if (hops >= NAVAL_MOVE) {
        continue;
      }
      for (final Territory next : player.getData().getMap().getNeighbors(current)) {
        if (!next.isWater()) {
          continue;
        }
        if (result.containsKey(next)) {
          continue;
        }
        if (next.anyUnitsMatch(Matches.enemyUnit(player).and(Matches.unitIsSea()))) {
          continue;
        }
        if (!ProMatches.noCanalsBetweenTerritories(player).test(current, next)) {
          continue;
        }
        final List<Territory> newPath = new ArrayList<>(currentPath);
        newPath.add(next);
        result.put(next, new Route(newPath));
        seaHopsUsed.put(next, hops + 1);
        queue.add(Map.entry(next, newPath));
      }
    }
    return result;
  }

  /**
   * Case (B): already-embarked land unit, NCM. Returns sea-zone transit destinations and, if {@code
   * !embarkedThisTurn}, adjacent friendly coasts for disembark.
   *
   * <p>Sea transit uses the {@link #NAVAL_MOVE} budget from the staging SZ. Friendly-coast
   * disembark is exactly 1-hop (adjacent), gated by {@code !embarkedThisTurn} (mirrors TS {@code
   * canEmbarkedUnitDisembark :286}).
   */
  private static Map<Territory, Route> buildEmbarkedNcmDestinations(
      final Territory stagingSz, final boolean embarkedThisTurn, final GamePlayer player) {

    final Map<Territory, Route> result = new LinkedHashMap<>();
    // visitedSz tracks sea zones we've already processed — includes stagingSz so the BFS
    // does not loop back and try to add the starting zone as a destination.
    final Set<Territory> visitedSz = new HashSet<>();
    visitedSz.add(stagingSz);
    final Map<Territory, Integer> seaHopsUsed = new HashMap<>();
    final Queue<Map.Entry<Territory, List<Territory>>> queue = new ArrayDeque<>();

    // Friendly-coast disembark (1-hop, !embarkedThisTurn guard)
    if (!embarkedThisTurn) {
      for (final Territory adj : player.getData().getMap().getNeighbors(stagingSz)) {
        if (!adj.isWater() && Matches.isTerritoryFriendly(player).test(adj)) {
          result.put(adj, new Route(stagingSz, adj));
        }
      }
    }

    // Sea-transit BFS from staging SZ, up to NAVAL_MOVE hops
    // Seed: adjacent sea zones (1 hop each)
    for (final Territory adjSz : player.getData().getMap().getNeighbors(stagingSz)) {
      if (!adjSz.isWater()) {
        continue;
      }
      if (visitedSz.contains(adjSz)) {
        continue;
      }
      if (adjSz.anyUnitsMatch(Matches.enemyUnit(player).and(Matches.unitIsSea()))) {
        continue;
      }
      if (!ProMatches.noCanalsBetweenTerritories(player).test(stagingSz, adjSz)) {
        continue;
      }
      result.put(adjSz, new Route(stagingSz, adjSz));
      visitedSz.add(adjSz);
      seaHopsUsed.put(adjSz, 1);
      queue.add(Map.entry(adjSz, new ArrayList<>(List.of(stagingSz, adjSz))));
    }

    while (!queue.isEmpty()) {
      final Map.Entry<Territory, List<Territory>> entry = queue.poll();
      final Territory current = entry.getKey();
      final List<Territory> currentPath = entry.getValue();
      final int hops = seaHopsUsed.getOrDefault(current, 1);
      if (hops >= NAVAL_MOVE) {
        continue;
      }
      for (final Territory next : player.getData().getMap().getNeighbors(current)) {
        if (!next.isWater()) {
          continue;
        }
        if (visitedSz.contains(next)) {
          continue;
        }
        if (next.anyUnitsMatch(Matches.enemyUnit(player).and(Matches.unitIsSea()))) {
          continue;
        }
        if (!ProMatches.noCanalsBetweenTerritories(player).test(current, next)) {
          continue;
        }
        final List<Territory> newPath = new ArrayList<>(currentPath);
        newPath.add(next);
        result.put(next, new Route(newPath));
        visitedSz.add(next);
        seaHopsUsed.put(next, hops + 1);
        queue.add(Map.entry(next, newPath));
      }
    }
    return result;
  }

  /**
   * Returns all sea zones reachable from {@code landT} within {@code maxHops} moves, mapped to the
   * route from {@code landT} to each destination.
   *
   * <p>The first hop is the embark step (land → adjacent sea zone); subsequent hops are sea-zone
   * transits. Contested sea zones (any enemy sea unit present) are excluded. Canal-aware.
   *
   * @param landT starting land territory
   * @param player the moving player
   * @param maxHops maximum total hops (embark + transit); must be ≥ 1
   * @return map of reachable sea-zone territory → route from {@code landT}; insertion-ordered by
   *     BFS discovery (nearest first)
   */
  public static Map<Territory, Route> findEmbarkReachableRoutes(
      final Territory landT, final GamePlayer player, final int maxHops) {

    final Map<Territory, Route> routes = new LinkedHashMap<>();
    final Map<Territory, Integer> hopsTo = new HashMap<>();
    // Queue entries: (sea zone reached so far, full path list from landT)
    final Queue<Map.Entry<Territory, List<Territory>>> queue = new ArrayDeque<>();

    // Seed: adjacent sea zones — each costs 1 hop (the embark step)
    for (final Territory sz : player.getData().getMap().getNeighbors(landT)) {
      if (!sz.isWater()) continue;
      if (sz.anyUnitsMatch(Matches.enemyUnit(player).and(Matches.unitIsSea()))) continue;
      final List<Territory> path = new ArrayList<>(List.of(landT, sz));
      routes.put(sz, new Route(path));
      hopsTo.put(sz, 1);
      queue.add(Map.entry(sz, path));
    }

    while (!queue.isEmpty()) {
      final Map.Entry<Territory, List<Territory>> entry = queue.poll();
      final Territory current = entry.getKey();
      final List<Territory> currentPath = entry.getValue();
      final int hops = hopsTo.get(current);
      if (hops >= maxHops) continue;

      for (final Territory next : player.getData().getMap().getNeighbors(current)) {
        if (!next.isWater()) continue;
        if (routes.containsKey(next)) continue;
        if (next.anyUnitsMatch(Matches.enemyUnit(player).and(Matches.unitIsSea()))) continue;
        if (!ProMatches.noCanalsBetweenTerritories(player).test(current, next)) continue;

        final List<Territory> newPath = new ArrayList<>(currentPath);
        newPath.add(next);
        routes.put(next, new Route(newPath));
        hopsTo.put(next, hops + 1);
        queue.add(Map.entry(next, newPath));
      }
    }

    return routes;
  }
}
