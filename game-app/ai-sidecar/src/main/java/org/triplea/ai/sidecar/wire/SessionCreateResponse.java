package org.triplea.ai.sidecar.wire;

/**
 * v2 session-create response.
 *
 * <p>{@code created} is {@code true} if a new session was written to disk,
 * {@code false} if an existing session was found (idempotent re-open).
 */
public record SessionCreateResponse(String sessionId, boolean created) {}
