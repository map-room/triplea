package org.triplea.ai.sidecar.session;

import games.strategy.engine.data.GameData;
import games.strategy.triplea.ai.pro.ProAi;

public record Session(
    String sessionId, SessionKey key, long seed, ProAi proAi, GameData gameData) {}
