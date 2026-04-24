package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.triplea.ai.sidecar.wire.WireState;
import org.triplea.ai.sidecar.wire.WireUnit;

@JsonIgnoreProperties("kind")
public record SubmergeRequest(WireState state, SubmergeBattle battle) implements DecisionRequest {
  public record SubmergeBattle(
      String battleId,
      String territory,
      String attackerNation,
      String defenderNation,
      List<WireUnit> eligibleSubs,
      List<WireUnit> enemyUnits,
      boolean isAttackerSubmerge) {}
}
