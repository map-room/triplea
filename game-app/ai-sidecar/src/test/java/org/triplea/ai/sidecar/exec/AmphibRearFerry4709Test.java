package org.triplea.ai.sidecar.exec;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import games.strategy.triplea.ai.pro.logging.ProLogUi;
import games.strategy.triplea.settings.ClientSetting;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.dto.NoncombatMoveRequest;
import org.triplea.ai.sidecar.dto.WireMoveDescription;
import org.triplea.ai.sidecar.wire.WireState;

/**
 * map-room#4709 fix 5: the non-combat "Move amphib units" pass does not ferry land units to the
 * rear.
 *
 * <p>The board is the real Global 1940 round-1 American non-combat move from a seeded AI-vs-AI run
 * (seed 4609, Map Room's own wire serialization). The planner loaded an infantry and an armour in
 * Central United States, sailed them to 64 Sea Zone and unloaded them in Central America. Central
 * America is no closer to any enemy than Central United States, and the two are joined by land.
 */
class AmphibRearFerry4709Test {

  private static final long SEED = 4609L;
  private static CanonicalGameData canonical;

  @BeforeAll
  static void init() {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load("ww2global40_2nd_edition.xml");
  }

  private static WireState americansRound1Noncombat() {
    try (InputStream in =
        AmphibRearFerry4709Test.class
            .getClassLoader()
            .getResourceAsStream("amphib-4709/wire-state-g1940-americans-r1-ncm.json")) {
      return new ObjectMapper().readValue(in, WireState.class);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void americansDoNotFerryCentralUnitedStatesTroopsToCentralAmerica() {
    final List<String> amphibLines = new ArrayList<>();
    final var previous = ProLogUi.getExternalHandler();
    ProLogUi.setExternalHandler(
        msg -> {
          if (msg.contains("unloading to") || msg.contains("to the rear")) {
            synchronized (amphibLines) {
              amphibLines.add(msg);
            }
          }
        });
    final List<WireMoveDescription> moves;
    try {
      moves =
          new NoncombatMoveExecutor()
              .execute(
                  canonical,
                  new NoncombatMoveRequest(americansRound1Noncombat(), SEED, "amphib-4709"))
              .moves();
    } finally {
      ProLogUi.setExternalHandler(previous);
    }

    // Only an unload from a sea zone is a ferry; a mech infantry driving overland to Central
    // America is a land move and stays.
    assertThat(moves)
        .as("amphib lines:%n%s", String.join("\n", amphibLines))
        .noneMatch(m -> m.from().endsWith("Sea Zone") && "Central America".equals(m.to()));
  }
}
