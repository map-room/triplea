package org.triplea.ai.sidecar.wire;

import java.util.List;

public record WireState(
    List<WireTerritory> territories,
    List<WirePlayer> players,
    int round,
    String phase,
    String currentPlayer) {}
