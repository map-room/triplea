package games.strategy.triplea.ai.pro.util;

import static games.strategy.triplea.delegate.GameDataTestUtil.infantry;
import static games.strategy.triplea.delegate.GameDataTestUtil.territory;
import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.odds.calculator.AggregateResults;
import games.strategy.triplea.odds.calculator.IBattleCalculator;
import games.strategy.triplea.xml.TestMapGameData;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link ProOddsCalculator#estimateAttackBattleResults} and {@link
 * ProOddsCalculator#estimateDefendBattleResults} skip the expensive Monte Carlo simulation when the
 * strength difference is decisive in either direction. These fast paths are the key optimisation
 * for the O(territories × trials) cliff in {@code
 * ProNonCombatMoveAi.determineIfMoveTerritoriesCanBeHeld} (see map-room/map-room#2561).
 */
class ProOddsCalculatorFastPathTest {

  private GameData data;
  private GamePlayer germans;
  private GamePlayer russians;
  private Territory germany;
  private AtomicInteger simCalls;
  private ProOddsCalculator calc;
  private ProData proData;

  @BeforeEach
  void setUp() throws Exception {
    data = TestMapGameData.GLOBAL1940.getGameData();
    germany = territory("Germany", data);
    germans = data.getPlayerList().getPlayerId("Germans");
    russians = data.getPlayerList().getPlayerId("Russians");

    simCalls = new AtomicInteger();
    calc = new ProOddsCalculator(countingCalc(simCalls));
    proData = proDataWithGameData(data);
  }

  @Test
  void estimateAttackBattleResults_noSimWhenDefendersDominate() {
    // 1 attacker vs 20 defenders — strength difference << 45, so fast-path must fire.
    final List<Unit> attackers = infantry(data).create(1, russians);
    final List<Unit> defenders = infantry(data).create(20, germans);

    calc.estimateAttackBattleResults(proData, germany, attackers, defenders, List.of());

    assertThat(simCalls.get())
        .as("estimateAttackBattleResults must skip simulation when defenders dominate")
        .isEqualTo(0);
  }

  @Test
  void estimateDefendBattleResults_noSimWhenAttackersDominate() {
    // 20 attackers vs 1 defender — strength difference >> 55, so fast-path must fire.
    final List<Unit> attackers = infantry(data).create(20, russians);
    final List<Unit> defenders = infantry(data).create(1, germans);

    calc.estimateDefendBattleResults(proData, germany, attackers, defenders, List.of());

    assertThat(simCalls.get())
        .as("estimateDefendBattleResults must skip simulation when attackers dominate")
        .isEqualTo(0);
  }

  @Test
  void estimateAttackBattleResults_proTerritoryOverload_noSimWhenDefendersDominate() {
    // Mirrors the ProTerritory overload added for the NCM "Move air units" section (line 2132).
    final List<Unit> attackers = infantry(data).create(1, russians);
    final List<Unit> defenders = infantry(data).create(20, germans);

    final ProTerritory proTerritory = new ProTerritory(germany, new ProData());
    proTerritory.setMaxEnemyUnits(attackers);

    calc.estimateAttackBattleResults(proData, proTerritory, defenders);

    assertThat(simCalls.get())
        .as(
            "estimateAttackBattleResults (ProTerritory overload) must skip simulation when defenders dominate")
        .isEqualTo(0);
  }

  @Test
  void estimateDefendBattleResults_proTerritoryOverload_noSimWhenAttackersDominate() {
    // Mirrors the ProTerritory overload used in the NCM "Move air units" section (line 2152).
    final List<Unit> attackers = infantry(data).create(20, russians);
    final List<Unit> defenders = infantry(data).create(1, germans);

    final ProTerritory proTerritory = new ProTerritory(germany, new ProData());
    proTerritory.setMaxEnemyUnits(attackers);

    calc.estimateDefendBattleResults(proData, proTerritory, defenders);

    assertThat(simCalls.get())
        .as(
            "estimateDefendBattleResults (ProTerritory overload) must skip simulation when attackers dominate")
        .isEqualTo(0);
  }

  private static ProData proDataWithGameData(final GameData data) throws Exception {
    final ProData proData = new ProData();
    final Field f = ProData.class.getDeclaredField("data");
    f.setAccessible(true);
    f.set(proData, data);
    return proData;
  }

  /** Returns an {@link IBattleCalculator} that increments {@code counter} on each call. */
  private static IBattleCalculator countingCalc(final AtomicInteger counter) {
    return (attacker,
        defender,
        location,
        attacking,
        defending,
        bombarding,
        territoryEffects,
        retreatWhenOnlyAirLeft,
        runCount) -> {
      counter.incrementAndGet();
      return new AggregateResults(0);
    };
  }
}
