package org.triplea.ai.sidecar.wire;

import java.util.List;

public record WirePlayer(String playerId, int pus, List<String> tech, boolean capitalCaptured) {}
