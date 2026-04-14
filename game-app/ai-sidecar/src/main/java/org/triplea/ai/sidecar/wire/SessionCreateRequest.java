package org.triplea.ai.sidecar.wire;

public record SessionCreateRequest(String gameId, String nation, long seed) {}
