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
 * map-room#4709 fix 4, second half: once a landing was kept because warships could escort it, the
 * non-combat move sends them.
 *
 * <p>The board is the real Global 1940 round-2 Japanese non-combat move from the seeded run with
 * fix 4 (seed 4609). Japan took Java from 42 Sea Zone; the empty transport sits there with three
 * American warships and a fighter in reach. The main fleet is in 35 Sea Zone and can reach 42 Sea
 * Zone. Without the follow-through the planner valued 42 Sea Zone at 0, gave up on it and sailed
 * the whole fleet to 36 Sea Zone; the transport was sunk.
 */
class AmphibEscortFollowThrough4709Test {

  private static final long SEED = 4609L;
  private static CanonicalGameData canonical;

  @BeforeAll
  static void init() {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load("ww2global40_2nd_edition.xml");
  }

  private static WireState japaneseRound2Noncombat() {
    try (InputStream in =
        AmphibEscortFollowThrough4709Test.class
            .getClassLoader()
            .getResourceAsStream("amphib-4709/wire-state-g1940-japanese-r2-ncm.json")) {
      return new ObjectMapper().readValue(in, WireState.class);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void warshipsSailToGuardTheTransportThatLandedOnJava() {
    final List<String> lines = new ArrayList<>();
    final var previous = ProLogUi.getExternalHandler();
    ProLogUi.setExternalHandler(
        msg -> {
          if (msg.contains("42 Sea Zone")) {
            synchronized (lines) {
              lines.add(msg);
            }
          }
        });
    final List<WireMoveDescription> moves;
    try {
      moves =
          new NoncombatMoveExecutor()
              .execute(
                  canonical,
                  new NoncombatMoveRequest(japaneseRound2Noncombat(), SEED, "amphib-4709"))
              .moves();
    } finally {
      ProLogUi.setExternalHandler(previous);
    }

    assertThat(moves)
        .as("42 Sea Zone lines:%n%s", String.join("\n", lines))
        .anyMatch(m -> "42 Sea Zone".equals(m.to()) && !m.from().equals("42 Sea Zone"));
  }
}
