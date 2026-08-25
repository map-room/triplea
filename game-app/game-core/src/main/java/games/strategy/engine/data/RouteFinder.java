package games.strategy.engine.data;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import games.strategy.triplea.delegate.TerritoryEffectHelper;
import games.strategy.triplea.delegate.move.validation.MoveValidator;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
class RouteFinder {

  private final MoveValidator moveValidator;
  private final GameMap map;
  private final Predicate<Territory> condition;
  private final Collection<Unit> units;
  @Nullable private final GamePlayer player;

  /** Nodes popped from the work queue during the last {@code findRouteByCost} call. */
  @VisibleForTesting int nodesDequeued;

  /**
   * Calls to {@link GameMap#getNeighbors(Territory, Predicate)} during the last search. Equal to
   * expanding dequeues (not minCost-pruned ones).
   */
  @VisibleForTesting int neighborLookups;

  /**
   * {@code previous.containsKey} / {@code routeCosts.get} on Territory-keyed HashMaps during the
   * last search. Zero when the int-index path runs (map-room#3703).
   */
  @VisibleForTesting int territoryKeyedMapOps;

  RouteFinder(final GameMap map, final Predicate<Territory> condition) {
    this(map, condition, Set.of(), null);
  }

  RouteFinder(
      final GameMap map,
      final Predicate<Territory> condition,
      final Collection<Unit> units,
      final GamePlayer player) {
    // Note: We only use MoveValidator for canal checks, where isNonCombat isn't used.
    this(new MoveValidator(map.getData(), false), map, condition, units, player);
  }

  Optional<Route> findRouteByDistance(
      final @Nonnull Territory start, final @Nonnull Territory end) {
    return findRouteByCost(start, end, t -> BigDecimal.ONE);
  }

  Optional<Route> findRouteByCost(final @Nonnull Territory start, final @Nonnull Territory end) {
    return findRouteByCost(start, end, this::getMaxMovementCost);
  }

  private Optional<Route> findRouteByCost(
      final Territory start,
      final Territory end,
      final Function<Territory, BigDecimal> territoryCostFunction) {
    Preconditions.checkNotNull(start);
    Preconditions.checkNotNull(end);

    if (start.equals(end)) {
      return Optional.of(new Route(start));
    }

    nodesDequeued = 0;
    neighborLookups = 0;
    territoryKeyedMapOps = 0;

    if (canIndexByInt(start, end)) {
      return findRouteByCostIndexed(start, end, territoryCostFunction);
    }
    return findRouteByCostHashed(start, end, territoryCostFunction);
  }

  /**
   * True only for real {@link Territory} instances that {@link GameMap} has indexed. Mockito mocks
   * return 0 from unstubbed {@code getIndex()} and must not share a slot.
   */
  private static boolean canIndexByInt(final Territory start, final Territory end) {
    return start.getClass() == Territory.class
        && end.getClass() == Territory.class
        && start.getIndex() >= 0
        && end.getIndex() >= 0;
  }

  /**
   * Same search as {@link #findRouteByCostHashed}, but {@code previous} / {@code routeCosts} are
   * dense arrays. Neighbor iteration is still the filtered HashSet from {@link GameMap}, so
   * first-min-cost tie-breaking is unchanged.
   */
  private Optional<Route> findRouteByCostIndexed(
      final Territory start,
      final Territory end,
      final Function<Territory, BigDecimal> territoryCostFunction) {
    final int n = map.getTerritories().size();
    final Territory[] previous = new Territory[n];
    final BigDecimal[] routeCosts = new BigDecimal[n];
    final boolean[] seen = new boolean[n];
    final Queue<Territory> toVisit = new ArrayDeque<>();
    final int startIndex = start.getIndex();
    seen[startIndex] = true;
    routeCosts[startIndex] = BigDecimal.ZERO;
    toVisit.add(start);
    BigDecimal minCost = new BigDecimal(Integer.MAX_VALUE);

    while (!toVisit.isEmpty()) {
      final Territory currentTerritory = toVisit.remove();
      nodesDequeued++;
      final int currentIndex = currentTerritory.getIndex();
      if (routeCosts[currentIndex].compareTo(minCost) >= 0) {
        continue;
      }
      for (final Territory neighbor :
          getNeighborsValidatingCanals(currentTerritory, condition, units, player)) {
        final int neighborIndex = neighbor.getIndex();
        final BigDecimal routeCost =
            routeCosts[currentIndex].add(territoryCostFunction.apply(neighbor));
        if (!seen[neighborIndex] || routeCost.compareTo(routeCosts[neighborIndex]) < 0) {
          seen[neighborIndex] = true;
          previous[neighborIndex] = currentTerritory;
          routeCosts[neighborIndex] = routeCost;
          if (neighbor.equals(end) && routeCost.compareTo(minCost) < 0) {
            minCost = routeCost;
            break;
          }
          toVisit.add(neighbor);
        }
      }
    }
    return (minCost.compareTo(new BigDecimal(Integer.MAX_VALUE)) == 0)
        ? Optional.empty()
        : Optional.of(getRouteFromPredecessors(start, end, previous));
  }

