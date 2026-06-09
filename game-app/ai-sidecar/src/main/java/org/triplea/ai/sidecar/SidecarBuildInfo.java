package org.triplea.ai.sidecar;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Fork version and git commit SHA baked in at build time via {@code processResources}.
 *
 * <p>The values come from {@code sidecar-build.properties} on the classpath, which Gradle expands
 * from template variables during the build. Falls back to {@code "unknown"} when the resource is
 * absent (e.g. running directly from IDE sources without a prior Gradle build).
 */
public final class SidecarBuildInfo {

  public final String version;
  public final String commit;

  private SidecarBuildInfo(final String version, final String commit) {
    this.version = version;
    this.commit = commit;
  }

  public static SidecarBuildInfo load() {
    final Properties props = new Properties();
    try (final InputStream is =
        SidecarBuildInfo.class.getResourceAsStream("/sidecar-build.properties")) {
      if (is != null) {
        props.load(is);
      }
    } catch (final IOException ignored) {
      // Fall through to defaults.
    }
    return new SidecarBuildInfo(
        props.getProperty("sidecar.version", "unknown"),
        props.getProperty("sidecar.commit", "unknown"));
  }
}
