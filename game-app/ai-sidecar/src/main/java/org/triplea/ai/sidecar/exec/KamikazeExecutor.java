package org.triplea.ai.sidecar.exec;

import java.util.List;
import org.triplea.ai.sidecar.dto.KamikazePlan;
import org.triplea.ai.sidecar.dto.KamikazeRequest;
import org.triplea.ai.sidecar.session.Session;

/**
 * Stub executor for the {@code kamikaze} decision kind.
 *
 * <p>Returns the safe default (no kamikaze tokens spent) until ProAI integration is wired. TODO:
 * invoke {@code AbstractProAi.selectKamikazeSuicideAttacks} once the ProAI entry point is
 * identified and the session/state wiring is in place.
 */
public final class KamikazeExecutor implements DecisionExecutor<KamikazeRequest, KamikazePlan> {

  @Override
  public KamikazePlan execute(final Session session, final KamikazeRequest request) {
    return new KamikazePlan(List.of());
  }
}
