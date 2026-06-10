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
 * Regression test for map-room#2623: ProAi noncombat planner proposes US sea moves into Europe-map
 * sea zones (numbered ≥ 83) that are not adjacent to US-controlled territories, while the US is
 * still neutral with all axis powers.
 *
 * <p>Root cause: {@code territoryCanMoveSeaUnits} applied Rule 1 (Japan-adjacent restriction) but
 * not Rule 2 (Europe-map restriction). The engine's {@code getUSNeutralityRestriction} Rule 2
 * blocks US sea units from SZ ≥ 83 not adjacent to US-controlled land whenever the US is neutral
 * with ALL axis powers (Germany, Italy, AND Japan). The AI planner proposed those moves, the engine
 * soft-rejected them, and US Atlantic ships sat idle.
 *
 * <p>Fix: {@code ProMatches.territoryCanMoveSeaUnits} now excludes SZ ≥ 83 non-US-adjacent zones
 * when Americans is neutral with all three axis powers. Mirrors engine movement-validator.ts {@code
 * getUSNeutralityRestriction} Rule 2.
 *
 * <p>Sibling of map-room#2619 (Rule 1).
 */
public class ProNoncombatEuropeNeutralityTest {

  private static final int EUROPE_MAP_THRESHOLD = 83;

  private GameData data;
  private GamePlayer americans;
  private GamePlayer germany;
  private GamePlayer italy;
  private GamePlayer japan;

  @BeforeEach
  void setUp() {
    ClientSetting.setPreferences(new MemoryPreferences());
    data = TestMapGameData.GLOBAL1940.getGameData();
    americans = data.getPlayerList().getPlayerId("Americans");
    germany = data.getPlayerList().getPlayerId("Germans");
    italy = data.getPlayerList().getPlayerId("Italians");
    japan = data.getPlayerList().getPlayerId("Japanese");
  }

  /**
   * Predicate-level regression: while neutral with all axis (the G40 round-1 default), {@code
   * ProMatches.territoryCanMoveSeaUnits(americans, false)} must return {@code false} for Europe-map
   * sea zones (SZ ≥ 83) that are not adjacent to a US-controlled territory.
   *
   * <p>Before the fix: the predicate returned {@code true} for those zones. After the fix: {@code
   * false}, so the planner never proposes those zones.
   */
  @Test
  void seaUnitPredicateExcludesEuropeZonesNotAdjacentToUsWhenNeutralWithAllAxis() {
    assumeFalse(
        americans.isAtWar(germany) || americans.isAtWar(italy) || americans.isAtWar(japan),
        "This test only applies when Americans is neutral with all axis powers");

    final Set<Territory> restrictedZones = collectRestrictedEuropeZones();
    assumeFalse(
        restrictedZones.isEmpty(),
        "G40 must have Europe-map sea zones that are not adjacent to US-controlled territory");

    final Predicate<Territory> canMoveSea = ProMatches.territoryCanMoveSeaUnits(americans, false);
    for (final Territory sz : restrictedZones) {
      assertThat(canMoveSea.test(sz))
          .as(
              "territoryCanMoveSeaUnits must exclude Europe-map zone '"
                  + sz.getName()
                  + "' (not adjacent to US) when Americans is neutral with all axis")
          .isFalse();
    }
  }

  /**
   * Regression guard: once Americans is at war with any axis power, the Europe-map restriction
   * lifts. At least one previously-restricted zone must become plannable.
   */
  @Test
  void seaUnitPredicateAllowsEuropeZonesOnceAtWarWithAnyAxis() {
    // Declare war on Germany only — restriction must lift even though Italy and Japan are
    // still neutral (mirrors the Rule 2 condition: neutralWithAllAxis → AND, so one war lifts it).
    data.performChange(
        ChangeFactory.relationshipChange(
            americans,
            germany,
            data.getRelationshipTracker().getRelationshipType(americans, germany),
            data.getRelationshipTypeList().getRelationshipType("War")));

    final Set<Territory> restrictedZones = collectRestrictedEuropeZones();
    assumeFalse(
        restrictedZones.isEmpty(),
        "G40 must have Europe-map sea zones that are not adjacent to US-controlled territory");

    final Predicate<Territory> canMoveSea = ProMatches.territoryCanMoveSeaUnits(americans, false);
    final boolean anyNowAllowed = restrictedZones.stream().anyMatch(canMoveSea);
    assertThat(anyNowAllowed)
        .as(
            "At least one formerly-restricted Europe-map zone must be reachable once "
                + "Americans is at war with Germany (Rule 2 restriction requires neutral with ALL axis)")
        .isTrue();
  }

  /**
   * Integration-level regression: running the ProAi noncombat planner for round-1 Americans
   * (neutral with all axis) must produce zero sea-unit moves that end in a Europe-map sea zone not
   * adjacent to a US-controlled territory.
   */
  @Test
  void noncombatPlanContainsNoRestrictedEuropeSeaMoves() {
    assumeFalse(
        americans.isAtWar(germany) || americans.isAtWar(italy) || americans.isAtWar(japan),
        "This test only applies when Americans is neutral with all axis powers");

    final ProAi proAi = new ProAi("Test Americans", "Americans AI");
    final PlayerBridge playerBridgeMock = Mockito.mock(PlayerBridge.class);
    proAi.initialize(playerBridgeMock, americans);
    when(playerBridgeMock.getGameData()).thenReturn(data);

    final List<MoveDescription> dispatched = new ArrayList<>();
    final IMoveDelegate moveDelegate = Mockito.mock(IMoveDelegate.class);
    when(moveDelegate.performMove(any()))
        .then(
            inv -> {
              dispatched.add(inv.getArgument(0));
              return Optional.empty();
            });

    data.getSequence().setRoundAndStep(1, "Non Combat Move", americans);
    proAi.invokeNonCombatMoveForSidecar(moveDelegate, data, americans);

    final Set<Territory> restrictedZones = collectRestrictedEuropeZones();
    final List<MoveDescription> violations =
        dispatched.stream()
            .filter(
                m ->
                    m.getRoute().getStart().isWater()
                        && m.getRoute().getEnd().isWater()
                        && restrictedZones.contains(m.getRoute().getEnd()))
            .toList();
    assertThat(violations)
        .as(
            "ProAi must not plan sea moves to Europe-map zones not adjacent to US while neutral; "
                + "all dispatched: "
                + dispatched)
        .isEmpty();
  }

  /**
   * Returns the set of sea zones that Rule 2 restricts: SZ ≥ 83 AND not adjacent to any
   * US-controlled land territory.
   */
  private Set<Territory> collectRestrictedEuropeZones() {
    final Set<Territory> zones = new HashSet<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater()) {
        continue;
      }
      final String name = t.getName();
      if (!name.endsWith(" Sea Zone")) {
        continue;
      }
      try {
        final int zoneNum =
            Integer.parseInt(name.substring(0, name.length() - " Sea Zone".length()).trim());
        if (zoneNum < EUROPE_MAP_THRESHOLD) {
          continue;
        }
      } catch (final NumberFormatException ignored) {
        continue;
      }
      // Not adjacent to any US-controlled land territory.
      final boolean adjToUs =
          data.getMap().getNeighbors(t).stream()
              .anyMatch(adj -> !adj.isWater() && adj.isOwnedBy(americans));
      if (!adjToUs) {
        zones.add(t);
      }
    }
    return zones;
  }
}
