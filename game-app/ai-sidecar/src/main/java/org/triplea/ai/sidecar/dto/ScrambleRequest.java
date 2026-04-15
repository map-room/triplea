package org.triplea.ai.sidecar.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import org.triplea.ai.sidecar.wire.WireState;
import org.triplea.ai.sidecar.wire.WireUnit;

@JsonIgnoreProperties("kind")
public record ScrambleRequest(WireState state, ScrambleBattle battle) implements DecisionRequest {
  public record ScrambleBattle(
      String defendingTerritory, Map<String, ScrambleSource> possibleScramblers) {}

  public record ScrambleSource(int maxCount, List<WireUnit> units) {}
}
