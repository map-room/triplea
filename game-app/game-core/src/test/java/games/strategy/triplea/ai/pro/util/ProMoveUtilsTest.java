package games.strategy.triplea.ai.pro.util;

import static games.strategy.triplea.delegate.GameDataTestUtil.americans;
import static games.strategy.triplea.delegate.GameDataTestUtil.armour;
import static games.strategy.triplea.delegate.GameDataTestUtil.bomber;
import static games.strategy.triplea.delegate.GameDataTestUtil.germans;
import static games.strategy.triplea.delegate.GameDataTestUtil.infantry;
import static games.strategy.triplea.delegate.GameDataTestUtil.territory;
import static games.strategy.triplea.delegate.GameDataTestUtil.transport;
import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameState;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProMoveUtilsTest {
  private final GameState data = TestMapGameData.WW2V3_1941.getGameData();
  private final Route route = new Route(territory("Germany", data), territory("Poland", data));
  private final Unit bomber = bomber(data).create(germans(data));
  private final Unit inf = infantry(data).create(germans(data));
  private final Unit inf2 = infantry(data).create(germans(data));
  private final Unit tank = armour(data).create(germans(data));

  @BeforeEach
  void enableParatroopers() {
    germans(data).getTechAttachment().setParatroopers("true");
  }

  private List<MoveDescription> moves(final MoveDescription... moves) {
    return new ArrayList<>(List.of(moves));
  }

  @Test
  void mergeKeepsBomberAndInfantryMovesSeparate() {
    final var moves =
        moves(
            new MoveDescription(List.of(bomber), route), new MoveDescription(List.of(inf), route));

    ProMoveUtils.mergeSameRouteMoves(moves);

    assertThat(moves).hasSize(2);
  }

  @Test
  void mergeKeepsAirTransportApartFromInfantryWhenOtherUnitsAlsoMove() {
    final var moves =
        moves(
            new MoveDescription(List.of(bomber, tank), route),
            new MoveDescription(List.of(inf), route));

    ProMoveUtils.mergeSameRouteMoves(moves);

    assertThat(moves).hasSize(2);
  }

  @Test
  void mergeStillMergesInfantryAndArmourOnSameRoute() {
    final var moves =
        moves(new MoveDescription(List.of(inf), route), new MoveDescription(List.of(tank), route));

    ProMoveUtils.mergeSameRouteMoves(moves);

    assertThat(moves).hasSize(1);
    assertThat(moves.get(0).getUnits()).containsExactlyInAnyOrder(inf, tank);
  }

  @Test
  void mergeStillMergesTwoBombers() {
    final Unit bomber2 = bomber(data).create(germans(data));
    final var moves =
        moves(
            new MoveDescription(List.of(bomber), route),
            new MoveDescription(List.of(bomber2), route));

    ProMoveUtils.mergeSameRouteMoves(moves);

    assertThat(moves).hasSize(1);
  }

  @Test
  void mergeLeavesIntentionalAirliftMoveAlone() {
    final var airlift =
        new MoveDescription(List.of(bomber, inf), route, Map.of(), Map.of(bomber, List.of(inf)));
    final var moves = moves(airlift, new MoveDescription(List.of(inf2), route));

    ProMoveUtils.mergeSameRouteMoves(moves);

    assertThat(moves).hasSize(2).contains(airlift);
  }

  /**
   * Map-room#4675 signature A, on the Anniversary 1941 board: two American transports share the
   * first leg 11 SZ to 12 SZ and then part ways. Folding the first transport's 11 to 12 leg into
   * the second's put it after the first transport's own 12 to 7 and 7 to Northwestern Europe legs,
   * so the plan sailed the transport out of 12 SZ before it had got there.
   */
  @Test
  void mergeKeepsEachTransportsLegsInSailingOrder() {
    final var us = americans(data);
    final Unit transportA = transport(data).create(us);
    final Unit cargoA = infantry(data).create(us);
    final Unit transportB = transport(data).create(us);
    final Unit cargoB = infantry(data).create(us);
    final var sz11 = territory("11 Sea Zone", data);
    final var sz12 = territory("12 Sea Zone", data);
    final var moves =
        moves(
            new MoveDescription(List.of(transportA, cargoA), new Route(sz11, sz12)),
            new MoveDescription(
                List.of(transportA, cargoA), new Route(sz12, territory("7 Sea Zone", data))),
            new MoveDescription(
                List.of(cargoA),
                new Route(territory("7 Sea Zone", data), territory("Northwestern Europe", data))),
            new MoveDescription(List.of(transportB, cargoB), new Route(sz11, sz12)),
            new MoveDescription(
                List.of(transportB, cargoB), new Route(sz12, territory("13 Sea Zone", data))),
            new MoveDescription(
                List.of(cargoB),
                new Route(territory("13 Sea Zone", data), territory("France", data))));

    ProMoveUtils.mergeSameRouteMoves(moves);

    for (final Unit unit : List.of(transportA, cargoA, transportB, cargoB)) {
      final var legs = moves.stream().filter(m -> m.getUnits().contains(unit)).toList();
      for (int i = 1; i < legs.size(); i++) {
        assertThat(legs.get(i).getRoute().getStart())
            .as("%s leg %s starts where the previous leg ended", unit, i)
            .isEqualTo(legs.get(i - 1).getRoute().getEnd());
      }
    }
  }
}
