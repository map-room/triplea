package games.strategy.triplea.ai.pro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.ai.pro.util.ProMatches;
import games.strategy.triplea.ai.pro.util.ProUtils;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

/**
 * Regression tests for map-room#2876: {@link ProMatches#territoryCanMoveAirUnits} must block
 * neutral territories (Neutral_True / Neutral_Axis / Neutral_Allies) as air-movement waypoints,
 * mirroring the engine's {@code createTraversalFilter} air branch (movement-validator.ts:278-282).
 *
 * <p>Repro: British tactical_bomber planned Caucasus → Syria via Turkey (2 hops). Turkey is a
 * Neutral_True territory in round 1. The engine costs the path at 3 (Turkey non-traversable →
 * detour via NW Persia → Iraq → Syria). The sidecar previously costed it at 2, causing a
 * soft-rejected move.
 *
 * <p>Tests:
 *
 * <ul>
 *   <li>Predicate-level: {@code territoryCanMoveAirUnits} returns {@code false} for Turkey (any
 *       neutral territory).
 *   <li>Predicate-level counter-regression: non-neutral Allied territory (Caucasus) returns {@code
 *       true}.
 *   <li>BFS-level: British air at Caucasus cannot reach Syria in 2 hops — Turkey (the only direct
 *       2-hop route) is blocked by the fix.
 * </ul>
 */
public class ProAirNeutralOverflyTest {

  private GameData data;
  private GamePlayer british;
  private Territory turkey;
  private Territory caucasus;
  private Territory unitedKingdom;

  @BeforeEach
  void setUp() {
    ClientSetting.setPreferences(new MemoryPreferences());
    data = TestMapGameData.GLOBAL1940.getGameData();
    british = data.getPlayerList().getPlayerId("British");
    turkey = data.getMap().getTerritoryOrThrow("Turkey");
    caucasus = data.getMap().getTerritoryOrThrow("Caucasus");
    unitedKingdom = data.getMap().getTerritoryOrThrow("United Kingdom");
  }

  /**
   * Predicate-level regression (map-room#2876): {@code territoryCanMoveAirUnits} must return {@code
   * false} for Turkey, which is owned by the {@code Neutral_True} player in the G40 initial setup.
   */
  @Test
  void airMovePredicate_blocksNeutralTerritoryTransit() {
    assumeTrue(
        ProUtils.isNeutralLand(turkey),
        "Precondition: Turkey must be neutral land in G40 round-1 setup");

    final Predicate<Territory> canMoveAir =
        ProMatches.territoryCanMoveAirUnits(data, british, true);

    assertThat(canMoveAir.test(turkey))
        .as("territoryCanMoveAirUnits must block Turkey (Neutral_True) as an air waypoint")
        .isFalse();
  }

  /**
   * Counter-regression: a British-owned territory (United Kingdom) must still be traversable by
   * British air — the neutral check must not block own territory.
   */
  @Test
  void airMovePredicate_allowsOwnedTerritoryTransit() {
    assumeFalse(
        ProUtils.isNeutralLand(unitedKingdom),
        "Precondition: United Kingdom must NOT be neutral land in G40 round-1 setup");

    final Predicate<Territory> canMoveAir =
        ProMatches.territoryCanMoveAirUnits(data, british, true);

    assertThat(canMoveAir.test(unitedKingdom))
        .as("territoryCanMoveAirUnits must allow United Kingdom (British-owned) as an air waypoint")
        .isTrue();
  }

  /**
   * BFS-level regression (map-room#2876): British air at Caucasus must NOT be able to reach Syria
   * in 2 hops.
   *
   * <p>Turkey sits directly between Caucasus and Syria (Caucasus ↔ Turkey ↔ Syria = 2 hops). With
   * Turkey blocked, the 2-hop path is impossible. This is the exact geometry of the soft-reject
   * repro in the issue.
   */
  @Test
  void airBfs_cannotReachSyriaInTwoHopsViaTurkey() {
    // Verify the G40 geometry preconditions before asserting.
    final Territory syria = data.getMap().getTerritoryOrThrow("Syria");
    assumeTrue(
        ProUtils.isNeutralLand(turkey), "Precondition: Turkey must be neutral land in G40 setup");
    assumeTrue(
        data.getMap().getNeighbors(caucasus).contains(turkey),
        "Precondition: Caucasus must be adjacent to Turkey");
    assumeTrue(
        data.getMap().getNeighbors(turkey).contains(syria),
        "Precondition: Turkey must be adjacent to Syria");
    assumeFalse(
        data.getMap().getNeighbors(caucasus).contains(syria),
        "Precondition: Caucasus must NOT be directly adjacent to Syria (requires 2+ hops)");

    final Predicate<Territory> canMoveAir =
        ProMatches.territoryCanMoveAirUnits(data, british, true);
    final Set<Territory> twoHopReach = data.getMap().getNeighbors(caucasus, 2, canMoveAir);

    assertThat(twoHopReach)
        .as(
            "British air from Caucasus must not reach Syria in 2 hops — Turkey (neutral) blocks"
                + " the direct path")
        .doesNotContain(syria);
  }
}
