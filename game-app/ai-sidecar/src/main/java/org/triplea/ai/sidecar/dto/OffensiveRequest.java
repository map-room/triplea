package org.triplea.ai.sidecar.dto;

import org.triplea.ai.sidecar.wire.WireState;

public record OffensiveRequest(WireState state, String kind) implements DecisionRequest {}
