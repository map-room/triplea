package org.triplea.ai.sidecar.session;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background task that removes stale sessions every 5 minutes.
 *
 * <p>A session is stale if its {@code updatedAt} timestamp is older than 30 days. Gameover-driven
 * reaping (querying the game server to detect finished matches) is a planned Phase 3 enhancement —
 * see {@code docs/ai-sidecar-contract-v2.md} — and is not yet wired in.
 *
 * <p>The reaper is started via {@link #start()} and shut down via {@link #stop()}. For testing,
 * {@link #runOnce()} executes one reap cycle synchronously.
 */
public final class SessionReaper {

  private static final System.Logger LOG = System.getLogger(SessionReaper.class.getName());

  static final long STALE_THRESHOLD_DAYS = 30;
  static final long INTERVAL_MINUTES = 5;

  private final SessionRegistry registry;
  private final Clock clock;

  private ScheduledExecutorService scheduler;

  public SessionReaper(final SessionRegistry registry, final Clock clock) {
    this.registry = registry;
    this.clock = clock;
  }

  /** Starts the background reaper. Idempotent. */
  public synchronized void start() {
    if (scheduler != null) {
      return;
    }
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              final Thread t = new Thread(r, "sidecar-reaper");
              t.setDaemon(true);
              return t;
            });
    scheduler.scheduleAtFixedRate(
        this::runOnceSafe, INTERVAL_MINUTES, INTERVAL_MINUTES, TimeUnit.MINUTES);
    LOG.log(
        System.Logger.Level.INFO,
        "Session reaper started (interval={0}m, stale_threshold={1}d)",
        new Object[] {INTERVAL_MINUTES, STALE_THRESHOLD_DAYS});
  }

  /** Stops the background reaper. */
  public synchronized void stop() {
    if (scheduler != null) {
      scheduler.shutdownNow();
      scheduler = null;
    }
  }

  /** Runs one reap cycle. Used directly by tests and by the scheduler. */
  public void runOnce() {
    final long now = clock.millis();
    final long staleBeforeMs = now - TimeUnit.DAYS.toMillis(STALE_THRESHOLD_DAYS);

    final List<SessionKey> toDelete = registry.findStale(staleBeforeMs);

    if (!toDelete.isEmpty()) {
      LOG.log(System.Logger.Level.INFO, "Reaper: deleting {0} stale sessions", toDelete.size());
    }

    for (final SessionKey key : toDelete) {
      final long idleSec =
          registry.getUpdatedAt(key).map(updatedAt -> (now - updatedAt) / 1000).orElse(-1L);
      LOG.log(
          System.Logger.Level.INFO,
          "[sidecar] session reaped matchID={0} idleSeconds={1} caller=reaper",
          new Object[] {key.gameId(), idleSec});
      registry.deleteByKey(key);
    }

    // TODO(Phase 3): gameover check via GET {serverUrl}/api/matches/{matchID}.
    // Deferred: requires the sidecar to call the game server, which is otherwise a
    // leaf service. See docs/ai-sidecar-contract-v2.md.
  }

  private void runOnceSafe() {
    try {
      runOnce();
    } catch (final Exception e) {
      LOG.log(System.Logger.Level.WARNING, "Reaper cycle failed", e);
    }
  }
}
