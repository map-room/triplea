package org.triplea.ai.sidecar.dto;

import java.util.List;
import org.triplea.ai.sidecar.wire.WireState;
import org.triplea.ai.sidecar.wire.WireUnit;

public record SelectCasualtiesRequest(WireState state, SelectCasualtiesBattle battle)
    implements DecisionRequest {
  public record SelectCasualtiesBattle(
      String battleId,
      String territory,
      String attackerNation,
      String defenderNation,
      int hitCount,
      List<WireUnit> selectFrom,
      List<WireUnit> friendlyUnits,
      List<WireUnit> enemyUnits,
      boolean isAmphibious,
      List<WireUnit> amphibiousLandAttackers,
      List<String> defaultCasualties,
      boolean allowMultipleHitsPerUnit) {}
}
