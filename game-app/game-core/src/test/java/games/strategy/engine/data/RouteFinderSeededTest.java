package games.strategy.engine.data;

import static games.strategy.triplea.delegate.GameDataTestUtil.germans;
import static games.strategy.triplea.delegate.GameDataTestUtil.infantry;
import static games.strategy.triplea.delegate.GameDataTestUtil.submarine;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.strategy.engine.framework.GameDataManager;
import games.strategy.engine.framework.GameDataUtils;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Behaviour-preserving gate for RouteFinder speedups (map-room#3703).
 *
 * <p>{@link GameMap#getRouteForUnits} is a pure function of (map, start, end, condition, units,
 * player). The chosen route — including which equal-cost path wins — must stay pinned. A change
 * that returns a different route for any tie is behaviour-changing and needs eval-harness
 * validation.
 *
 * <p>These pairs are chosen so the gate actually reaches {@code findRouteByCost} on a real G40 map:
 * land, a long land path with many equal-cost alternatives, and a sea path. A gate that only
 * covered unique shortest paths would not catch a neighbor-iteration-order change.
 *
 * <p>Pinned against unmodified {@code origin/main}. Tests that only compiled or passed with the
 * parked int-index (HashMap-op counters, {@code Territory.getIndex()}) are not here.
 */
class RouteFinderSeededTest extends AbstractClientSettingTestCase {

  private static List<String> names(final Route route) {
    return route.getAllTerritories().stream().map(Territory::getName).collect(Collectors.toList());
  }

  private static Route landRoute(final GameData data, final String from, final String to) {
    final GamePlayer germans = germans(data);
    final List<Unit> units = infantry(data).create(1, germans);
    final Optional<Route> route =
        data.getMap()
            .getRouteForUnits(
                data.getMap().getTerritoryOrThrow(from),
                data.getMap().getTerritoryOrThrow(to),
                t -> !t.isWater(),
                units,
                germans);
    assertTrue(route.isPresent(), from + " -> " + to);
    return route.get();
  }

  private static Route seaRoute(final GameData data, final String from, final String to) {
    final GamePlayer germans = germans(data);
    final List<Unit> units = submarine(data).create(1, germans);
    final Optional<Route> route =
        data.getMap()
            .getRouteForUnits(
                data.getMap().getTerritoryOrThrow(from),
                data.getMap().getTerritoryOrThrow(to),
                Territory::isWater,
                units,
                germans);
    assertTrue(route.isPresent(), from + " -> " + to);
    return route.get();
  }

  /**
   * Number of distinct minimum-hop paths on the unfiltered land/sea graph. Used to prove a pinned
   * pair is a real tie, not a unique shortest path.
   */
  static int countMinHopPaths(
      final GameMap map, final Territory start, final Territory end, final boolean water) {
    final Map<Territory, Integer> dist = new HashMap<>();
    final Queue<Territory> q = new ArrayDeque<>();
    dist.put(start, 0);
    q.add(start);
    while (!q.isEmpty()) {
      final Territory cur = q.remove();
      final int d = dist.get(cur);
      if (cur.equals(end)) {
        continue;
      }
      for (final Territory n : map.getNeighbors(cur)) {
        if (n.isWater() != water && !n.equals(end) && !n.equals(start)) {
          continue;
        }
        if (!dist.containsKey(n)) {
          dist.put(n, d + 1);
          q.add(n);
        }
      }
    }
    if (!dist.containsKey(end)) {
      return 0;
    }
    return countPaths(map, start, end, water, dist);
  }

  private static int countPaths(
      final GameMap map,
      final Territory start,
      final Territory end,
      final boolean water,
      final Map<Territory, Integer> dist) {
    if (start.equals(end)) {
      return 1;
    }
    int total = 0;
    final int d = dist.get(end);
    for (final Territory n : map.getNeighbors(end)) {
      if (n.isWater() != water && !n.equals(start) && !n.equals(end)) {
        continue;
      }
      if (dist.getOrDefault(n, Integer.MAX_VALUE) == d - 1) {
        total += countPaths(map, start, n, water, dist);
      }
    }
    return total;
  }

  @Test
  void twoIndependentFindersAgreeOnG40Routes() {
    final GameData a = TestMapGameData.GLOBAL1940.getGameData();
    final GameData b = TestMapGameData.GLOBAL1940.getGameData();
    assertEquals(
        names(landRoute(a, "Germany", "Poland")), names(landRoute(b, "Germany", "Poland")));
    assertEquals(
        names(landRoute(a, "Germany", "Russia")), names(landRoute(b, "Germany", "Russia")));
    assertEquals(
        names(seaRoute(a, "5 Sea Zone", "12 Sea Zone")),
        names(seaRoute(b, "5 Sea Zone", "12 Sea Zone")));
  }

  @Test
  void clonePicksTheSameGermanyRussiaTieWinner() {
    final GameData original = TestMapGameData.GLOBAL1940.getGameData();
    final GameData clone =
        GameDataUtils.cloneGameData(
                original, GameDataManager.Options.builder().withDelegates(true).build())
            .orElseThrow();
    assertEquals(
        names(landRoute(original, "Germany", "Russia")),
        names(landRoute(clone, "Germany", "Russia")));
  }

  @Test
  void germanyToRussiaIsARealTieOnTheUnfilteredGraph() {
    final GameData data = TestMapGameData.GLOBAL1940.getGameData();
    final int alternatives =
        countMinHopPaths(
            data.getMap(),
            data.getMap().getTerritoryOrThrow("Germany"),
            data.getMap().getTerritoryOrThrow("Russia"),
            false);
    assertTrue(
        alternatives > 1,
        "Germany→Russia must have multiple min-hop paths so the pin catches a tie-break change;"
            + " got "
            + alternatives);
  }

  @Test
  void pinnedG40RoutesIncludingEqualCostLandPath() {
    final GameData data = TestMapGameData.GLOBAL1940.getGameData();

    assertEquals(List.of("Germany", "Poland"), names(landRoute(data, "Germany", "Poland")));
    assertEquals(
        List.of("Germany", "Western Germany"),
        names(landRoute(data, "Germany", "Western Germany")));

    // Long land route with many equal-cost alternatives. The specific path is the
    // first-min-cost winner under current HashSet neighbor iteration. If a later
    // change iterates neighbors in a different order, this list is the thing that moves.
    assertEquals(
        List.of("Germany", "Poland", "Baltic States", "Belarus", "Bryansk", "Russia"),
        names(landRoute(data, "Germany", "Russia")));

    assertEquals(
        List.of("Germany", "Western Germany", "Northern Italy", "Southern France"),
        names(landRoute(data, "Germany", "Southern France")));

    assertEquals(
        List.of("5 Sea Zone", "7 Sea Zone", "8 Sea Zone", "9 Sea Zone", "12 Sea Zone"),
        names(seaRoute(data, "5 Sea Zone", "12 Sea Zone")));
  }
}
