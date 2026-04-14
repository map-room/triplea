package org.triplea.ai.sidecar.wire;

public record DecisionRequest(String kind, WireState state) {}
