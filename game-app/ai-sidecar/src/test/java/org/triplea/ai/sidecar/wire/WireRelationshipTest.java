package org.triplea.ai.sidecar.wire;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WireRelationshipTest {

  @Test
  void recordExposesAccessors() {
    final WireRelationship r = new WireRelationship("Germans", "Russians", "war");
    assertThat(r.a()).isEqualTo("Germans");
    assertThat(r.b()).isEqualTo("Russians");
    assertThat(r.kind()).isEqualTo("war");
  }
}
