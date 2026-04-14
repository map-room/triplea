package org.triplea.ai.sidecar.session;

import games.strategy.engine.data.GameData;
import games.strategy.triplea.ai.pro.ProAi;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;

/**
 * Session state for a single AI sidecar nation/game.
 *
 * <p>The {@code unitIdMap} is a shared, mutable, concurrent map from Map Room unit IDs (stable
 * string identifiers supplied by the Map Room side of the wire) to TripleA {@link
 * java.util.UUID} unit identifiers. The map is populated lazily by {@link
 * org.triplea.ai.sidecar.wire.WireStateApplier} on first encounter with a given Map Room unit
 * ID, and kept stable across subsequent applies so that executors (Task 22+) can resolve wire
 * unit IDs back to live {@link games.strategy.engine.data.Unit} instances in the cloned {@link
 * GameData}.
 */
public record Session(
    String sessionId,
    SessionKey key,
    long seed,
    ProAi proAi,
    GameData gameData,
    ConcurrentMap<String, UUID> unitIdMap) {
  public Session {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(proAi, "proAi");
    Objects.requireNonNull(gameData, "gameData");
    Objects.requireNonNull(unitIdMap, "unitIdMap");
  }
}
