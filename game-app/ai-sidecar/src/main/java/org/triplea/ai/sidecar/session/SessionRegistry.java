package org.triplea.ai.sidecar.session;

import games.strategy.engine.data.GameData;
import games.strategy.triplea.ai.pro.ProAi;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.triplea.ai.sidecar.CanonicalGameData;

public final class SessionRegistry {
  private final CanonicalGameData canonical;
  private final ConcurrentHashMap<SessionKey, Session> byKey = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Session> byId = new ConcurrentHashMap<>();

  public SessionRegistry(final CanonicalGameData canonical) {
    this.canonical = canonical;
  }

  public synchronized Session createOrGet(final SessionKey key, final long seed) {
    final Session existing = byKey.get(key);
    if (existing != null && existing.seed() == seed) {
      return existing;
    }
    if (existing != null) {
      byId.remove(existing.sessionId());
      existing.offensiveExecutor().shutdownNow();
    }
    final GameData data = canonical.cloneForSession();
    final ProAi proAi = new ProAi("sidecar-" + key.gameId() + "-" + key.nation(), key.nation());
    final String id = "s-" + UUID.randomUUID();
    final ExecutorService offensiveExecutor =
        Executors.newSingleThreadExecutor(
            r -> {
              final Thread t = new Thread(r, "sidecar-offensive-" + id);
              t.setDaemon(true);
              return t;
            });
    final Session created =
        new Session(id, key, seed, proAi, data, new ConcurrentHashMap<>(), offensiveExecutor);
    byKey.put(key, created);
    byId.put(id, created);
    return created;
  }

  public Optional<Session> get(final String sessionId) {
    return Optional.ofNullable(byId.get(sessionId));
  }

  public synchronized boolean delete(final String sessionId) {
    final Session removed = byId.remove(sessionId);
    if (removed == null) {
      return false;
    }
    byKey.remove(removed.key(), removed);
    removed.offensiveExecutor().shutdownNow();
    return true;
  }
}
