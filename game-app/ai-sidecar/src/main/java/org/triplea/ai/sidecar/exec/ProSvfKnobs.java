package org.triplea.ai.sidecar.exec;

import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.data.AiTheaterPriority;
import javax.annotation.Nullable;
import org.triplea.ai.sidecar.wire.WireState;

/**
 * Reads Strategic Value Field tuning knobs from process env vars (with sysprop fallback) and
 * applies them to a per-request {@link ProData} instance. Two roles:
 *
 * <ul>
 *   <li>{@link #applyNumericKnobs(ProData)} — always called by executors. Reads the five tuning
 *       knobs ({@code AI_SVF_G_CAP}, {@code AI_SVF_GAMMA}, {@code AI_SVF_W_STRAT}, {@code
 *       AI_SVF_ALPHA_OFF}, {@code AI_SVF_BETA_LAUNCH}). These are not part of the wire protocol
 *       (Phase 4 may promote them); they remain env-only for tuning runs.
 *   <li>{@link #applyTheaterPriorityFromEnv(ProData)} — called as a <em>fallback</em> by executors
 *       when the wire field {@code aiTheaterPriority} is absent (e.g., a curl debug request that
 *       omitted the field). The wire is canonical when present; this is the debug-only override
 *       path.
 * </ul>
 *
 * <p>Mirrors the {@code AI_DUMP_VALUES} / {@code ai.dump.values} pattern from {@code
 * ProValueHeatmapDumper}. Sysprops are for tests that need to flip a knob without spawning a child
 * JVM; env vars are canonical for docker-compose deployments.
 *
 * <table>
 *   <caption>Recognized knobs</caption>
 *   <tr><th>Env var</th><th>Sysprop</th><th>ProData setter</th><th>Default</th></tr>
 *   <tr><td>{@code AI_THEATER_PRIORITY}</td><td>{@code ai.theater.priority}</td>
 *       <td>{@link ProData#setAiTheaterPriority}</td>
 *       <td>{@code KGF} (or whatever the wire field says — wire wins)</td></tr>
 *   <tr><td>{@code AI_SVF_G_CAP}</td><td>{@code ai.svf.g.cap}</td>
 *       <td>{@link ProData#setGCap}</td><td>{@code 75.0}</td></tr>
 *   <tr><td>{@code AI_SVF_GAMMA}</td><td>{@code ai.svf.gamma}</td>
 *       <td>{@link ProData#setGamma}</td><td>{@code 0.85}</td></tr>
 *   <tr><td>{@code AI_SVF_W_STRAT}</td><td>{@code ai.svf.w.strat}</td>
 *       <td>{@link ProData#setWStrat}</td><td>{@code 0.0}</td></tr>
 *   <tr><td>{@code AI_SVF_ALPHA_OFF}</td><td>{@code ai.svf.alpha.off}</td>
 *       <td>{@link ProData#setAlphaOff}</td><td>{@code 0.10}</td></tr>
 *   <tr><td>{@code AI_SVF_BETA_LAUNCH}</td><td>{@code ai.svf.beta.launch}</td>
 *       <td>{@link ProData#setBetaLaunch}</td><td>{@code 0.0}</td></tr>
 * </table>
 *
 * <p>Null, empty, or unparseable values silently leave the ProData default in place — knobs are
 * opt-in tuning, not validated configuration. A typo just means "use the default", never an
 * exception that breaks the request.
 */
public final class ProSvfKnobs {

  private ProSvfKnobs() {}

  /**
   * Applies the five numeric tuning knobs ({@code gCap}, {@code gamma}, {@code wStrat}, {@code
   * alphaOff}, {@code betaLaunch}) to {@code proData} in place. Called unconditionally by executors
   * — these knobs are not on the wire protocol so env/sysprop is the only source.
   */
  public static void applyNumericKnobs(final ProData proData) {
    applyDouble("AI_SVF_G_CAP", "ai.svf.g.cap", proData::setGCap);
    applyDouble("AI_SVF_GAMMA", "ai.svf.gamma", proData::setGamma);
    applyDouble("AI_SVF_W_STRAT", "ai.svf.w.strat", proData::setWStrat);
    applyDouble("AI_SVF_ALPHA_OFF", "ai.svf.alpha.off", proData::setAlphaOff);
    applyDouble("AI_SVF_BETA_LAUNCH", "ai.svf.beta.launch", proData::setBetaLaunch);
  }

  /**
   * Applies the theater priority from env/sysprop if set, no-op otherwise. Executors call this
   * <em>only</em> as a fallback when the wire field {@code aiTheaterPriority} is absent — the wire
   * is canonical when present.
   */
  public static void applyTheaterPriorityFromEnv(final ProData proData) {
    final String theater = read("AI_THEATER_PRIORITY", "ai.theater.priority");
    if (theater != null) {
      proData.setAiTheaterPriority(AiTheaterPriority.fromWire(theater));
    }
  }

  /**
   * Canonical entry point for sidecar executors: applies the numeric tuning knobs (env only) and
   * the theater priority (wire-canonical with env fallback). Wire wins when present; env is the
   * debug-only override path for curl/test requests that omit the {@code aiTheaterPriority} field.
   */
  public static void applyFromRequest(final ProData proData, final WireState state) {
    applyNumericKnobs(proData);
    final String wireFlag = state.aiTheaterPriority();
    if (wireFlag != null && !wireFlag.isEmpty()) {
      proData.setAiTheaterPriority(AiTheaterPriority.fromWire(wireFlag));
    } else {
      applyTheaterPriorityFromEnv(proData);
    }
  }

  /**
   * @deprecated Use {@link #applyFromRequest} from sidecar executors. Retained as a thin wrapper
   *     for any legacy call sites still binding env-only.
   */
  @Deprecated
  public static void applyTo(final ProData proData) {
    applyTheaterPriorityFromEnv(proData);
    applyNumericKnobs(proData);
  }

  private static void applyDouble(
      final String envName, final String sysName, final java.util.function.DoubleConsumer setter) {
    final String raw = read(envName, sysName);
    if (raw == null) {
      return;
    }
    try {
      setter.accept(Double.parseDouble(raw));
    } catch (final NumberFormatException e) {
      // Silently keep the default — knobs are opt-in tuning, never request-breaking.
    }
  }

  private static @Nullable String read(final String envName, final String sysName) {
    final String env = System.getenv(envName);
    if (env != null && !env.isEmpty()) {
      return env;
    }
    final String sys = System.getProperty(sysName);
    if (sys != null && !sys.isEmpty()) {
      return sys;
    }
    return null;
  }
}
