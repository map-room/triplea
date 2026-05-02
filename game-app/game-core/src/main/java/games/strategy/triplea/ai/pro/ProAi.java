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
}
