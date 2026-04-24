package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.triplea.ai.sidecar.wire.WireState;
import org.triplea.ai.sidecar.wire.WireUnit;

@JsonIgnoreProperties("kind")
public record IgnoreSubsRequest(WireState state, IgnoreSubsBattle battle)
    implements DecisionRequest {
  public record IgnoreSubsBattle(
      String battleId,
      String territory,
      String attackerNation,
      String defenderNation,
      List<WireUnit> defendingSubs,
      List<WireUnit> attackingUnits) {}
}
