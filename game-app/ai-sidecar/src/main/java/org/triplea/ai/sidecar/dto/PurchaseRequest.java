package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.triplea.ai.sidecar.wire.WireState;

/**
 * Request for the {@code purchase} decision kind. Phase 3 is the first offensive kind wired
 * end-to-end; sidecar hydrates a cached {@code GameData} from {@code state} and invokes {@code
 * AbstractProAi#purchase}.
 *
 * <p>{@code seed} is the per-call wire seed derived TS-side via {@code seedForCall(rootSeed, round,
 * phase, nation)} in map-room#2388. {@link
 * org.triplea.ai.sidecar.exec.PurchaseExecutor#execute(org.triplea.ai.sidecar.session.Session,
 * PurchaseRequest)} reseeds {@code proData.getRng()} and the battle calculator from this value at
 * the start of every call so ProAi behaviour is deterministic given {@code (gamestate, seed)}.
 *
 * <p>{@code matchId} is the boardgame.io match ID for cross-process log correlation (#2555). Older
 * callers that omit it receive {@code null}; the log formatter substitutes the {@code -} sentinel.
 */
@JsonIgnoreProperties("kind")
public record PurchaseRequest(WireState state, long seed, String matchId)
    implements DecisionRequest {
  public String kind() {
    return "purchase";
  }

  /** Backwards-compat: call sites that don't supply a matchId get null → sentinel in logs. */
  public PurchaseRequest(final WireState state, final long seed) {
    this(state, seed, null);
  }

  /** Jackson deserialisation — {@code matchId} absent in old payloads defaults to null. */
  @JsonCreator
  public static PurchaseRequest fromJson(
      @JsonProperty("state") final WireState state,
      @JsonProperty("seed") final long seed,
      @JsonProperty("matchId") final String matchId) {
    return new PurchaseRequest(state, seed, matchId);
  }
}
