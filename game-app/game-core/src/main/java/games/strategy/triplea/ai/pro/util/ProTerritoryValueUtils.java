package games.strategy.triplea.ai.pro.util;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.util.BreadthFirstSearch;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.logging.ProValueHeatmapDumper;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.Matches;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import lombok.experimental.UtilityClass;
import org.triplea.java.collections.CollectionUtils;

/** Pro AI battle utilities. */
@UtilityClass
public final class ProTerritoryValueUtils {
  static final int MIN_FACTORY_CHECK_DISTANCE = 9;

  /**
   * Bonus added to territory value when an airfield is present and production {@code <=} 1.
   *
   * <p>Tuned so bases differentiate marginal low-IPC territories (Midway, Caroline Islands) without
   * adding noise to higher-IPC mainland scoring where raw IPC math dominates.
   */
  public static final double AIRFIELD_BONUS = 0.0;

  /**
   * Bonus added to territory value when a naval base is present and production {@code <=} 1.
   *
   * @see #AIRFIELD_BONUS
   */
  public static final double NAVAL_BASE_BONUS = 0.0;

  // G40 represents bases as placed units: airfield (isAirBase=true) and harbour (givesMovement).
  // TerritoryAttachment.hasAirBase/hasNavalBase cover maps that use territory-attribute flags
  // instead, so we check both to be game-agnostic.
  private static boolean territoryHasAirBase(final Territory t) {
    return TerritoryAttachment.hasAirBase(t)
        || t.getUnits().stream().anyMatch(Matches.unitIsAirBase());
  }

  private static boolean territoryHasNavalBase(final Territory t) {
    return TerritoryAttachment.hasNavalBase(t)
        // Airfields also have givesMovement (to air units); exclude them so we match only
        // harbour-type units that give movement to naval units.
        || t.getUnits().stream()
            .filter(Matches.unitIsAirBase().negate())
            .anyMatch(u -> !u.getUnitAttachment().getGivesMovement().isEmpty());
  }

  /**
   * Returns the combined airfield/naval-base bonus for {@code t}, or 0 if its production exceeds 1.
   *
   * <p>The production gate ensures the bonus only differentiates marginal low-IPC territories
   * (Midway, Caroline Islands) and does not distort scoring for mainland territories where raw IPC
   * math already provides sufficient discrimination.
   *
   * <p>Single source of truth — called from {@link #findTerritoryAttackValue}, {@link
   * #findLandValue}, and {@code ProNonCombatMoveAi.prioritizeDefendOptions}.
   */
  public static double computeBaseBonus(final Territory t) {
    if (TerritoryAttachment.getProduction(t) > 1) {
      return 0.0;
    }
    return (territoryHasAirBase(t) ? AIRFIELD_BONUS : 0.0)
        + (territoryHasNavalBase(t) ? NAVAL_BASE_BONUS : 0.0);
  }

  /**
   * Returns the relative value of attacking the specified territory compared to other territories.
   */
  public static double findTerritoryAttackValue(
      final ProData proData, final GamePlayer player, final Territory t) {
    final int isEnemyFactory =
        ProMatches.territoryHasInfraFactoryAndIsEnemyLand(player).test(t) ? 1 : 0;
    double value = 3.0 * TerritoryAttachment.getProduction(t) * (isEnemyFactory + 1);
    value += computeBaseBonus(t);
    if (ProUtils.isNeutralLand(t)) {
      final double strength =
          ProBattleUtils.estimateStrength(
              t, new ArrayList<>(t.getUnits()), new ArrayList<>(), false);

      // Estimate TUV swing as number of casualties * cost
      final double tuvSwing = -(strength / 8) * proData.getMinCostPerHitPoint();
      value += tuvSwing;
    }

    return value;
  }

