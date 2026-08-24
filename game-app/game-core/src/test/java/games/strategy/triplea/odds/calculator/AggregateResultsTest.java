package games.strategy.triplea.odds.calculator;

import static games.strategy.triplea.delegate.GameDataTestUtil.germans;
import static games.strategy.triplea.delegate.GameDataTestUtil.infantry;
import static games.strategy.triplea.delegate.GameDataTestUtil.russians;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.delegate.battle.BattleResults;
import games.strategy.triplea.util.TuvCostsCalculator;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.triplea.java.collections.IntegerMap;
import org.triplea.util.Tuple;

public class AggregateResultsTest {
  /**
   * Regression guard for map-room#3698. {@code getBattleResultsClosestToAverage()} compares every
   * result against the set-wide averages. Those averages used to be read from inside the {@link
   * java.util.Comparator} key extractor, which re-runs on both operands of every comparison — so
   * picking the representative battle was quadratic in the simulation count, and each step
   * allocated a fresh {@code double[n]} and ran a full {@code Mean}. It was ~6.5% of all AI sidecar
   * CPU. The averages are constants of the result set, so each must be computed exactly once per
   * call; a timing assertion would be noise, but the call count is exact.
   */
  @Test
  void averagesAreComputedOncePerClosestToAverageQuery() {
    final int[] attackingCalls = {0};
    final int[] defendingCalls = {0};
    final AggregateResults results =
        new AggregateResults(0) {
          @Override
          public double getAverageAttackingUnitsLeft() {
            attackingCalls[0]++;
            return super.getAverageAttackingUnitsLeft();
          }

          @Override
          public double getAverageDefendingUnitsLeft() {
            defendingCalls[0]++;
            return super.getAverageDefendingUnitsLeft();
          }
        };
    for (int i = 0; i < 50; i++) {
      final BattleResults result = mock(BattleResults.class);
      lenient().when(result.getRemainingAttackingUnits()).thenReturn(List.of());
      lenient().when(result.getRemainingDefendingUnits()).thenReturn(List.of());
      results.addResult(result);
    }

    results.getAverageAttackingUnitsRemaining();

    assertEquals(1, attackingCalls[0]);
    assertEquals(1, defendingCalls[0]);
  }

  @Test
  void testNoResultsAdded() {
    final AggregateResults results = new AggregateResults(1);

    // The methods for the TUV need some additional objects.  Note that even in an zero-result TUV
    // swing simulation, some pre-computation is done with this objects, i.e. they must be non-null.
    final GameData gameData = TestMapGameData.REVISED.getGameData();
    final GamePlayer attacker = russians(gameData);
    final List<Unit> attackingUnits = infantry(gameData).create(100, attacker);
    final GamePlayer defender = germans(gameData);
    final List<Unit> defendingUnits = infantry(gameData).create(100, defender);
    final TuvCostsCalculator tuvCalculator = new TuvCostsCalculator();
    final IntegerMap<UnitType> attackerCostsForTuv = tuvCalculator.getCostsForTuv(attacker);
    final IntegerMap<UnitType> defenderCostsForTuv = tuvCalculator.getCostsForTuv(defender);

    final Tuple<Double, Double> t =
        results.getAverageTuvOfUnitsLeftOver(attackerCostsForTuv, defenderCostsForTuv);
    assertIsNaN(t.getFirst());
    assertIsNaN(t.getSecond());
    assertIsNaN(
        results.getAverageTuvSwing(attacker, attackingUnits, defender, defendingUnits, gameData));
    assertIsNaN(results.getAverageAttackingUnitsLeft());
    assertIsNaN(results.getAverageAttackingUnitsLeftWhenAttackerWon());
    assertIsNaN(results.getAverageDefendingUnitsLeft());
    assertIsNaN(results.getAverageDefendingUnitsLeftWhenDefenderWon());
    assertIsNaN(results.getAttackerWinPercent());
    assertIsNaN(results.getDefenderWinPercent());
    assertIsNaN(results.getDrawPercent());
    assertIsNaN(results.getAverageBattleRoundsFought());
  }

  private static void assertIsNaN(final double d) {
    assertTrue(Double.isNaN(d));
  }
}
