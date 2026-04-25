package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.triplea.ai.sidecar.wire.WireState;
import org.triplea.ai.sidecar.wire.WireUnit;

@JsonIgnoreProperties("kind")
public record KamikazeRequest(WireState state, KamikazeBattle battle) implements DecisionRequest {
  public record KamikazeBattle(
      String battleId,
      String territory,
      String attackerNation,
      String defenderNation,
      int availableTokens,
      List<WireUnit> eligibleTargets) {}
}
