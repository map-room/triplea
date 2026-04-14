package org.triplea.ai.sidecar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SidecarConfigTest {
  @Test
  void defaultsWhenEnvEmpty() {
    final SidecarConfig c = SidecarConfig.fromEnv(Map.of());
    assertEquals("0.0.0.0", c.bindHost());
    assertEquals(8099, c.port());
    assertEquals(4, c.workerCount());
    assertEquals("dev-token", c.authToken());
  }

  @Test
  void envOverridesDefaults() {
    final SidecarConfig c =
        SidecarConfig.fromEnv(
            Map.of(
                "SIDECAR_BIND_HOST", "127.0.0.1",
                "SIDECAR_PORT", "9100",
                "SIDECAR_WORKERS", "8",
                "SIDECAR_AUTH_TOKEN", "prod-token-xyz"));
    assertEquals("127.0.0.1", c.bindHost());
    assertEquals(9100, c.port());
    assertEquals(8, c.workerCount());
    assertEquals("prod-token-xyz", c.authToken());
  }
}
