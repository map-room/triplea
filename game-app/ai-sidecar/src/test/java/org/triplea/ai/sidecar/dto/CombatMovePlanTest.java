package org.triplea.ai.sidecar.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class CombatMovePlanTest {
  @Test
  void deserializesFromMinimalJson() throws Exception {
    // Deserialize via the DecisionPlan interface so the 'kind' discriminator is exercised,
    // which mirrors how the sidecar actually receives plans. Missing moves/sbrMoves must
    // default to empty lists (backwards-compat for older TS clients).
    final String json = "{\"kind\":\"combat-move\",\"moves\":[],\"sbrMoves\":[]}";
    final DecisionPlan plan = new ObjectMapper().readValue(json, DecisionPlan.class);
    assertThat(plan).isInstanceOf(CombatMovePlan.class);
    final CombatMovePlan combatMovePlan = (CombatMovePlan) plan;
    assertThat(combatMovePlan.moves()).isEmpty();
    assertThat(combatMovePlan.sbrMoves()).isEmpty();
  }
}
