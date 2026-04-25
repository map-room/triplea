package org.triplea.ai.sidecar.dto;

import java.util.List;

public record KamikazePlan(List<KamikazeTarget> targets) implements DecisionPlan {
  public record KamikazeTarget(String targetUnitId) {}
}
