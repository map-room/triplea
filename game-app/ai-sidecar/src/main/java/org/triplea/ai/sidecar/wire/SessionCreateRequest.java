package org.triplea.ai.sidecar.wire;

/**
 * v2 session-create request.
 *
 * <p>{@code sessionId} is deterministic and supplied by the caller as {@code
 * matchID:nation:r{round}}. It must equal {@code gameId + ":" + nation + ":r" + round}; the handler
 * returns 400 if it does not.
 */
public record SessionCreateRequest(
    String sessionId, String gameId, String nation, int round, long seed) {}
