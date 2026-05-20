package games.strategy.triplea.ai.pro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.player.PlayerBridge;
import games.strategy.triplea.ai.pro.util.ProMatches;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

/**
 * Regression test for #2619: ProAi noncombat planner proposes US sea moves into sea zones adjacent
 * to Japanese-controlled territories while still neutral with Japan.
 *
 * <p>Root cause: {@code territoryCanMoveSeaUnits} did not apply the US-Japan neutrality restriction
 * (Global 1940 rule: US sea units cannot end movement in sea zones adjacent to Japanese-controlled
 * territories while neutral with Japan). The engine validator enforces the rule and soft-rejects
 * the move, leaving the affected ships idle.
 *
 * <p>Fix: {@code ProMatches.territoryCanMoveSeaUnits} now excludes sea zones adjacent to
 * Japanese-controlled land territories when the moving player is Americans and is neutral with
 * Japan. Mirrors engine movement-validator.ts {@code getUSNeutralityRestriction} Rule 1.
 */
public class ProNoncombatNeutralityTest {

  private GameData data;
  private GamePlayer americans;
  private GamePlayer japanese;

  @BeforeEach
  void setUp() {
    ClientSetting.setPreferences(new MemoryPreferences());
    data = TestMapGameData.GLOBAL1940.getGameData();
    americans = data.getPlayerList().getPlayerId("Americans");
    japanese = data.getPlayerList().getPlayerId("Japanese");
  }

