package org.triplea.ai.sidecar.exec;

import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.dto.DecisionPlan;
import org.triplea.ai.sidecar.dto.DecisionRequest;

/**
 * Kind-specific executor for a {@link DecisionRequest}.
 *
 * <p>Each implementation handles exactly one {@code DecisionRequest} subtype. The executor builds
 * its own per-call state — a fresh {@link games.strategy.engine.data.GameData} clone from {@link
 * CanonicalGameData}, a fresh {@link games.strategy.triplea.ai.pro.ProAi}, a fresh wire-id → UUID
 * map — applies the embedded {@link org.triplea.ai.sidecar.wire.WireState} via {@link
 * org.triplea.ai.sidecar.wire.WireStateApplier}, reseeds the ProAi's RNG sources from {@code
 * request.seed()}, invokes the ProAi entry point, and projects the returned Java object into a
 * wire-shaped {@link DecisionPlan}.
 *
 * <p>The sidecar keeps no state between requests: every executor invocation is hermetic and the
 * (gamestate, seed) → wire-response mapping is therefore a pure function. Replay semantics are
 * proved by the determinism harness (see {@code StatelessReplayDeterminismTest}).
 */
public interface DecisionExecutor<REQ extends DecisionRequest, PLAN extends DecisionPlan> {
  PLAN execute(CanonicalGameData canonical, REQ request);
}
