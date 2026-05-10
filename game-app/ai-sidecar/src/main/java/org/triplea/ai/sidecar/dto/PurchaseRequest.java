package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
 */
@JsonIgnoreProperties("kind")
public record PurchaseRequest(WireState state, long seed) implements DecisionRequest {
  public String kind() {
    return "purchase";
  }
}
