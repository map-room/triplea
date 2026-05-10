package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
 */
@JsonIgnoreProperties("kind")
public record NoncombatMoveRequest(WireState state, long seed) implements DecisionRequest {
  public String kind() {
    return "noncombat-move";
  }
}
