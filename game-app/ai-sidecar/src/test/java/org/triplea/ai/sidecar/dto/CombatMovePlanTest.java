package org.triplea.ai.sidecar.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class CombatMovePlanTest {
  @Test
  void serializesDeclarations() throws Exception {
    final CombatMovePlan plan =
        new CombatMovePlan(
            List.of(new WarDeclaration("Russians")),
            List.of(),
            List.of());
    final String json = new ObjectMapper().writeValueAsString(plan);
    assertThat(json).contains("\"declarations\":[{\"target\":\"Russians\"}]");
  }

  @Test
  void deserializesWithoutDeclarationsField() throws Exception {
    // Deserialize via the DecisionPlan interface so the 'kind' discriminator is present,
    // which mirrors how the sidecar actually receives plans. Omitting 'declarations' must
    // default to an empty list (backwards-compat for older TS clients).
    final String json = "{\"kind\":\"combat-move\",\"moves\":[],\"sbrMoves\":[]}";
    final DecisionPlan plan = new ObjectMapper().readValue(json, DecisionPlan.class);
    assertThat(plan).isInstanceOf(CombatMovePlan.class);
    assertThat(((CombatMovePlan) plan).declarations()).isEmpty();
  }
}
