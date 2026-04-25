package org.triplea.ai.sidecar.exec;

import java.util.List;
import org.triplea.ai.sidecar.dto.SubmergePlan;
import org.triplea.ai.sidecar.dto.SubmergeRequest;
import org.triplea.ai.sidecar.session.Session;

/**
 * Stub executor for the {@code submerge} decision kind.
 *
 * <p>Returns the safe default (no subs submerge — press the attack) until ProAI integration is
 * wired. TODO: invoke the relevant ProAI battle-step submerge logic once identified.
 */
public final class SubmergeExecutor implements DecisionExecutor<SubmergeRequest, SubmergePlan> {

  @Override
  public SubmergePlan execute(final Session session, final SubmergeRequest request) {
    return new SubmergePlan(List.of());
  }
}
