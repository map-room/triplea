package org.triplea.ai.sidecar.wire;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.triplea.ai.sidecar.dto.CombatMovePlan;
import org.triplea.ai.sidecar.dto.DecisionPlan;

class WireContractFixtureTest {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  @Test
  void wireStateFixtureRoundTripsByteIdentical() throws Exception {
    final byte[] original = readResource("wire-contract/wire-state-with-relationships.json");
    final WireState parsed = MAPPER.readValue(original, WireState.class);
    final byte[] reserialized = MAPPER.writeValueAsBytes(parsed);
    assertThat(normalize(reserialized)).isEqualTo(normalize(original));
  }

  @Test
  void combatMovePlanFixtureRoundTripsByteIdentical() throws Exception {
    final byte[] original = readResource("wire-contract/combat-move-plan-with-declarations.json");
    final CombatMovePlan parsed = (CombatMovePlan) MAPPER.readValue(original, DecisionPlan.class);
    final byte[] reserialized = MAPPER.writeValueAsBytes((DecisionPlan) parsed);
    assertThat(normalize(reserialized)).isEqualTo(normalize(original));
  }

  private static byte[] readResource(final String path) throws Exception {
    try (InputStream in =
        WireContractFixtureTest.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("resource not found: " + path);
      }
      return in.readAllBytes();
    }
  }

  /** Strip trailing newlines/whitespace; Jackson may or may not emit a final newline. */
  private static String normalize(final byte[] bytes) {
    return new String(bytes, java.nio.charset.StandardCharsets.UTF_8).stripTrailing();
  }
}