  /** Returns the value of each territory in {@code territoriesToCheck}. */
  public static Map<Territory, Double> findTerritoryValues(
      final ProData proData,
      final GamePlayer player,
      final List<Territory> territoriesThatCantBeHeld,
      final List<Territory> territoriesToAttack,
      final Set<Territory> territoriesToCheck) {
    // Strategic Value Field (map-room#2755 PR-A): lazy-compute S(n) on the first
    // findTerritoryValues call of this request when wStrat > 0. Skipped entirely when wStrat==0
    // (the zero-impact default), so PR-A is a true no-op for non-tuning runs. PR-B's blend
    // reads proData.getStrategicValueField() and applies wStrat * S(t) inside the value loops.
    if (proData.getWStrat() > 0 && proData.getStrategicValueField() == null) {
      proData.setStrategicValueField(ProStrategicValueField.compute(proData, player));
    }
    final int maxLandMassSize = findMaxLandMassSize(player);
    final Map<Territory, Double> enemyCapitalsAndFactoriesMap =
        findEnemyCapitalsAndFactoriesValue(
            player, maxLandMassSize, territoriesThatCantBeHeld, territoriesToAttack);

    final Map<Territory, Double> territoryValueMap = new HashMap<>();
    for (final Territory t : territoriesToCheck) {
      if (!t.isWater()) {
        final double value =
            findLandValue(
                proData,
                t,
                player,
                maxLandMassSize,
                enemyCapitalsAndFactoriesMap,
                territoriesThatCantBeHeld,
                territoriesToAttack);
        territoryValueMap.put(t, value);
      }
    }

    for (final Territory t : territoriesToCheck) {
      if (t.isWater()) {
        final double value =
            findWaterValue(
                proData,
                t,
                player,
                maxLandMassSize,
                enemyCapitalsAndFactoriesMap,
                territoriesThatCantBeHeld,
                territoriesToAttack,
                territoryValueMap);
        territoryValueMap.put(t, value);
      }
    }

    ProValueHeatmapDumper.dumpIfEnabled(proData, player, "combined", territoryValueMap);
    return territoryValueMap;
  }

  /** Returns the value of each sea territory in {@link ProData#getData()}. */
  public static Map<Territory, Double> findSeaTerritoryValues(
      final GamePlayer player,
      final List<Territory> territoriesThatCantBeHeld,
      final List<Territory> territoriesToCheck) {

    // Determine value for water territories
    final Map<Territory, Double> territoryValueMap = new HashMap<>();
    final GameData data = player.getData();
    for (final Territory t : territoriesToCheck) {
      if (!territoriesThatCantBeHeld.contains(t)
          && t.isWater()
          && !data.getMap().getNeighbors(t, Matches.territoryIsWater()).isEmpty()) {

        // Determine sea value based on nearby convoy production
        double nearbySeaProductionValue = 0;
        final Set<Territory> nearbySeaTerritories =
            data.getMap().getNeighbors(t, 4, ProMatches.territoryCanMoveSeaUnits(player, true));
        final List<Territory> nearbyEnemySeaTerritories =
            CollectionUtils.getMatches(
                nearbySeaTerritories,
                ProMatches.territoryIsEnemyOrCantBeHeld(player, territoriesThatCantBeHeld));
        calculateTerritoryValueToTargets(
            t, nearbyEnemySeaTerritories, player, data, TerritoryAttachment::getProduction);

        // Determine sea value based on nearby enemy sea units
        double nearbyEnemySeaUnitValue = 0;
        final List<Territory> nearbyEnemySeaUnitTerritories =
            CollectionUtils.getMatches(
                nearbySeaTerritories, Matches.territoryHasEnemyUnits(player));
        calculateTerritoryValueToTargets(
            t,
            nearbyEnemySeaUnitTerritories,
            player,
            data,
            targetTerritory ->
                targetTerritory.getUnitCollection().countMatches(Matches.unitIsEnemyOf(player)));

        // Set final values
        final double value = 100 * nearbySeaProductionValue + nearbyEnemySeaUnitValue;
        territoryValueMap.put(t, value);
      } else if (t.isWater()) {
        territoryValueMap.put(t, 0.0);
      }
    }

    // findSeaTerritoryValues doesn't receive ProData; use the GameData-shaped dumper overload.
    ProValueHeatmapDumper.dumpIfEnabledFromGameData(
        player.getData(), player, "sea-only", territoryValueMap);
    return territoryValueMap;
  }