  private Optional<Route> findRouteByCostHashed(
      final Territory start,
      final Territory end,
      final Function<Territory, BigDecimal> territoryCostFunction) {
    final Map<Territory, Territory> previous = new HashMap<>();
    previous.put(start, null);
    final Queue<Territory> toVisit = new ArrayDeque<>();
    toVisit.add(start);
    final Map<Territory, BigDecimal> routeCosts = new HashMap<>();
    routeCosts.put(start, BigDecimal.ZERO);
    BigDecimal minCost = new BigDecimal(Integer.MAX_VALUE);

    while (!toVisit.isEmpty()) {
      final Territory currentTerritory = toVisit.remove();
      nodesDequeued++;
      territoryKeyedMapOps++;
      if (routeCosts.get(currentTerritory).compareTo(minCost) >= 0) {
        continue;
      }
      for (final Territory neighbor :
          getNeighborsValidatingCanals(currentTerritory, condition, units, player)) {
        territoryKeyedMapOps++;
        final BigDecimal routeCost =
            routeCosts.get(currentTerritory).add(territoryCostFunction.apply(neighbor));
        territoryKeyedMapOps++;
        if (!previous.containsKey(neighbor) || routeCost.compareTo(routeCosts.get(neighbor)) < 0) {
          previous.put(neighbor, currentTerritory);
          routeCosts.put(neighbor, routeCost);
          if (neighbor.equals(end) && routeCost.compareTo(minCost) < 0) {
            minCost = routeCost;
            break;
          }
          toVisit.add(neighbor);
        }
      }
    }
    return (minCost.compareTo(new BigDecimal(Integer.MAX_VALUE)) == 0)
        ? Optional.empty()
        : Optional.of(getRoute(start, end, previous));
  }

  private Set<Territory> getNeighborsValidatingCanals(
      final Territory territory,
      final Predicate<Territory> neighborFilter,
      final Collection<Unit> units,
      final GamePlayer player) {
    neighborLookups++;
    return map.getNeighbors(
        territory,
        player == null
            ? neighborFilter
            : neighborFilter.and(
                t -> moveValidator.canAnyUnitsPassCanal(territory, t, units, player)));
  }

  @VisibleForTesting
  BigDecimal getMaxMovementCost(final Territory t) {
    return TerritoryEffectHelper.getMaxMovementCost(t, units);
  }

  private static Route getRouteFromPredecessors(
      final Territory start, final Territory destination, final Territory[] previous) {
    final List<Territory> territories = new ArrayList<>();
    Territory current = destination;
    while (!start.equals(current)) {
      assert current != null : "Route was calculated but isn't connected";
      territories.add(current);
      current = previous[current.getIndex()];
    }
    territories.add(start);
    Collections.reverse(territories);
    return new Route(territories);
  }

  private static Route getRoute(
      final Territory start,
      final Territory destination,
      final Map<Territory, Territory> previous) {
    final List<Territory> territories = new ArrayList<>();
    Territory current = destination;
    while (!start.equals(current)) {
      assert current != null : "Route was calculated but isn't connected";
      territories.add(current);
      current = previous.get(current);
    }
    territories.add(start);
    Collections.reverse(territories);
    return new Route(territories);
  }
}
