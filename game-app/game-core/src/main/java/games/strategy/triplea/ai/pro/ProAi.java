package games.strategy.triplea.ai.pro;

import games.strategy.engine.data.GameData;
import games.strategy.triplea.odds.calculator.ConcurrentBattleCalculator;

public class ProAi extends AbstractProAi {
  private final ConcurrentBattleCalculator concurrentCalc;

  public ProAi(final String name, final String playerLabel) {
    this(name, playerLabel, new ConcurrentBattleCalculator());
  }

  private ProAi(
      final String name, final String playerLabel, final ConcurrentBattleCalculator calc) {
    super(name, calc, new ProData(), playerLabel);
    this.concurrentCalc = calc;
  }

  @Override
  public void stopGame() {
    super.stopGame(); // absolutely MUST call super.stopGame() first
    concurrentCalc.cancel();
    concurrentCalc.setGameData(null);
  }

  @Override
  protected void prepareData(final GameData data) {
    concurrentCalc.setGameData(data);
  }

  /**
   * Switch this ProAi's battle calculator into deterministic single-worker mode (see {@link
   * games.strategy.triplea.odds.calculator.ConcurrentBattleCalculator#setSeed} javadoc). Must be
   * called before the first {@link #prepareData} so the seed is in place when worker construction
   * runs. Used by the AI sidecar for {@code (gamestate, seed) → wire-response} purity (see
   * map-room/map-room#2376 / #2377).
   */
  public void seedBattleCalc(final long seed) {
    concurrentCalc.setSeed(seed);
  }
}
