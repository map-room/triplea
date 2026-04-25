package org.triplea.ai.sidecar.wire;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class WireStateTest {

  @Test
  void deserializesRelationshipsFromJson() throws Exception {
    final String json =
        """
        {
          "territories": [],
          "players": [],
          "round": 3,
          "phase": "combat-move",
          "currentPlayer": "Germans",
          "relationships": [
            { "a": "Germans", "b": "Russians", "kind": "war" }
          ]
        }
        """;
    final WireState state = new ObjectMapper().readValue(json, WireState.class);
    assertThat(state.relationships())
        .containsExactly(new WireRelationship("Germans", "Russians", "war"));
  }

  @Test
  void deserializesWithoutRelationshipsField() throws Exception {
    // Backwards compat: missing field -> empty list, not null.
    final String json =
        """
        {
          "territories": [],
          "players": [],
          "round": 1,
          "phase": "purchase",
          "currentPlayer": "Germans"
        }
        """;
    final WireState state = new ObjectMapper().readValue(json, WireState.class);
    assertThat(state.relationships()).isEmpty();
  }
}
