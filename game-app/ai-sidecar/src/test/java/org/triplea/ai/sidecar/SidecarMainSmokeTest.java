package org.triplea.ai.sidecar;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class SidecarMainSmokeTest {
  @Test
  void sidecarMainClassExists() {
    assertNotNull(SidecarMain.class);
  }
}
