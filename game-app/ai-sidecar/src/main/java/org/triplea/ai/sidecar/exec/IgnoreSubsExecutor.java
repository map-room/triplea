package org.triplea.ai.sidecar.exec;

import org.triplea.ai.sidecar.dto.IgnoreSubsPlan;
import org.triplea.ai.sidecar.dto.IgnoreSubsRequest;
import org.triplea.ai.sidecar.session.Session;

/**
 * Stub executor for the {@code ignore-subs} decision kind.
 *
 * <p>Returns the safe default (engage subs, do not ignore) until ProAI integration is wired.
 * TODO: invoke the relevant ProAI helper once identified.
 */
public final class IgnoreSubsExecutor
    implements DecisionExecutor<IgnoreSubsRequest, IgnoreSubsPlan> {

  @Override
  public IgnoreSubsPlan execute(final Session session, final IgnoreSubsRequest request) {
    return new IgnoreSubsPlan(false);
  }
}
