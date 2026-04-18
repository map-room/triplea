package org.triplea.ai.sidecar.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Wire-contract tests for {@link CombatMoveOrder}.
 *
 * <p>These tests verify the Jackson round-trip behaviour introduced in #1761:
 * <ul>
 *   <li>Absent {@code kind} field defaults to {@code "move"} (backwards-compat for old payloads).
 *   <li>{@code kind="load"} and {@code transportId} round-trip correctly.
 *   <li>{@code kind="unload"} round-trips correctly.
 *   <li>3-arg convenience constructor produces {@code kind="move"} and null {@code transportId}.
 * </ul>
 */
class CombatMoveOrderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void absentKindDefaultsToMove() throws Exception {
    // Legacy payload: no kind or transportId field.
    final String json =
        "{\"unitIds\":[\"u1\",\"u2\"],\"from\":\"Germany\",\"to\":\"Poland\"}";
    final CombatMoveOrder order = MAPPER.readValue(json, CombatMoveOrder.class);
    assertThat(order.unitIds()).containsExactly("u1", "u2");
    assertThat(order.from()).isEqualTo("Germany");
    assertThat(order.to()).isEqualTo("Poland");
    assertThat(order.kind()).isEqualTo("move");
    assertThat(order.transportId()).isNull();
  }

  @Test
  void loadOrderRoundTrips() throws Exception {
    final CombatMoveOrder original =
        new CombatMoveOrder(List.of("inf1"), "France", "Sea Zone 7", "load", "transport1");
    final String json = MAPPER.writeValueAsString(original);
    final CombatMoveOrder restored = MAPPER.readValue(json, CombatMoveOrder.class);
    assertThat(restored.unitIds()).containsExactly("inf1");
    assertThat(restored.from()).isEqualTo("France");
    assertThat(restored.to()).isEqualTo("Sea Zone 7");
    assertThat(restored.kind()).isEqualTo("load");
    assertThat(restored.transportId()).isEqualTo("transport1");
  }

  @Test
  void unloadOrderRoundTrips() throws Exception {
    final CombatMoveOrder original =
        new CombatMoveOrder(List.of("inf1", "arm1"), "Sea Zone 7", "Normandy", "unload", "transport1");
    final String json = MAPPER.writeValueAsString(original);
    final CombatMoveOrder restored = MAPPER.readValue(json, CombatMoveOrder.class);
    assertThat(restored.unitIds()).containsExactly("inf1", "arm1");
    assertThat(restored.from()).isEqualTo("Sea Zone 7");
    assertThat(restored.to()).isEqualTo("Normandy");
    assertThat(restored.kind()).isEqualTo("unload");
    assertThat(restored.transportId()).isEqualTo("transport1");
  }

  @Test
  void convenienceConstructorProducesPlainMove() {
    final CombatMoveOrder order =
        new CombatMoveOrder(List.of("tank1"), "Germany", "Poland");
    assertThat(order.kind()).isEqualTo("move");
    assertThat(order.transportId()).isNull();
  }

  @Test
  void nullKindInJsonDefaultsToMove() throws Exception {
    // Explicit null kind field should also default to "move" via the @JsonCreator.
    final String json =
        "{\"unitIds\":[\"u1\"],\"from\":\"A\",\"to\":\"B\",\"kind\":null,\"transportId\":null}";
    final CombatMoveOrder order = MAPPER.readValue(json, CombatMoveOrder.class);
    assertThat(order.kind()).isEqualTo("move");
    assertThat(order.transportId()).isNull();
  }
}
