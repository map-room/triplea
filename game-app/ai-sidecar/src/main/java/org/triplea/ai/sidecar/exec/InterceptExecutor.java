package org.triplea.ai.sidecar.exec;

import java.util.List;
import org.triplea.ai.sidecar.dto.InterceptPlan;
import org.triplea.ai.sidecar.dto.InterceptRequest;
import org.triplea.ai.sidecar.session.Session;

/**
 * Stub executor for the {@code intercept} decision kind.
 *
 * <p>Returns the safe default (no interceptors sent) until ProAI integration is wired. TODO: invoke
 * the ProAI SBR intercept logic once identified (likely related to {@code
 * AbstractProAi.shouldBomberBomb} or a dedicated scramble/intercept method).
 */
public final class InterceptExecutor implements DecisionExecutor<InterceptRequest, InterceptPlan> {

  @Override
  public InterceptPlan execute(final Session session, final InterceptRequest request) {
    return new InterceptPlan(List.of());
  }
}
