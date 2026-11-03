package games.strategy.triplea.ai.pro;

import games.strategy.engine.data.GameData;
import games.strategy.triplea.odds.calculator.ConcurrentBattleCalculator;

public class ProAi extends AbstractProAi {
  private final ConcurrentBattleCalculator concurrentCalc;

  /** The default Pro AI. {@code beta} is false, so this is not Hard Beta. */
  public ProAi(final String name, final String playerLabel) {
    this(name, playerLabel, false);
  }

  /**
   * @param beta upstream's Hard Beta mode. The headed "Hard (AI)" player type passes true. The
   *     sidecar keeps the two-argument constructor, so its default stays the normal Pro AI.
   */
  public ProAi(final String name, final String playerLabel, final boolean beta) {
    this(name, playerLabel, beta, new ConcurrentBattleCalculator());
  }

  /**
   * Per-instance calculator, not upstream's static one (map-room#64). A static calculator shared
   * across ProAi instances breaks sidecar seeding: each request needs its own {@link
   * ConcurrentBattleCalculator#setSeed}. {@link #stopGame()} already drops this instance's
   * calculator, so the static shutdown hook is unnecessary.
   */
  private ProAi(
      final String name,
      final String playerLabel,
      final boolean beta,
      final ConcurrentBattleCalculator calc) {
    super(name, calc, new ProData(beta), playerLabel);
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
