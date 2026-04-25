package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.triplea.ai.sidecar.wire.WireState;
import org.triplea.ai.sidecar.wire.WireUnit;

@JsonIgnoreProperties("kind")
public record InterceptRequest(WireState state, InterceptBattle battle) implements DecisionRequest {
  public record InterceptBattle(
      String battleId,
      String territory,
      String attackerNation,
      String defenderNation,
      List<PendingInterceptor> pendingInterceptors) {}

  public record PendingInterceptor(String fromTerritory, WireUnit unit) {}
}
