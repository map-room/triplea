package org.triplea.ai.sidecar.wire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import java.util.List;
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
          ],
          "gameDataKey": "ww2global40_2nd_edition"
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
          "currentPlayer": "Germans",
          "gameDataKey": "ww2global40_2nd_edition"
        }
        """;
    final WireState state = new ObjectMapper().readValue(json, WireState.class);
    assertThat(state.relationships()).isEmpty();
  }

  /**
   * Unlike {@code relationships} (which defaults to an empty list when absent — see {@link
   * #deserializesWithoutRelationshipsField()}), an absent {@code gameDataKey} must fail loudly: it
   * selects which {@link org.triplea.ai.sidecar.CanonicalGameData} the request is decided against,
   * and silently defaulting it would silently select the wrong map.
   */
  @Test
  void deserializationFailsWhenGameDataKeyAbsent() {
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
    assertThatThrownBy(() -> new ObjectMapper().readValue(json, WireState.class))
        .isInstanceOf(ValueInstantiationException.class)
        .hasRootCauseInstanceOf(IllegalArgumentException.class)
        .hasRootCauseMessage("WireState.gameDataKey is required");
  }

  @Test
  void deserializationFailsWhenGameDataKeyBlank() {
    final String json =
        """
        {
          "territories": [],
          "players": [],
          "round": 1,
          "phase": "purchase",
          "currentPlayer": "Germans",
          "gameDataKey": "   "
        }
        """;
    assertThatThrownBy(() -> new ObjectMapper().readValue(json, WireState.class))
        .isInstanceOf(ValueInstantiationException.class)
        .hasRootCauseInstanceOf(IllegalArgumentException.class)
        .hasRootCauseMessage("WireState.gameDataKey is required");
  }

  @Test
  void constructorRejectsNullGameDataKey() {
    assertThatThrownBy(
            () -> new WireState(List.of(), List.of(), 1, "purchase", "Germans", List.of(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("WireState.gameDataKey is required");
  }

  @Test
  void constructorRejectsBlankGameDataKey() {
    assertThatThrownBy(
            () -> new WireState(List.of(), List.of(), 1, "purchase", "Germans", List.of(), "   "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("WireState.gameDataKey is required");
  }
}
