package org.triplea.ai.sidecar.exec;

import org.triplea.ai.sidecar.dto.EngageStayHiddenPlan;
import org.triplea.ai.sidecar.dto.EngageStayHiddenRequest;
import org.triplea.ai.sidecar.session.Session;

/**
 * Stub executor for the {@code engage-stay-hidden} decision kind.
 *
 * <p>Returns the safe default (engage — do not stay hidden) until ProAI integration is wired.
 * TODO: invoke the relevant ProAI helper (likely in {@code ProRetreatAi} or
 * {@code AbstractProAi}) once identified.
 */
public final class EngageStayHiddenExecutor
    implements DecisionExecutor<EngageStayHiddenRequest, EngageStayHiddenPlan> {

  @Override
  public EngageStayHiddenPlan execute(
      final Session session, final EngageStayHiddenRequest request) {
    return new EngageStayHiddenPlan(true);
  }
}
