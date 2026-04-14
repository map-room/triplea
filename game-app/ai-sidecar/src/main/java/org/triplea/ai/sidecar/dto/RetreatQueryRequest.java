package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.triplea.ai.sidecar.wire.WireState;

@JsonIgnoreProperties("kind")
public record RetreatQueryRequest(WireState state, RetreatQueryBattle battle)
    implements DecisionRequest {
  public record RetreatQueryBattle(
      String battleId,
      String battleTerritory,
      boolean canSubmerge,
      List<String> possibleRetreatTerritories) {}
}
