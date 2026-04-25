package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.triplea.ai.sidecar.wire.WireState;
import org.triplea.ai.sidecar.wire.WireUnit;

@JsonIgnoreProperties("kind")
public record EngageStayHiddenRequest(WireState state, EngageStayHiddenBattle battle)
    implements DecisionRequest {
  public record EngageStayHiddenBattle(
      String battleId,
      String territory,
      String attackerNation,
      String defenderNation,
      List<WireUnit> stayHiddenSubs,
      List<WireUnit> attackingUnits) {}
}
