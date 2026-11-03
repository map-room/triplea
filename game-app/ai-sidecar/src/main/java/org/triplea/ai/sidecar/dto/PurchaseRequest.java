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
 *
 * <p>{@code profile} selects the ProAi variant: omitted or {@code null} / {@code "hard"} is stock
 * Hard, {@code "hard-beta"} is Hard Beta. Anything else is rejected, see {@link AiProfile}.
 */
@JsonIgnoreProperties("kind")
public record PurchaseRequest(WireState state, long seed, String matchId, String profile)
    implements DecisionRequest {
  public String kind() {
    return "purchase";
  }

  /** Backwards-compat: call sites that don't supply a matchId get null → sentinel in logs. */
  public PurchaseRequest(final WireState state, final long seed) {
    this(state, seed, null, null);
  }

  /** Call sites that supply a matchId but no profile get the stock (non-beta) ProAi. */
  public PurchaseRequest(final WireState state, final long seed, final String matchId) {
    this(state, seed, matchId, null);
  }

  /**
   * Jackson deserialisation — {@code matchId} and {@code profile} absent in old payloads default to
   * null. {@code profile} is validated by {@link AiProfile#isBeta(String)} before any ProAi is
   * built, not here, so an unknown value surfaces as a 400 from the handler.
   */
  @JsonCreator
  public static PurchaseRequest fromJson(
      @JsonProperty("state") final WireState state,
      @JsonProperty("seed") final long seed,
      @JsonProperty("matchId") final String matchId,
      @JsonProperty("profile") final String profile) {
    return new PurchaseRequest(state, seed, matchId, profile);
  }
}