  private static double calculateTerritoryValueToTargets(
      final Territory t,
      final List<Territory> targetTerritories,
      final GamePlayer player,
      final GameData data,
      ToIntFunction<Territory> toTargetValueFunction) {
    double territoryValue = 0;
    for (final Territory targetTerritory : targetTerritories) {
      final Optional<Route> optionalRoute =
          data.getMap()
              .getRouteForUnits(
                  t,
                  targetTerritory,
                  ProMatches.territoryCanMoveSeaUnits(player, true),
                  Set.of(),
                  player);
      if (optionalRoute.isEmpty()) {
        continue;
      }
      final int distance = optionalRoute.get().numberOfSteps();
      if (distance > 0) {
        territoryValue += toTargetValueFunction.applyAsInt(targetTerritory) / Math.pow(2, distance);
        territoryValue +=
            targetTerritory.getUnitCollection().countMatches(Matches.unitIsEnemyOf(player))
                / Math.pow(2, distance);
      }
    }
    return territoryValue;
  }

  static int findMaxLandMassSize(final GamePlayer player) {
    final GameState data = player.getData();
    final Predicate<Territory> cond = ProMatches.territoryCanPotentiallyMoveLandUnits(player);

    final var visited = new HashSet<Territory>();

    int maxLandMassSize = 1;
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater() && !visited.contains(t)) {
        visited.add(t);
        final int[] landMassSize = new int[1];
        new BreadthFirstSearch(t, cond)
            .traverse(
                (territory, distance) -> {
                  visited.add(territory);
                  landMassSize[0]++;
                  return true;
                });
        if (landMassSize[0] > maxLandMassSize) {
          maxLandMassSize = landMassSize[0];
        }
      }
    }

    return maxLandMassSize;
  }

  private static Map<Territory, Double> findEnemyCapitalsAndFactoriesValue(
      final GamePlayer player,
      final int maxLandMassSize,
      final List<Territory> territoriesThatCantBeHeld,
      final List<Territory> territoriesToAttack) {
    // Get all enemy factories and capitals (check if most territories have factories and if so
    // remove them)
    final GameState data = player.getData();
    final List<Territory> allTerritories = data.getMap().getTerritories();
    final Set<Territory> enemyCapitalsAndFactories =
        new HashSet<>(
            CollectionUtils.getMatches(
                allTerritories,
                ProMatches.territoryHasInfraFactoryAndIsOwnedByPlayersOrCantBeHeld(
                    player, ProUtils.getPotentialEnemyPlayers(player), territoriesThatCantBeHeld)));
    final int numPotentialEnemyTerritories =
        CollectionUtils.countMatches(
            allTerritories,
            Matches.isTerritoryOwnedByAnyOf(ProUtils.getPotentialEnemyPlayers(player)));
    if (enemyCapitalsAndFactories.size() * 2 >= numPotentialEnemyTerritories) {
      enemyCapitalsAndFactories.clear();
    }
    enemyCapitalsAndFactories.addAll(ProUtils.getLiveEnemyCapitals(data, player));
    enemyCapitalsAndFactories.removeAll(territoriesToAttack);

    // Find value for each enemy capital and factory
    final Map<Territory, Double> enemyCapitalsAndFactoriesMap = new HashMap<>();
    for (final Territory t : enemyCapitalsAndFactories) {
      // Get factory production if factory
      int factoryProduction = 0;
      if (ProMatches.territoryHasInfraFactoryAndIsLand().test(t)) {
        factoryProduction = TerritoryAttachment.getProduction(t);
      }

      // Get player production if capital
      double playerProduction = 0;
      if (TerritoryAttachment.get(t).map(TerritoryAttachment::isCapital).orElse(false)) {
        playerProduction = ProUtils.getPlayerProduction(t.getOwner(), data);
      }

      // Calculate value
      final int isNeutral = ProUtils.isNeutralLand(t) ? 1 : 0;
      final int landMassSize =
          1
              + data.getMap()
                  .getNeighbors(t, 6, ProMatches.territoryCanPotentiallyMoveLandUnits(player))
                  .size();
      final double value =
          Math.sqrt(factoryProduction + Math.sqrt(playerProduction))
              * 32
              / (1 + 3.0 * isNeutral)
              * landMassSize
              / maxLandMassSize;
      enemyCapitalsAndFactoriesMap.put(t, value);
    }

    return enemyCapitalsAndFactoriesMap;
  }

  private static double findLandValue(
      final ProData proData,
      final Territory t,
      final GamePlayer player,
      final int maxLandMassSize,
      final Map<Territory, Double> enemyCapitalsAndFactoriesMap,
      final List<Territory> territoriesThatCantBeHeld,
      final List<Territory> territoriesToAttack) {
    // True Neutrals are never (or almost never) attacked due to the cascade rule —
    // attacking any True Neutral flips every remaining True Neutral to the opposing
    // coalition, which is virtually always a net loss. Score them at zero in the
    // territoryValueMap so they don't drive sea-zone staging value (and through it
    // `needAmphibUnitValue`), which previously pulled US/UK ground forces toward the
    // Caribbean to invade South American neutrals that no one ever attacks. #2745
    if (ProUtils.isNeutralLand(t)) {
      return 0.0;
    }
    final GameData data = proData.getData();
    final BiPredicate<Territory, Territory> routeCond =
        (t1, t2) ->
            ProMatches.territoryCanPotentiallyMoveLandUnits(player).test(t2)
                && ProMatches.noCanalsBetweenTerritories(player).test(t1, t2);
    final int landMassSize = 1 + data.getMap().getNeighbors(t, 6, routeCond).size();

    // Can't-hold short-circuit: mainland chunks we can't hold are scored zero (no point
    // pricing how productive an enemy mainland is if we can't keep it). For island
    // territories (landMassSize == 1) we still emit the raid-value floor — capturing
    // a can't-hold island flips its IPC and can deny enemy basing even if we lose it
    // back next turn. Without this branch the island production floor at line 391
    // below is bypassed for any island the AI marks can't-hold (e.g., Guam round 1
    // against a stacked Japanese garrison), zeroing every Pacific amphib target. #2736
    if (territoriesThatCantBeHeld.contains(t)) {
      if (landMassSize == 1) {
        return TerritoryAttachment.getProduction(t) * 0.5 + computeBaseBonus(t);
      }
      return 0.0;
    }

    // Determine value based on enemy factory land distance
    final List<Double> values = new ArrayList<>();
    final Collection<Territory> nearbyEnemyCapitalsAndFactories =
        findNearbyEnemyCapitalsAndFactories(t, enemyCapitalsAndFactoriesMap.keySet());
    for (final Territory enemyCapitalOrFactory : nearbyEnemyCapitalsAndFactories) {
      final int distance = data.getMap().getDistance(t, enemyCapitalOrFactory, routeCond);
      if (distance > 0) {
        values.add(enemyCapitalsAndFactoriesMap.get(enemyCapitalOrFactory) / Math.pow(2, distance));
      }
    }
    values.sort(Collections.reverseOrder());
    double capitalOrFactoryValue = 0;
    for (int i = 0; i < values.size(); i++) {
      capitalOrFactoryValue +=
          values.get(i) / Math.pow(2, i); // Decrease each additional factory value by half
    }

    // Determine value based on nearby territory production
    double nearbyEnemyValue = 0;
    final Set<Territory> nearbyTerritories = data.getMap().getNeighbors(t, 2, routeCond);
    final List<Territory> nearbyEnemyTerritories =
        CollectionUtils.getMatches(
            nearbyTerritories,
            ProMatches.territoryIsEnemyOrCantBeHeld(player, territoriesThatCantBeHeld));
    nearbyEnemyTerritories.removeAll(territoriesToAttack);
    for (final Territory nearbyEnemyTerritory : nearbyEnemyTerritories) {
      final int distance = data.getMap().getDistance(t, nearbyEnemyTerritory, routeCond);
      if (distance > 0) {
        double value = TerritoryAttachment.getProduction(nearbyEnemyTerritory);
        if (ProUtils.isNeutralLand(nearbyEnemyTerritory)) {
          // True Neutrals contribute almost nothing to nearby-territory value because
          // the cascade rule makes them un-attackable in practice. The previous /3
          // discount was too generous and let SA neutrals inflate Caribbean staging. #2745
          value = findTerritoryAttackValue(proData, player, nearbyEnemyTerritory) / 30;
        } else if (ProMatches.territoryIsAlliedLandAndHasNoEnemyNeighbors(player)
            .test(nearbyEnemyTerritory)) {
          value *= 0.1; // reduce value for can't hold amphib allied territories
        }
        if (value > 0) {
          nearbyEnemyValue += (value / Math.pow(2, distance));
        }
      }
    }
    double value = nearbyEnemyValue * landMassSize / maxLandMassSize + capitalOrFactoryValue;
    if (ProMatches.territoryHasInfraFactoryAndIsLand().test(t)) {
      value *= 1.1; // prefer territories with factories
    }

    // Island territories (no land-accessible neighbors within 6 hops) produce value=0 from
    // the proximity math regardless of their production, because all distance calculations
    // use land-only routing. Apply a production-based floor so islands aren't silently zeroed
    // in the hold-value map and discarded before amphibious planning considers them.
    //
    // Scoped to *attack candidates* only (enemy-owned or can't-be-held) — friendly islands
    // already have their own defense scoring path and shouldn't appear in the amphib
    // destination set via this floor. Pre-scoping the floor was inflating West Indies (UK,
    // production=1) to 0.5, which then dominated US East Coast amphib unload destinations
    // when no enemy target was within range. #2747
    final boolean isAttackCandidate =
        ProMatches.territoryIsEnemyOrCantBeHeld(player, territoriesThatCantBeHeld).test(t);
    if (landMassSize == 1 && isAttackCandidate) {
      value = Math.max(value, TerritoryAttachment.getProduction(t) * 0.5);
    }

    // Prefer base-bearing low-IPC territories (e.g. Midway, Caroline Islands): airfields extend
    // fighter/bomber range; naval bases extend fleet range and enable repairs. The production gate
    // (production <= 1) ensures the bonus only differentiates marginal islands without distorting
    // mainland scoring where raw IPC math already provides sufficient discrimination.
    value += computeBaseBonus(t);

    return value;
  }

  private static double findWaterValue(
      final ProData proData,
      final Territory t,
      final GamePlayer player,
      final int maxLandMassSize,
      final Map<Territory, Double> enemyCapitalsAndFactoriesMap,
      final List<Territory> territoriesThatCantBeHeld,
      final List<Territory> territoriesToAttack,
      final Map<Territory, Double> territoryValueMap) {
    final GameState data = proData.getData();
    if (territoriesThatCantBeHeld.contains(t)
        || data.getMap().getNeighbors(t, Matches.territoryIsWater()).isEmpty()) {
      return 0.0;
    }

    // Determine value based on enemy factory distance
    final List<Double> values = new ArrayList<>();
    final Collection<Territory> nearbyEnemyCapitalsAndFactories =
        findNearbyEnemyCapitalsAndFactories(t, enemyCapitalsAndFactoriesMap.keySet());
    for (final Territory enemyCapitalOrFactory : nearbyEnemyCapitalsAndFactories) {
      final Optional<Route> optionalRoute =
          data.getMap()
              .getRouteForUnits(
                  t,
                  enemyCapitalOrFactory,
                  ProMatches.territoryCanMoveSeaUnits(player, true),
                  Set.of(),
                  player);
      if (optionalRoute.isEmpty()) {
        continue;
      }
      final int distance = optionalRoute.get().numberOfSteps();
      if (distance > 0) {
        values.add(enemyCapitalsAndFactoriesMap.get(enemyCapitalOrFactory) / Math.pow(2, distance));
      }
    }
    values.sort(Collections.reverseOrder());
    double capitalOrFactoryValue = 0;
    for (int i = 0; i < values.size(); i++) {
      capitalOrFactoryValue +=
          values.get(i) / Math.pow(2, i); // Decrease each additional factory value by half
    }

    // Determine value based on nearby territory production
    double nearbyLandValue = 0;
    final Set<Territory> nearbyTerritories =
        data.getMap()
            .getNeighborsIgnoreEnd(t, 3, ProMatches.territoryCanMoveSeaUnits(player, true));
    final List<Territory> nearbyLandTerritories =
        CollectionUtils.getMatches(
            nearbyTerritories, ProMatches.territoryCanPotentiallyMoveLandUnits(player));
    nearbyLandTerritories.removeAll(territoriesToAttack);
    for (final Territory nearbyLandTerritory : nearbyLandTerritories) {
      final Optional<Route> optionalRoute =
          data.getMap()
              .getRouteForUnits(
                  t,
                  nearbyLandTerritory,
                  ProMatches.territoryCanMoveSeaUnits(player, true),
                  Set.of(),
                  player);
      if (optionalRoute.isEmpty()) {
        continue;
      }
      final int distance = optionalRoute.get().numberOfSteps();
      if (distance > 0 && distance <= 3) {
        if (ProMatches.territoryIsEnemyOrCantBeHeld(player, territoriesThatCantBeHeld)
            .test(nearbyLandTerritory)) {
          double value = TerritoryAttachment.getProduction(nearbyLandTerritory);
          if (ProUtils.isNeutralLand(nearbyLandTerritory)) {
            // True Neutrals contribute almost nothing to sea-zone staging value because
            // the cascade rule makes them un-attackable in practice. Previously this
            // summed full attack-value (3 × production), making Caribbean sea zones
            // hugely attractive to transports because of nearby South American
            // neutrals — pulling US/UK ground forces toward staging that never lands. #2745
            value = findTerritoryAttackValue(proData, player, nearbyLandTerritory) / 30;
          }
          nearbyLandValue += value;
        }
        if (!territoryValueMap.containsKey(nearbyLandTerritory)) {
          final double value =
              findLandValue(
                  proData,
                  nearbyLandTerritory,
                  player,
                  maxLandMassSize,
                  enemyCapitalsAndFactoriesMap,
                  territoriesThatCantBeHeld,
                  territoriesToAttack);
          territoryValueMap.put(nearbyLandTerritory, value);
        }
        nearbyLandValue += territoryValueMap.get(nearbyLandTerritory);
      }
    }

    return capitalOrFactoryValue / 100 + nearbyLandValue / 10;
  }

  /**
   * Finds enemy capitals / factories from a list that are "nearby" a given territory.
   *
   * <p>If any of the target territories exist within a distance of 9, returns the subset that do.
   * Otherwise, proceeds to check the territories at each subsequent distance until at least one
   * capital is found.
   *
   * <p>Note: This is an optimized version of a previous, much slower algorithm that has been
   * designed to keep the original behavior, but tuned for speed.
   *
   * @param startTerritory The territory from where to start the search.
   * @param enemyCapitalsAndFactories The territories to search for.
   * @return Subset of enemyCapitalsAndFactories that were found.
   */
  static Collection<Territory> findNearbyEnemyCapitalsAndFactories(
      final Territory startTerritory, final Set<Territory> enemyCapitalsAndFactories) {
    final var found = new HashSet<Territory>();
    new BreadthFirstSearch(startTerritory)
        .traverse(
            new BreadthFirstSearch.Visitor() {
              int currentDistance = -1;

              @Override
              public boolean visit(Territory territory, int distance) {
                if (enemyCapitalsAndFactories.contains(territory)) {
                  found.add(territory);
                }
                if (distance != currentDistance) {
                  currentDistance = distance;
                  // When we reach a new distance, check if we should end the search.
                  if (!shouldContinueSearch()) {
                    return false;
                  }
                }
                return true;
              }

              public boolean shouldContinueSearch() {
                return currentDistance <= MIN_FACTORY_CHECK_DISTANCE || found.isEmpty();
              }
            });
    return found;
  }
}
