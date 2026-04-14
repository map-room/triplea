package org.triplea.ai.sidecar.wire;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record WireState(
    List<WireTerritory> territories,
    List<WirePlayer> players,
    int round,
    String phase,
    String currentPlayer,
    JsonNode battleContext) {}
