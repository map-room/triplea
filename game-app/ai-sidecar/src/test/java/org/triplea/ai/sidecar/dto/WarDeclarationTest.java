package org.triplea.ai.sidecar.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class WarDeclarationTest {
  @Test
  void roundTripsThroughJson() throws Exception {
    final WarDeclaration wd = new WarDeclaration("Russians");
    final ObjectMapper m = new ObjectMapper();
    final String json = m.writeValueAsString(wd);
    assertThat(m.readValue(json, WarDeclaration.class)).isEqualTo(wd);
    assertThat(json).contains("\"target\":\"Russians\"");
  }
}
