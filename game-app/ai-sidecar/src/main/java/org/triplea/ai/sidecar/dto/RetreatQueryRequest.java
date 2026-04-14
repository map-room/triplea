package org.triplea.ai.sidecar.dto;

import java.util.List;
import org.triplea.ai.sidecar.wire.WireState;

public record RetreatQueryRequest(WireState state, RetreatQueryBattle battle)
    implements DecisionRequest {
  public record RetreatQueryBattle(
      String battleId,
      String battleTerritory,
      boolean canSubmerge,
      List<String> possibleRetreatTerritories) {}
}
