package org.triplea.ai.sidecar.exec;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.player.PlayerBridge;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.settings.ClientSetting;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.session.Session;
import org.triplea.ai.sidecar.session.SessionKey;

class ExecutorSupportTest {

  private static CanonicalGameData canonical;

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load();
  }

  /**
   * Subclass of {@link ProAi} that counts how many times {@code initialize} is actually invoked,
   * allowing the test to assert exactly one initialization under concurrent load.
   */
  private static final class CountingProAi extends ProAi {
    final AtomicInteger initCount = new AtomicInteger(0);

    CountingProAi(final String name, final String nation) {
      super(name, nation);
    }

    @Override
    public void initialize(final PlayerBridge playerBridge, final GamePlayer gamePlayer) {
      initCount.incrementAndGet();
      super.initialize(playerBridge, gamePlayer);
    }
  }

  /**
   * Fix 1 regression: {@link ExecutorSupport#ensureProAiInitialized} must initialize {@link ProAi}
   * exactly once under concurrent load on the same {@link Session}. Without synchronization, two
   * threads that both observe {@code getGamePlayer() == null} before either calls {@code
   * initialize} would both proceed — double-initializing and potentially corrupting ProAi state.
   */
  @Test
  void ensureProAiInitialized_initializesExactlyOnceUnderConcurrentLoad() throws Exception {
    final int threadCount = 16;
    final GameData data = canonical.cloneForSession();
    final CountingProAi proAi = new CountingProAi("sidecar-race-test", "Germans");
    final Session session =
        new Session(
            "s-race-" + UUID.randomUUID(),
            new SessionKey("g1", "Germans", 1),
            42L,
            proAi,
            data,
            new ConcurrentHashMap<>(),
            Executors.newSingleThreadExecutor());

    final GamePlayer player = data.getPlayerList().getPlayerId("Germans");
    assertThat(player).as("Germans player must exist in canonical game data").isNotNull();

    // All threads wait at the latch before calling ensureProAiInitialized,
    // maximizing the race window where multiple threads could observe null simultaneously.
    final CountDownLatch startGate = new CountDownLatch(1);
    final ExecutorService pool = Executors.newFixedThreadPool(threadCount);
    try {
      for (int i = 0; i < threadCount; i++) {
        pool.submit(
            () -> {
              try {
                startGate.await();
              } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
              }
              ExecutorSupport.ensureProAiInitialized(session, player);
            });
      }
      // Release all threads simultaneously.
      startGate.countDown();
    } finally {
      pool.shutdown();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS))
          .as("All threads should finish within 10 s")
          .isTrue();
    }

    assertThat(proAi.initCount.get())
        .as("initialize() must be called exactly once regardless of concurrent entrants")
        .isEqualTo(1);
    assertThat(proAi.getGamePlayer())
        .as("getGamePlayer() must be non-null after initialization")
        .isNotNull();
  }
}