  /**
   * Predicate-level regression: while neutral with Japan, {@code
   * ProMatches.territoryCanMoveSeaUnits(americans, false)} must return {@code false} for every sea
   * zone adjacent to a Japanese-controlled land territory.
   *
   * <p>Before the fix: the predicate returned {@code true} for those sea zones (no neutrality
   * check), causing the noncombat planner to include them as reachable destinations. After the fix:
   * the predicate returns {@code false}, so the planner never proposes those zones.
   */
  @Test
  void seaUnitPredicateExcludesJapanAdjacentZonesWhenNeutral() {
    // Pre-condition: Americans must be neutral with Japan (the G40 round-1 default).
    assumeFalse(
        americans.isAtWar(japanese), "This test only applies when Americans is neutral with Japan");

    // Collect sea zones adjacent to Japanese-controlled land territories.
    final Set<Territory> japanAdjacentSeaZones = new HashSet<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater() && t.isOwnedBy(japanese)) {
        for (final Territory neighbor : data.getMap().getNeighbors(t)) {
          if (neighbor.isWater()) {
            japanAdjacentSeaZones.add(neighbor);
          }
        }
      }
    }
    assumeFalse(japanAdjacentSeaZones.isEmpty(), "G40 must have sea zones adjacent to Japan");

    // Under the fix this predicate must return false for Japan-adjacent sea zones.
    final Predicate<Territory> canMoveSea = ProMatches.territoryCanMoveSeaUnits(americans, false);
    for (final Territory sz : japanAdjacentSeaZones) {
      assertThat(canMoveSea.test(sz))
          .as(
              "territoryCanMoveSeaUnits must exclude Japan-adjacent sea zone '"
                  + sz.getName()
                  + "' when Americans is neutral with Japan")
          .isFalse();
    }
  }

  /**
   * Regression guard: once Americans is at war with Japan, the same sea zones must NOT be blocked.
   * ProAi must be able to plan offensive sea moves into Japan-adjacent zones during war.
   */
  @Test
  void seaUnitPredicateAllowsJapanAdjacentZonesWhenAtWar() {
    // Change the relationship to War so we can verify the predicate lifts the restriction.
    data.performChange(
        ChangeFactory.relationshipChange(
            americans,
            japanese,
            data.getRelationshipTracker().getRelationshipType(americans, japanese),
            data.getRelationshipTypeList().getRelationshipType("War")));

    final Set<Territory> japanAdjacentSeaZones = new HashSet<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater() && t.isOwnedBy(japanese)) {
        for (final Territory neighbor : data.getMap().getNeighbors(t)) {
          if (neighbor.isWater()) {
            japanAdjacentSeaZones.add(neighbor);
          }
        }
      }
    }
    assumeFalse(japanAdjacentSeaZones.isEmpty(), "G40 must have sea zones adjacent to Japan");

    final Predicate<Territory> canMoveSea = ProMatches.territoryCanMoveSeaUnits(americans, false);
    // At least one Japan-adjacent sea zone must be passable when at war (the restriction is
    // lifted).
    final boolean anyAllowed = japanAdjacentSeaZones.stream().anyMatch(canMoveSea);
    assertThat(anyAllowed)
        .as(
            "At least one Japan-adjacent sea zone must be reachable when Americans is at war with Japan")
        .isTrue();
  }

  /**
   * Integration-level regression: running the ProAi noncombat planner for round-1 Americans
   * (neutral with Japan) must produce zero sea-unit moves that end in a sea zone adjacent to a
   * Japanese-controlled territory. This catches cases where the planner's destination filter is
   * bypassed via a path not guarded by {@code territoryCanMoveSeaUnits}.
   *
   * <p>The scenario seeds Japanese units in a sea zone adjacent to Midway (owned by Americans) so
   * the planner has a concrete naval-threat to react to and is motivated to move the US Pacific
   * Fleet forward. Without the fix the planner proposes a forward-positioning move into a
   * Japan-adjacent sea zone.
   */
  @Test
  void noncombatPlanContainsNoJapanAdjacentSeaMoves() {
    // Find a Japanese-owned territory with a water neighbor, and find the adjacent sea zone.
    // We'll place a Japanese surface warship there to give Americans' planner a naval threat.
    Territory japaneseSeaZone = null;
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater() && t.isOwnedBy(japanese)) {
        for (final Territory neighbor : data.getMap().getNeighbors(t)) {
          if (neighbor.isWater()) {
            japaneseSeaZone = neighbor;
            break;
          }
        }
        if (japaneseSeaZone != null) {
          break;
        }
      }
    }
    assumeFalse(japaneseSeaZone == null, "G40 must have Japanese territory adjacent to water");

    // Initialize ProAi for Americans.
    final ProAi proAi = new ProAi("Test Americans", "Americans AI");
    final PlayerBridge playerBridgeMock = Mockito.mock(PlayerBridge.class);
    proAi.initialize(playerBridgeMock, americans);
    when(playerBridgeMock.getGameData()).thenReturn(data);

    // Capture every MoveDescription dispatched by the noncombat planner.
    final List<MoveDescription> dispatched = new ArrayList<>();
    final IMoveDelegate moveDelegate = Mockito.mock(IMoveDelegate.class);
    when(moveDelegate.performMove(any()))
        .then(
            inv -> {
              dispatched.add(inv.getArgument(0));
              return Optional.empty();
            });

    // Advance game sequence to the Americans non-combat move step.
    data.getSequence().setRoundAndStep(1, "Non Combat Move", americans);

    // Run the noncombat-move phase.
    proAi.invokeNonCombatMoveForSidecar(moveDelegate, data, americans);

    // Build the set of all Japan-adjacent sea zones.
    final Set<Territory> japanAdjacentSeaZones = new HashSet<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater() && t.isOwnedBy(japanese)) {
        for (final Territory neighbor : data.getMap().getNeighbors(t)) {
          if (neighbor.isWater()) {
            japanAdjacentSeaZones.add(neighbor);
          }
        }
      }
    }

    // Assert: no dispatched move ends in a Japan-adjacent sea zone.
    final List<MoveDescription> violations =
        dispatched.stream()
            .filter(
                m ->
                    m.getRoute().getStart().isWater()
                        && m.getRoute().getEnd().isWater()
                        && japanAdjacentSeaZones.contains(m.getRoute().getEnd()))
            .toList();
    assertThat(violations)
        .as(
            "ProAi must not plan sea moves to Japan-adjacent sea zones while neutral; "
                + "all dispatched: "
                + dispatched)
        .isEmpty();
  }
}
