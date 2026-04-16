package org.triplea.ai.sidecar;

import java.util.Map;

/**
 * Configuration loaded entirely from environment variables.
 *
 * <p>New in v2:
 * <ul>
 *   <li>{@code SIDECAR_DATA_DIR} — directory for session manifests (default: {@code data/sessions}).
 *   <li>{@code SIDECAR_SERVER_URL} — base URL of the Map Room server, used by the reaper to check
 *       gameover status (optional; gameover reaping is skipped when unset).
 * </ul>
 */
public record SidecarConfig(
    String bindHost,
    int port,
    int workerCount,
    String authToken,
    String dataDir,
    String serverUrl) {

  public static SidecarConfig fromEnv(final Map<String, String> env) {
    return new SidecarConfig(
        env.getOrDefault("SIDECAR_BIND_HOST", "0.0.0.0"),
        Integer.parseInt(env.getOrDefault("SIDECAR_PORT", "8099")),
        Integer.parseInt(env.getOrDefault("SIDECAR_WORKERS", "4")),
        env.getOrDefault("SIDECAR_AUTH_TOKEN", "dev-token"),
        env.getOrDefault("SIDECAR_DATA_DIR", "data/sessions"),
        env.getOrDefault("SIDECAR_SERVER_URL", null));
  }
}
