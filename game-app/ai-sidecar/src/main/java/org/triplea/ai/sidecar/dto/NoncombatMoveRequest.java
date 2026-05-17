package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.triplea.ai.sidecar.wire.WireState;

/**
 * Request for the {@code noncombat-move} decision kind. The sidecar restores {@code
 * storedFactoryMoveMap} and {@code storedPurchaseTerritories} from the session snapshot and calls
 * {@code AbstractProAi#invokeNonCombatMoveForSidecar} to produce the move list.
 *
 * <p>{@code seed} is the per-call wire seed derived TS-side via {@code seedForCall(rootSeed, round,
 * phase, nation)} in map-room#2388. {@link
 * org.triplea.ai.sidecar.exec.NoncombatMoveExecutor#execute(org.triplea.ai.sidecar.session.Session,
 * NoncombatMoveRequest)} reseeds {@code proData.getRng()} and the battle calculator from this value
 * at the start of every call so ProAi behaviour is deterministic given {@code (gamestate, seed)}.
 *
 * <p>{@code matchId} is the boardgame.io match ID for cross-process log correlation (#2555). Older
 * callers that omit it receive {@code null}; the log formatter substitutes the {@code -} sentinel.
 */
@JsonIgnoreProperties("kind")
public record NoncombatMoveRequest(WireState state, long seed, String matchId)
    implements DecisionRequest {
  public String kind() {
    return "noncombat-move";
  }

  /** Backwards-compat: call sites that don't supply a matchId get null → sentinel in logs. */
  public NoncombatMoveRequest(final WireState state, final long seed) {
    this(state, seed, null);
  }

  /** Jackson deserialisation — {@code matchId} absent in old payloads defaults to null. */
  @JsonCreator
  public static NoncombatMoveRequest fromJson(
      @JsonProperty("state") final WireState state,
      @JsonProperty("seed") final long seed,
      @JsonProperty("matchId") final String matchId) {
    return new NoncombatMoveRequest(state, seed, matchId);
  }
}
