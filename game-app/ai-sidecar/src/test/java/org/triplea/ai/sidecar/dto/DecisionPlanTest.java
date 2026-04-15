package org.triplea.ai.sidecar.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DecisionPlanTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void serializesSelectCasualtiesPlan() throws Exception {
    DecisionPlan plan = new SelectCasualtiesPlan(List.of("u1", "u2"), List.of());
    String json = mapper.writeValueAsString(plan);
    assertThat(json).contains("\"kind\":\"select-casualties\"")
                    .contains("\"killed\":[\"u1\",\"u2\"]");
  }

  @Test
  void serializesRetreatPlanWithNullRetreatTo() throws Exception {
    DecisionPlan plan = new RetreatPlan(null);
    String json = mapper.writeValueAsString(plan);
    assertThat(json).contains("\"kind\":\"retreat-or-press\"")
                    .contains("\"retreatTo\":null");
  }

  @Test
  void serializesScramblePlan() throws Exception {
    DecisionPlan plan = new ScramblePlan(Map.of("United Kingdom", List.of("u7")));
    String json = mapper.writeValueAsString(plan);
    assertThat(json).contains("\"kind\":\"scramble\"");
  }

  @Test
  void roundTripsSelectCasualtiesPlan() throws Exception {
    DecisionPlan original = new SelectCasualtiesPlan(List.of("u1", "u2"), List.of("u3"));
    String json = mapper.writeValueAsString(original);
    DecisionPlan roundTripped = mapper.readValue(json, DecisionPlan.class);
    assertThat(roundTripped).isInstanceOf(SelectCasualtiesPlan.class);
    SelectCasualtiesPlan result = (SelectCasualtiesPlan) roundTripped;
    assertThat(result.killed()).containsExactly("u1", "u2");
    assertThat(result.damaged()).containsExactly("u3");
  }

  @Test
  void roundTripsRetreatPlan() throws Exception {
    DecisionPlan original = new RetreatPlan("Libya");
    String json = mapper.writeValueAsString(original);
    DecisionPlan roundTripped = mapper.readValue(json, DecisionPlan.class);
    assertThat(roundTripped).isInstanceOf(RetreatPlan.class);
    assertThat(((RetreatPlan) roundTripped).retreatTo()).isEqualTo("Libya");
  }

  @Test
  void roundTripsRetreatPlanWithNullRetreatTo() throws Exception {
    DecisionPlan original = new RetreatPlan(null);
    String json = mapper.writeValueAsString(original);
    DecisionPlan roundTripped = mapper.readValue(json, DecisionPlan.class);
    assertThat(roundTripped).isInstanceOf(RetreatPlan.class);
    assertThat(((RetreatPlan) roundTripped).retreatTo()).isNull();
  }

  @Test
  void roundTripsScramblePlan() throws Exception {
    DecisionPlan original = new ScramblePlan(Map.of("United Kingdom", List.of("u7", "u8")));
    String json = mapper.writeValueAsString(original);
    DecisionPlan roundTripped = mapper.readValue(json, DecisionPlan.class);
    assertThat(roundTripped).isInstanceOf(ScramblePlan.class);
    ScramblePlan result = (ScramblePlan) roundTripped;
    assertThat(result.scramblers()).containsKey("United Kingdom");
    assertThat(result.scramblers().get("United Kingdom")).containsExactlyInAnyOrder("u7", "u8");
  }
}
