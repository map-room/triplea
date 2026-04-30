package org.triplea.ai.sidecar.exec;

import java.util.ArrayList;
import java.util.List;
import org.triplea.ai.sidecar.AiTraceLogger;
import org.triplea.ai.sidecar.dto.InterceptPlan;
import org.triplea.ai.sidecar.dto.InterceptRequest;
import org.triplea.ai.sidecar.session.Session;

/**
 * Stub executor for the {@code intercept} decision kind.
 *
 * <p>Returns the safe default (no interceptors sent) until ProAI integration is wired. TODO: invoke
 * the ProAI SBR intercept logic once identified (likely related to {@code
 * AbstractProAi.shouldBomberBomb} or a dedicated scramble/intercept method).
 *
 * <p>The {@code [AI-TRACE] kind=interceptor-decision} line still emits in the stub state (#2105) so
 * triagers can distinguish "intercept query reached the sidecar but the decision is not yet
 * implemented" from silence. Once the ProAI integration replaces the stub return below, swap {@code
 * reason="stub-not-implemented"} for the real heuristic and pass the real picked set — the helper
 * signature accepts both today.
 */
public final class InterceptExecutor implements DecisionExecutor<InterceptRequest, InterceptPlan> {

  @Override
  public InterceptPlan execute(final Session session, final InterceptRequest request) {
    final InterceptRequest.InterceptBattle b = request.battle();
    final List<String> candidateIds = new ArrayList<>(b.pendingInterceptors().size());
    final List<String> candidateTypes = new ArrayList<>(b.pendingInterceptors().size());
    for (final InterceptRequest.PendingInterceptor pi : b.pendingInterceptors()) {
      candidateIds.add(pi.unit().unitId());
      candidateTypes.add(pi.unit().unitType());
    }

    AiTraceLogger.logSbrInterceptorDecision(
        b.defenderNation(),
        b.battleId(),
        b.territory(),
        b.attackerNation(),
        candidateIds,
        candidateTypes,
        /* pickedIds */ List.of(),
        /* reason */ "stub-not-implemented");

    return new InterceptPlan(List.of());
  }
}
