package org.triplea.ai.sidecar.session;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Disk record for a sidecar session. Written atomically to {@code
 * data/sessions/{gameId}/{nation}.json} on creation and update. Re-read on startup to rehydrate the
 * in-memory session registry.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class SessionManifest {
  private final String sessionId;
  private final String gameId;
  private final String nation;
  private final long seed;
  private final long createdAt;
  private final long updatedAt;

  @JsonCreator
  public SessionManifest(
      @JsonProperty("sessionId") final String sessionId,
      @JsonProperty("gameId") final String gameId,
      @JsonProperty("nation") final String nation,
      @JsonProperty("seed") final long seed,
      @JsonProperty("createdAt") final long createdAt,
      @JsonProperty("updatedAt") final long updatedAt) {
    this.sessionId = sessionId;
    this.gameId = gameId;
    this.nation = nation;
    this.seed = seed;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  @JsonProperty("sessionId")
  public String sessionId() {
    return sessionId;
  }

  @JsonProperty("gameId")
  public String gameId() {
    return gameId;
  }

  @JsonProperty("nation")
  public String nation() {
    return nation;
  }

  @JsonProperty("seed")
  public long seed() {
    return seed;
  }

  @JsonProperty("createdAt")
  public long createdAt() {
    return createdAt;
  }

  @JsonProperty("updatedAt")
  public long updatedAt() {
    return updatedAt;
  }

  public SessionManifest withUpdatedAt(final long newUpdatedAt) {
    return new SessionManifest(sessionId, gameId, nation, seed, createdAt, newUpdatedAt);
  }
}
