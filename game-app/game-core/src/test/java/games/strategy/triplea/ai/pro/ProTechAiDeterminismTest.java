package games.strategy.triplea.ai.pro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.triplea.Constants;
import games.strategy.triplea.delegate.data.TechResults;
import games.strategy.triplea.delegate.remote.ITechDelegate;
import games.strategy.triplea.xml.TestMapGameData;
import java.lang.reflect.Field;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ProTechAiDeterminismTest {

  /** Records every call to {@link Random}'s internal next() generator. */
  private static final class CountingRandom extends Random {
    int callCount = 0;

    CountingRandom(final long seed) {
      super(seed);
    }

    @Override
    protected int next(final int bits) {
      callCount++;
      return super.next(bits);
    }
  }

  /**
   * Verifies that {@link ProTechAi#tech} drives all random decisions through the injected {@link
   * ProData#getRng()} rather than {@link java.util.concurrent.ThreadLocalRandom#current()}.
   *
   * <p>Strategy: inject a {@link CountingRandom} spy into {@link ProData} via reflection, call
   * tech(), and assert the spy was consulted. Before the fix (ThreadLocalRandom path), the spy gets
   * 0 calls — RED. After the fix (proData.getRng() path), the spy gets ≥1 call — GREEN.
   */
  @Test
  void techDrivenByProDataRng() throws Exception {
    final GameData data = TestMapGameData.GLOBAL1940.getGameData();
    data.getProperties().set(Constants.WW2V3_TECH_MODEL, Boolean.TRUE);
    final GamePlayer player = data.getPlayerList().getPlayerId("Germans");

    final ProData proData = new ProData();
    final CountingRandom spy = new CountingRandom(42L);
    injectRng(proData, spy);

    final ITechDelegate delegate = mock(ITechDelegate.class);
    when(delegate.rollTech(anyInt(), any(), anyInt(), any())).thenReturn(new TechResults("stub"));

    ProTechAi.tech(delegate, data, player, proData);

    assertThat(spy.callCount)
        .as("ProTechAi.tech must use proData.getRng(), not ThreadLocalRandom")
        .isGreaterThan(0);
  }

  private static void injectRng(final ProData proData, final Random rng) throws Exception {
    final Field f = ProData.class.getDeclaredField("rng");
    f.setAccessible(true);
    f.set(proData, rng);
  }
}
