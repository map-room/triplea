package org.triplea.ai.sidecar.exec;

import org.triplea.ai.sidecar.dto.DecisionPlan;
import org.triplea.ai.sidecar.dto.DecisionRequest;
import org.triplea.ai.sidecar.session.Session;

/**
 * Kind-specific executor for a {@link DecisionRequest}.
 *
 * <p>Each implementation handles exactly one {@code DecisionRequest} subtype, applies the embedded
 * {@link org.triplea.ai.sidecar.wire.WireState} onto the session's {@link
 * games.strategy.engine.data.GameData} via {@link org.triplea.ai.sidecar.wire.WireStateApplier},
 * synthesises whatever transient combat-phase state the corresponding ProAi entry point reads (e.g.
 * a pending {@code IBattle} registered in {@code BattleTracker}), invokes the ProAi method, and
 * projects the returned Java object back into a wire-shaped {@link DecisionPlan}.
 *
 * <p>Executors must leave no residual state in the BattleTracker or other shared game-data
 * structures: anything added for the call must be removed before returning (success or exception).
 */
public interface DecisionExecutor<REQ extends DecisionRequest, PLAN extends DecisionPlan> {
  PLAN execute(Session session, REQ request);
}
