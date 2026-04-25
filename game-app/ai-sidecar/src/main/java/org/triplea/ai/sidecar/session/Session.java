package org.triplea.ai.sidecar.session;

import games.strategy.engine.data.GameData;
import games.strategy.triplea.ai.pro.ProAi;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;

/**
 * Session state for a single AI sidecar nation/game.
 *
 * <p>The {@code unitIdMap} is a shared, mutable, concurrent map from Map Room unit IDs (stable
 * string identifiers supplied by the Map Room side of the wire) to TripleA {@link java.util.UUID}
 * unit identifiers. The map is populated lazily by {@link
 * org.triplea.ai.sidecar.wire.WireStateApplier} on first encounter with a given Map Room unit ID,
 * and kept stable across subsequent applies so that executors (Task 22+) can resolve wire unit IDs
 * back to live {@link games.strategy.engine.data.Unit} instances in the cloned {@link GameData}.
 *
 * <p>The {@code offensiveExecutor} is a per-session single-threaded executor used to dispatch Phase
 * 3 offensive (purchase / combat-move / noncombat-move / place) ProAi calls serially, ensuring
 * ProAi state is never touched concurrently. The executor's worker thread is a daemon named {@code
 * sidecar-offensive-<sessionId>} and is shut down when the session is released from the {@link
 * SessionRegistry}.
 */
public record Session(
    String sessionId,
    SessionKey key,
    long seed,
    ProAi proAi,
    GameData gameData,
    ConcurrentMap<String, UUID> unitIdMap,
    ExecutorService offensiveExecutor) {
  public Session {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(proAi, "proAi");
    Objects.requireNonNull(gameData, "gameData");
    Objects.requireNonNull(unitIdMap, "unitIdMap");
    Objects.requireNonNull(offensiveExecutor, "offensiveExecutor");
  }
}
