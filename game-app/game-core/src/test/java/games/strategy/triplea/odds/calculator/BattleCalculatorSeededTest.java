package games.strategy.triplea.odds.calculator;

import static games.strategy.triplea.delegate.GameDataTestUtil.germans;
import static games.strategy.triplea.delegate.GameDataTestUtil.infantry;
import static games.strategy.triplea.delegate.GameDataTestUtil.russians;
import static org.junit.jupiter.api.Assertions.assertEquals;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.delegate.TerritoryEffectHelper;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Behaviour-preserving gate for battle-sim speedups (map-room#3695). Seeded {@link
 * BattleCalculator} is a pure function of (gamestate, seed, runCount); two independent instances
 * must agree exactly, and the numbers must stay pinned if a later change is meant not to
 * approximate.
 */
class BattleCalculatorSeededTest extends AbstractClientSettingTestCase {

  private static final long SEED = 42L;
  private static final int RUNS = 200;

  private static AggregateResults run(final GameData gameData) {
    final Territory germany = gameData.getMap().getTerritoryOrNull("Germany");
    final GamePlayer russians = russians(gameData);
    final GamePlayer germans = germans(gameData);
    final List<Unit> attacking = infantry(gameData).create(8, russians);
    final List<Unit> defending = infantry(gameData).create(6, germans);
    final BattleCalculator calculator = new BattleCalculator(gameData, SEED);
    return calculator.calculate(
        russians,
        germans,
        germany,
        attacking,
        defending,
        List.of(),
        TerritoryEffectHelper.getEffects(germany),
        false,
        RUNS);
  }

  @Test
  void twoIndependentSeededCalculatorsAgreeExactly() {
    final GameData data = TestMapGameData.GLOBAL1940.getGameData();
    final AggregateResults a = run(data);
    final AggregateResults b = run(TestMapGameData.GLOBAL1940.getGameData());
    assertEquals(a.getAttackerWinPercent(), b.getAttackerWinPercent());
    assertEquals(a.getDefenderWinPercent(), b.getDefenderWinPercent());
    assertEquals(a.getDrawPercent(), b.getDrawPercent());
    assertEquals(a.getAverageAttackingUnitsLeft(), b.getAverageAttackingUnitsLeft());
    assertEquals(a.getAverageDefendingUnitsLeft(), b.getAverageDefendingUnitsLeft());

    // Pinned from a run after the stage-3 upstream sync, which moved PlainRandomSource from
    // MersenneTwister to upstream's SplittableRandom; the stream changed, so the old pins (77/120/3
    // of 200) moved with it. 200 seeded trials: 88 attacker wins, 110 defender, 2 draws. Any drift
    // from here means the change is no longer behaviour-preserving.
    assertEquals(0.44, a.getAttackerWinPercent(), 1e-9);
    assertEquals(0.55, a.getDefenderWinPercent(), 1e-9);
    assertEquals(0.01, a.getDrawPercent(), 1e-9);
    assertEquals(1.715, a.getAverageAttackingUnitsLeft(), 1e-9);
    assertEquals(1.88, a.getAverageDefendingUnitsLeft(), 1e-9);

    // Pinned the same way, added in map-room#3698. getAverage*UnitsRemaining() picks the single
    // simulated result closest to the average — it is the observable output of
    // getBattleResultsClosestToAverage(), which #3698 de-quadratifies. If that hoist changed
    // which result is chosen, these two sizes would move.
    assertEquals(0, a.getAverageAttackingUnitsRemaining().size());
    assertEquals(2, a.getAverageDefendingUnitsRemaining().size());
    assertEquals(
        a.getAverageAttackingUnitsRemaining().size(), b.getAverageAttackingUnitsRemaining().size());
    assertEquals(
        a.getAverageDefendingUnitsRemaining().size(), b.getAverageDefendingUnitsRemaining().size());
    assertEquals(5.16, a.getAverageBattleRoundsFought(), 1e-9);
    assertEquals(3.897_727_272_727_272_5, a.getAverageAttackingUnitsLeftWhenAttackerWon(), 1e-9);
    assertEquals(3.418_181_818_181_818, a.getAverageDefendingUnitsLeftWhenDefenderWon(), 1e-9);
  }
}
