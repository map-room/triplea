package org.triplea.ai.sidecar.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class PoliticsPlanTest {
  @Test
  void serializesDeclarations() throws Exception {
    final PoliticsPlan plan = new PoliticsPlan(List.of(new WarDeclaration("Russians")));
    final String json = new ObjectMapper().writeValueAsString((DecisionPlan) plan);
    assertThat(json).contains("\"kind\":\"politics\"");
    assertThat(json).contains("\"declarations\":[{\"target\":\"Russians\"}]");
  }

  @Test
  void deserializesFromJson() throws Exception {
    final String json =
        "{\"kind\":\"politics\",\"declarations\":[{\"target\":\"Russians\"}]}";
    final DecisionPlan plan = new ObjectMapper().readValue(json, DecisionPlan.class);
    assertThat(plan).isInstanceOf(PoliticsPlan.class);
    assertThat(((PoliticsPlan) plan).declarations())
        .containsExactly(new WarDeclaration("Russians"));
  }

  @Test
  void nullDeclarationsDefaultsToEmptyList() throws Exception {
    final String json = "{\"kind\":\"politics\"}";
    final DecisionPlan plan = new ObjectMapper().readValue(json, DecisionPlan.class);
    assertThat(plan).isInstanceOf(PoliticsPlan.class);
    assertThat(((PoliticsPlan) plan).declarations()).isEmpty();
  }
}
