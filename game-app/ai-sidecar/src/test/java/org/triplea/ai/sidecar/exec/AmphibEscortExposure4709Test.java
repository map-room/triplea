package org.triplea.ai.sidecar.exec;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.triplea.ai.sidecar.dto.PurchasePlan;
import org.triplea.ai.sidecar.dto.PurchaseRequest;
import org.triplea.ai.sidecar.dto.WireMoveDescription;
import org.triplea.ai.sidecar.wire.WireState;

/**
 * map-room#4709 fix 4: the transport-exposure check counts the warships that will escort a landing.
 *
 * <p>The board is the real Global 1940 round-2 Japanese turn from a seeded AI-vs-AI run (seed 4609,
 * Map Room's own wire serialization). Japan's main fleet (2 battleships, 2 carriers, 2 cruisers, 3
 * destroyers and 2 submarines) and three loaded transports sit in 35 Sea Zone. Java is an empty
 * landing from 42 Sea Zone. The check counted only the lone transport as 42 Sea Zone's defence
 * (swing 7 against an attack value of 5.1) and dropped the landing, though the fleet can reach 42
 * Sea Zone in non-combat move.
 */
class AmphibEscortExposure4709Test {

  private static final long SEED = 4609L;
  private static CanonicalGameData canonical;

  @BeforeAll
  static void init() {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load("ww2global40_2nd_edition.xml");
  }

  private static WireState japaneseRound2Purchase() {
    try (InputStream in =
        AmphibEscortExposure4709Test.class
            .getClassLoader()
            .getResourceAsStream("amphib-4709/wire-state-g1940-japanese-r2.json")) {
      final ObjectNode tree = (ObjectNode) new ObjectMapper().readTree(in);
      tree.put("phase", "purchase");
      return new ObjectMapper().treeToValue(tree, WireState.class);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void escortedLandingOnJavaIsNotDroppedAsExposed() {
    final List<String> exposureLines = new ArrayList<>();
    final var previous = ProLogUi.getExternalHandler();
    ProLogUi.setExternalHandler(
        msg -> {
          if (msg.contains("amphib territory: Java")
              || msg.contains("42 Sea Zone, EnemyAttackers")) {
            synchronized (exposureLines) {
              exposureLines.add(msg);
            }
          }
        });
    final PurchasePlan plan;
    try {
      plan =
          new PurchaseExecutor()
              .executeOn(
                  canonical.cloneForSession(),
                  new PurchaseRequest(japaneseRound2Purchase(), SEED, "amphib-4709"));
    } finally {
      ProLogUi.setExternalHandler(previous);
    }

    assertThat(exposureLines)
        .as("exposure-check lines for Java")
        .noneMatch(l -> l.contains("Removing amphib territory: Java"));
    assertThat(plan.combatMoves())
        .as("combat moves (exposure lines: %s)", exposureLines)
        .extracting(WireMoveDescription::to)
        .contains("Java");
  }
}
