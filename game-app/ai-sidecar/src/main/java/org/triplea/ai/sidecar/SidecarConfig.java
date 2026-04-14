package org.triplea.ai.sidecar;

import java.util.Map;

public record SidecarConfig(String bindHost, int port, int workerCount, String authToken) {
  public static SidecarConfig fromEnv(final Map<String, String> env) {
    return new SidecarConfig(
        env.getOrDefault("SIDECAR_BIND_HOST", "0.0.0.0"),
        Integer.parseInt(env.getOrDefault("SIDECAR_PORT", "8099")),
        Integer.parseInt(env.getOrDefault("SIDECAR_WORKERS", "4")),
        env.getOrDefault("SIDECAR_AUTH_TOKEN", "dev-token"));
  }
}
