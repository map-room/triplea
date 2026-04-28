package org.triplea.ai.sidecar.session;

import games.strategy.engine.data.GameData;
import games.strategy.triplea.ai.pro.ProAi;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.triplea.ai.sidecar.CanonicalGameData;

/**
 * In-memory registry of active sidecar sessions, backed by disk persistence.
 *
 * <p>Sessions are keyed by their deterministic {@code sessionId} ({@code matchID:nation}). On
 * startup, call {@link #rehydrate()} to restore sessions from disk. On session create, the manifest
 * is written atomically via {@link SessionStore}.
 *
 * <p>Thread safety: {@code createOrGet}, {@code delete}, {@code deleteByKey}, and {@code rehydrate}
 * are {@code synchronized} to prevent concurrent create/delete races. Read operations ({@code get})
 * use the underlying {@code ConcurrentHashMap} directly.
 */
public final class SessionRegistry {
  private final CanonicalGameData canonical;
  private final ProSessionSnapshotStore snapshotStore;
  private final SessionStore sessionStore;
  private final ConcurrentHashMap<SessionKey, Session> byKey = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Session> byId = new ConcurrentHashMap<>();
  // Tracks updatedAt per key (used by reaper). Updated by setUpdatedAt.
  private final ConcurrentHashMap<SessionKey, Long> updatedAtMap = new ConcurrentHashMap<>();

  /** Production constructor: data dir defaults to ./data/sessions; snapshot dir to tmpdir. */
  public SessionRegistry(final CanonicalGameData canonical) {
    this(
        canonical,
        Path.of("data", "sessions"),
        new ProSessionSnapshotStore(
            Path.of(System.getProperty("java.io.tmpdir"), "sidecar-snapshots")));
  }

  /** Constructor accepting a custom data dir (used in tests and via SidecarConfig). */
  public SessionRegistry(final CanonicalGameData canonical, final Path dataDir) {
    this(canonical, dataDir, new ProSessionSnapshotStore(dataDir.resolve("snapshots")));
  }

  private SessionRegistry(
      final CanonicalGameData canonical,
      final Path dataDir,
      final ProSessionSnapshotStore snapshotStore) {
    this.canonical = canonical;
    this.snapshotStore = snapshotStore;
    this.sessionStore = new SessionStore(dataDir);
  }

  /** Test-only constructor that accepts an explicit snapshot store. */
  public SessionRegistry(
      final CanonicalGameData canonical, final ProSessionSnapshotStore snapshotStore) {
    this.canonical = canonical;
    this.snapshotStore = snapshotStore;
    this.sessionStore =
        new SessionStore(
            Path.of(
                System.getProperty("java.io.tmpdir"), "sidecar-manifests-" + System.nanoTime()));
  }

  /**
   * Creates a new session for {@code (key, sessionId, seed)} or returns the existing one if {@code
   * sessionId} already exists.
   *
   * @param key the (gameId, nation) pair
   * @param sessionId deterministic ID supplied by the caller ({@code matchID:nation})
   * @param seed randomness seed for ProAi
   * @return a {@link CreateResult} with the session and whether it was newly created
   */
  public synchronized CreateResult createOrGet(
      final SessionKey key, final String sessionId, final long seed) {
    final Session existing = byId.get(sessionId);
    if (existing != null) {
      return new CreateResult(existing, false);
    }
    final Session created = buildSession(key, sessionId, seed);
    register(created);
    final long now = System.currentTimeMillis();
    sessionStore.save(new SessionManifest(sessionId, key.gameId(), key.nation(), seed, now, now));
    updatedAtMap.put(key, now);
    return new CreateResult(created, true);
  }

  /**
   * Rehydrates sessions from disk. Call once at startup before accepting requests. Sessions already
   * in memory (e.g. from a test) are not overwritten.
   */
  public synchronized void rehydrate() {
    final List<SessionManifest> manifests = sessionStore.loadAll();
    for (final SessionManifest m : manifests) {
      final SessionKey key = new SessionKey(m.gameId(), m.nation());
      if (byId.containsKey(m.sessionId())) {
        continue; // already present — skip
      }
      final Session session = buildSession(key, m.sessionId(), m.seed());
      register(session);
      updatedAtMap.put(key, m.updatedAt());
    }
  }

  public ProSessionSnapshotStore snapshotStore() {
    return snapshotStore;
  }

  public Optional<Session> get(final String sessionId) {
    return Optional.ofNullable(byId.get(sessionId));
  }

  public Optional<Long> getUpdatedAt(final SessionKey key) {
    return Optional.ofNullable(updatedAtMap.get(key));
  }

  public synchronized boolean delete(final String sessionId) {
    final Session removed = byId.remove(sessionId);
    if (removed == null) {
      return false;
    }
    byKey.remove(removed.key(), removed);
    updatedAtMap.remove(removed.key());
    removed.offensiveExecutor().shutdownNow();
    snapshotStore.delete(removed.key());
    sessionStore.delete(removed.key());
    return true;
  }

  public synchronized void deleteByKey(final SessionKey key) {
    final Session session = byKey.get(key);
    if (session != null) {
      delete(session.sessionId());
    }
  }

  /** Updates the on-disk and in-memory updatedAt for a session. Called by lifecycle handler. */
  public void touchUpdatedAt(final SessionKey key) {
    final long now = System.currentTimeMillis();
    updatedAtMap.put(key, now);
    sessionStore.updateTimestamp(key, now);
  }

  /**
   * Returns all session keys whose {@code updatedAt} is strictly before {@code beforeMs}. Used by
   * {@link SessionReaper}.
   */
  public List<SessionKey> findStale(final long beforeMs) {
    final List<SessionKey> stale = new ArrayList<>();
    for (final java.util.Map.Entry<SessionKey, Long> e : updatedAtMap.entrySet()) {
      if (e.getValue() < beforeMs) {
        stale.add(e.getKey());
      }
    }
    return stale;
  }

  /** Returns the number of live sessions. Used in tests. */
  public int sessionCount() {
    return byId.size();
  }

  /**
   * Test-only: override the updatedAt for a session key to simulate staleness. Also updates the
   * on-disk manifest so that reaper can read it.
   */
  public void setUpdatedAtForTesting(final SessionKey key, final long updatedAt) {
    updatedAtMap.put(key, updatedAt);
    sessionStore.updateTimestamp(key, updatedAt);
  }

  // --- private helpers ---

  private Session buildSession(final SessionKey key, final String sessionId, final long seed) {
    final GameData data = canonical.cloneForSession();
    final ProAi proAi = new ProAi("sidecar-" + key.gameId() + "-" + key.nation(), key.nation());
    proAi.getProData().setSeed(seed);
    final ExecutorService offensiveExecutor =
        Executors.newSingleThreadExecutor(
            r -> {
              final Thread t = new Thread(r, "sidecar-offensive-" + sessionId);
              t.setDaemon(true);
              return t;
            });
    return new Session(
        sessionId, key, seed, proAi, data, new ConcurrentHashMap<>(), offensiveExecutor);
  }

  private void register(final Session session) {
    byKey.put(session.key(), session);
    byId.put(session.sessionId(), session);
  }

  /** Result of {@link #createOrGet}. */
  public record CreateResult(Session session, boolean created) {}
}
