package org.triplea.ai.sidecar.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.triplea.ai.sidecar.wire.WireState;

class PoliticsRequestTest {
  @Test
  void serializesWithKind() throws Exception {
    final PoliticsRequest request =
        new PoliticsRequest(
            new WireState(List.of(), List.of(), 1, "politics", "Germans", List.of()));
    final String json = new ObjectMapper().writeValueAsString((DecisionRequest) request);
    assertThat(json).contains("\"kind\":\"politics\"");
  }

  @Test
  void deserializesFromJson() throws Exception {
    final String json =
        "{\"kind\":\"politics\",\"state\":{\"territories\":[],\"players\":[],\"round\":1,"
            + "\"phase\":\"politics\",\"currentPlayer\":\"Germans\",\"relationships\":[]}}";
    final DecisionRequest request = new ObjectMapper().readValue(json, DecisionRequest.class);
    assertThat(request).isInstanceOf(PoliticsRequest.class);
    assertThat(((PoliticsRequest) request).kind()).isEqualTo("politics");
  }
}
