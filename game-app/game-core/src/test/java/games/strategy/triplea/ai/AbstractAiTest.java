package games.strategy.triplea.ai;

import static games.strategy.triplea.delegate.GameDataTestUtil.british;
import static games.strategy.triplea.delegate.GameDataTestUtil.russians;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMultimap;
import games.strategy.engine.data.AllianceTracker;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.player.PlayerBridge;
import games.strategy.triplea.ai.weak.WeakAi;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.Arrays;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AbstractAiTest {

  private static final int CHOICES = 64;

  @AfterEach
  void clearRequestBinding() {
    AiRandom.clearRequest();
  }

  @Test
  void proposalFromNonAllyIsNotAlwaysAcceptedJustBecauseOfClassicNationNames() {
    final PoliticsCoinFlip flip = politicsCoinFlip();

    final boolean alwaysAccepted =
        IntStream.range(0, 200)
            .allMatch(i -> flip.ai().acceptAction(flip.proposer(), "peace?", true));

    assertThat(alwaysAccepted).isFalse();
  }

  /**
   * The politics coin-flip in {@link AbstractAi#acceptAction} must replay when the caller binds
   * {@link AiRandom} to the same seed (map-room/triplea#186). The sidecar does that per request.
   */
  @Test
  void acceptActionChoicesReplayForTheSameSeed() {
    final PoliticsCoinFlip flip = politicsCoinFlip();
    final boolean[] first = choices(flip, 186L);
    final boolean[] second = choices(flip, 186L);
    final boolean[] otherSeed = choices(flip, 187L);

    assertThat(second).containsExactly(first);
    assertThat(Arrays.equals(otherSeed, first)).isFalse();
    assertThat(boxed(first)).contains(true, false);
  }

  /**
   * {@link AiRandom#bindRequest} is per thread. Two workers drawing {@code acceptAction} at the
   * same time must each replay their own seed, which a process-wide {@link AiRandom#setSource}
   * would not.
   */
  @Test
  void acceptActionSeedsDoNotLeakAcrossThreads() throws Exception {
    final PoliticsCoinFlip first = politicsCoinFlip();
    final PoliticsCoinFlip second = politicsCoinFlip();
    final boolean[] soloA = choices(first, 11L);
    final boolean[] soloB = choices(second, 99L);
    final CyclicBarrier start = new CyclicBarrier(2);
    final boolean[][] got = new boolean[2][];

    final Thread threadA = new Thread(() -> got[0] = choicesAfterStart(first, 11L, start));
    final Thread threadB = new Thread(() -> got[1] = choicesAfterStart(second, 99L, start));
    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();

    assertThat(got[0]).containsExactly(soloA);
    assertThat(got[1]).containsExactly(soloB);
  }

  /**
   * Britain at war with Russia, and in a different alliance, so {@code acceptAction} reaches {@code
   * AiRandom.get().nextDouble()} instead of the always-accept branches.
   */
  private static PoliticsCoinFlip politicsCoinFlip() {
    final GameData data = TestMapGameData.PACT_OF_STEEL_2.getGameData();
    final GamePlayer britain = british(data);
    final GamePlayer russia = russians(data);
    final var war = data.getRelationshipTypeList().getRelationshipType("War");
    data.performChange(
        ChangeFactory.relationshipChange(
            britain,
            russia,
            data.getRelationshipTracker().getRelationshipType(britain, russia),
            war));
    final var gameDataWithSplitAlliances = mock(GameData.class);
    when(gameDataWithSplitAlliances.getAllianceTracker())
        .thenReturn(
            new AllianceTracker(ImmutableMultimap.of(britain, "Western", russia, "Eastern")));
    final var bridge = mock(PlayerBridge.class);
    when(bridge.getGameData()).thenReturn(gameDataWithSplitAlliances);
    final var ai = new WeakAi("British");
    ai.initialize(bridge, britain);
    return new PoliticsCoinFlip(ai, russia);
  }

  private static boolean[] choices(final PoliticsCoinFlip flip, final long seed) {
    AiRandom.bindRequest(seed);
    try {
      return draw(flip);
    } finally {
      AiRandom.clearRequest();
    }
  }

  private static boolean[] choicesAfterStart(
      final PoliticsCoinFlip flip, final long seed, final CyclicBarrier start) {
    AiRandom.bindRequest(seed);
    try {
      start.await(10, TimeUnit.SECONDS);
      return draw(flip);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    } finally {
      AiRandom.clearRequest();
    }
  }

  private static boolean[] draw(final PoliticsCoinFlip flip) {
    final boolean[] out = new boolean[CHOICES];
    for (int i = 0; i < out.length; i++) {
      out[i] = flip.ai().acceptAction(flip.proposer(), "peace?", true);
    }
    return out;
  }

  private static Boolean[] boxed(final boolean[] values) {
    final Boolean[] boxed = new Boolean[values.length];
    for (int i = 0; i < values.length; i++) {
      boxed[i] = values[i];
    }
    return boxed;
  }

  private record PoliticsCoinFlip(WeakAi ai, GamePlayer proposer) {}
}
