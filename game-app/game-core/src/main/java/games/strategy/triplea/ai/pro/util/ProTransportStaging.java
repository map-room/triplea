package games.strategy.triplea.ai.pro.util;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.move.validation.MoveValidator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import lombok.experimental.UtilityClass;

/**
 * Offense-intent transport staging for the SVF (map-room#2755 PR-F).
 *
 * <p>The Java sidecar's NCM transport-move logic only moves transports for defense reasons:
 * naval-defense (transport joins a threatened friendly sea zone) and amphib-defense (transport
 * rushes to a threatened friendly land territory). There is no parallel hook that says "I have idle
 * transports + a high-S Europe value map; pre-position them eastward so next turn's CM can reach
 * Berlin." That gap was the structural cause of US transports drifting south to Caribbean (via NCM
 * amphib-defense pull) instead of east toward Atlantic — observed across SVF live runs after PR-J
 * unblocked the reachability calc.
 *
 * <p>This util adds the offense-intent leg: at the end of NCM (after defense + best-territory +
 * infra placement), for each idle unloaded transport find the reachable sea zone with the highest
 * "max S(adjacent enemy land)" score and assign it as a move destination. The transport then gets
 * dispatched via the existing ProMoveUtils.calculateMoveRoutes path.
 *
 * <p>US-only per spec §2. Dormant unless {@code proData.getWStrat() > 0} so it activates with the
 * same flip that turns on the SVF blend.
 *
 * <p>The staging is bounded: only one hop forward per turn (transport moves to the best reachable
 * zone), so over 2-3 turns transports walk east. Doesn't override defensive commitments (transports
 * already in moveMap or in {@code transportsToHold} are skipped).
 */
@UtilityClass
public final class ProTransportStaging {

  /**
   * Stages idle US transports toward sea zones with high S(adjacent land) scores. Mutates the
   * {@code moveMap} in place by adding chosen transports as temp units to their target sea zone.
   *
   * <p>Returns the number of transports staged. Always {@code 0} for non-US, for {@code wStrat ==
   * 0}, or when {@code proData.getStrategicValueField()} is null (no SVF compute fired this
   * request).
   */
  public static int stageIdleTransports(
      final ProData proData,
      final GamePlayer player,
      final Map<Unit, Set<Territory>> transportMoveMap,
      final Map<Territory, ProTerritory> moveMap) {
    if (!isStagingActive(proData, player)) {
      return 0;
    }
    final Map<Territory, Double> svf = proData.getStrategicValueField();
    if (svf == null || svf.isEmpty()) {
      return 0;
    }

    final GameData data = proData.getData();
    final GameMap map = data.getMap();
    final Set<Unit> alreadyAssigned = collectAssignedTransports(moveMap);
    alreadyAssigned.addAll(proData.getTransportsToHold());

    int staged = 0;
    for (final Map.Entry<Unit, Set<Territory>> entry : transportMoveMap.entrySet()) {
      final Unit transport = entry.getKey();
      if (alreadyAssigned.contains(transport)) {
        continue;
      }
      final Territory currentSz = proData.getUnitTerritory(transport);
      if (currentSz == null) {
        continue;
      }
      final Territory bestSz =
          pickBestStagingZone(currentSz, entry.getValue(), svf, map, player, data, transport);
      if (bestSz == null || bestSz.equals(currentSz)) {
        continue;
      }
      // Stage the transport at bestSz by adding it as a temp unit on the destination's
      // ProTerritory. ProMoveUtils.calculateMoveRoutes picks this up downstream.
      final ProTerritory dest = proData.getProTerritory(moveMap, bestSz);
      dest.addTempUnit(transport);
      staged++;
      ProLogger.trace(
          "SVF staging: transport id="
              + transport.getId()
              + " from="
              + currentSz.getName()
              + " to="
              + bestSz.getName()
              + " score="
              + scoreSeaZone(bestSz, svf, map, player));
    }
    if (staged > 0) {
      ProLogger.info("SVF staging assigned " + staged + " transport(s) to high-value sea zones");
    }
    return staged;
  }

  /**
   * Walks moveMap collecting every transport already committed to a destination — either as a
   * defender (addTempUnit), an amphib unloader (transportTerritoryMap), or already-moved transit.
   * Returns the union so the staging pass doesn't double-assign.
   */
  private static Set<Unit> collectAssignedTransports(final Map<Territory, ProTerritory> moveMap) {
    final Set<Unit> assigned = new HashSet<>();
    for (final ProTerritory patd : moveMap.values()) {
      for (final Unit u : patd.getUnits()) {
        if (u.getUnitAttachment().getTransportCapacity() > 0) {
          assigned.add(u);
        }
      }
      for (final Unit u : patd.getTempUnits()) {
        if (u.getUnitAttachment().getTransportCapacity() > 0) {
          assigned.add(u);
        }
      }
      assigned.addAll(patd.getTransportTerritoryMap().keySet());
    }
    return assigned;
  }

  /**
   * Picks the reachable sea zone with the highest staging score, or the current zone if no
   * candidate scores strictly higher. The candidate set is filtered to validate the route per the
   * move validator (no canal restrictions, etc.) so we don't dispatch an invalid move.
   */
  private static Territory pickBestStagingZone(
      final Territory currentSz,
      final Set<Territory> reachable,
      final Map<Territory, Double> svf,
      final GameMap map,
      final GamePlayer player,
      final GameData data,
      final Unit transport) {
    Territory best = currentSz;
    double bestScore = scoreSeaZone(currentSz, svf, map, player);
    final MoveValidator validator = new MoveValidator(data, false);
    for (final Territory candidate : reachable) {
      if (!candidate.isWater()) {
        continue;
      }
      if (candidate.equals(currentSz)) {
        continue;
      }
      final double score = scoreSeaZone(candidate, svf, map, player);
      if (score <= bestScore) {
        continue;
      }
      // Validate the route — handles canal access and any per-unit restrictions the raw
      // adjacency walk doesn't catch.
      final Route route = new Route(currentSz, candidate);
      if (validator.validateCanal(route, List.of(transport), player) != null) {
        continue;
      }
      best = candidate;
      bestScore = score;
    }
    return best;
  }

  /**
   * Score a candidate staging sea zone as the max {@code S(adjacent_land)} for any adjacent
   * non-water territory. Friendly + own land have S=0 after PR-B's allied-land suppression, so the
   * max naturally picks the highest-value enemy target the AI could amphib next turn.
   */
  static double scoreSeaZone(
      final Territory seaZone,
      final Map<Territory, Double> svf,
      final GameMap map,
      final GamePlayer player) {
    if (!seaZone.isWater()) {
      return 0.0;
    }
    final Predicate<Territory> isLand = Matches.territoryIsLand();
    double max = 0.0;
    for (final Territory neighbor : map.getNeighbors(seaZone, isLand)) {
      final Double s = svf.get(neighbor);
      if (s != null && s > max) {
        max = s;
      }
    }
    return max;
  }

  /**
   * Gates the entire staging pass. Returns false for non-Americans (spec §2 US-only), when the SVF
   * blend is dormant ({@code wStrat == 0}), or when {@code player} is null. Public-visible because
   * the wire site in {@code ProNonCombatMoveAi} can short-circuit on it to skip the
   * transport-move-map collection when staging won't run.
   */
  public static boolean isStagingActive(final ProData proData, final GamePlayer player) {
    if (player == null || !"Americans".equals(player.getName())) {
      return false;
    }
    return proData.getWStrat() > 0.0;
  }
}
