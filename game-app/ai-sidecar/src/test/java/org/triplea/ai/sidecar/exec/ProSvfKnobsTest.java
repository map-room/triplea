package org.triplea.ai.sidecar.exec;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.data.AiTheaterPriority;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.triplea.ai.sidecar.wire.WireState;

/**
 * Precedence tests for the wire-canonical + env-fallback knob path introduced in PR-B
 * (map-room#2755). Sysprops are used as the env-var stand-in because env vars are immutable after
 * JVM start — the {@link ProSvfKnobs#read} helper checks both with env winning.
 */
class ProSvfKnobsTest {

  @BeforeEach
  void clear() {
    System.clearProperty("ai.theater.priority");
    System.clearProperty("ai.svf.g.cap");
    System.clearProperty("ai.svf.gamma");
    System.clearProperty("ai.svf.w.strat");
    System.clearProperty("ai.svf.alpha.off");
    System.clearProperty("ai.svf.beta.launch");
  }

  @AfterEach
  void cleanup() {
    clear();
  }

  // ---------------------------------------------------------------------------
  // applyFromRequest — the canonical executor entry point
  // ---------------------------------------------------------------------------

  @Test
  void wirePresent_winsOverEnvForTheaterPriority() {
    System.setProperty("ai.theater.priority", "KJF"); // env says KJF
    final ProData proData = new ProData();
    final WireState state =
        new WireState(List.of(), List.of(), 1, "purchase", "Americans", List.of(), null, "KGF");

    ProSvfKnobs.applyFromRequest(proData, state);

    assertThat(proData.getAiTheaterPriority())
        .as("wire field KGF must override env-var KJF")
        .isEqualTo(AiTheaterPriority.KGF);
  }

  @Test
  void wireAbsent_envFallback_used_forTheaterPriority() {
    System.setProperty("ai.theater.priority", "KJF");
    final ProData proData = new ProData();
    final WireState state =
        new WireState(List.of(), List.of(), 1, "purchase", "Americans", List.of(), null, null);

    ProSvfKnobs.applyFromRequest(proData, state);

    assertThat(proData.getAiTheaterPriority())
        .as("wire absent + env present → env wins")
        .isEqualTo(AiTheaterPriority.KJF);
  }

  @Test
  void wireAbsent_envAbsent_defaultKgfStays() {
    final ProData proData = new ProData();
    final WireState state =
        new WireState(List.of(), List.of(), 1, "purchase", "Americans", List.of(), null, null);

    ProSvfKnobs.applyFromRequest(proData, state);

    assertThat(proData.getAiTheaterPriority()).isEqualTo(AiTheaterPriority.KGF);
  }

  @Test
  void wireEmptyString_treatedAsAbsent_envFallbackUsed() {
    System.setProperty("ai.theater.priority", "KJF");
    final ProData proData = new ProData();
    final WireState state =
        new WireState(List.of(), List.of(), 1, "purchase", "Americans", List.of(), null, "");

    ProSvfKnobs.applyFromRequest(proData, state);

    assertThat(proData.getAiTheaterPriority()).isEqualTo(AiTheaterPriority.KJF);
  }

  @Test
  void numericKnobs_alwaysFromEnv_regardlessOfWire() {
    // Numeric knobs are not on the wire; env is the only source. Verify all five flow through.
    System.setProperty("ai.svf.g.cap", "100.0");
    System.setProperty("ai.svf.gamma", "0.90");
    System.setProperty("ai.svf.w.strat", "1.5");
    System.setProperty("ai.svf.alpha.off", "0.05");
    System.setProperty("ai.svf.beta.launch", "12.5");

    final ProData proData = new ProData();
    final WireState state =
        new WireState(List.of(), List.of(), 1, "purchase", "Americans", List.of(), null, "KGF");

    ProSvfKnobs.applyFromRequest(proData, state);

    assertThat(proData.getGCap()).isEqualTo(100.0);
    assertThat(proData.getGamma()).isEqualTo(0.90);
    assertThat(proData.getWStrat()).isEqualTo(1.5);
    assertThat(proData.getAlphaOff()).isEqualTo(0.05);
    assertThat(proData.getBetaLaunch()).isEqualTo(12.5);
  }

  @Test
  void invalidNumericKnob_silentlyKeepsDefault() {
    System.setProperty("ai.svf.g.cap", "not-a-number");

    final ProData proData = new ProData();
    ProSvfKnobs.applyNumericKnobs(proData);

    assertThat(proData.getGCap())
        .as("unparseable numeric knob silently uses the ProData default")
        .isEqualTo(75.0);
  }
}
