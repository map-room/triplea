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
import org.triplea.ai.sidecar.dto.PurchasePlan;
import org.triplea.ai.sidecar.dto.PurchaseRequest;
import org.triplea.ai.sidecar.dto.WarDeclaration;
import org.triplea.ai.sidecar.wire.WireState;

/**
 * map-room#4733: ProAI plans a Japanese combat move into 8 Sea Zone, which MoveValidator refuses
 * ("Cannot move to restricted territories").
 *
 * <p>The purchase call simulates the rest of the turn on a copy of the game, politics first. Hard
 * Beta's politics declares war on the Allies, the Americans among them. Global 1940 lifts Japan's
 * Pacific restriction with a trigger once Japan is at war with the Americans, and the stateless
 * sidecar never fires triggers, so the copy was at war with the US with the restriction list still
 * binding. The planner (which reads the war) routed a transport into the restricted zone; the
 * simulation's own validator (which reads the list) refused every leg.
 *
 * <p>The board is Map Room's own wire serialization of the Global 1940 start with Japan to purchase
 * (round 1). It runs the real {@link PurchaseExecutor}: real ProPoliticsAi, real relationship
 * changes on the simulation copy, real MoveDelegate validation. Nothing is mocked.
 */
class SimulatedPoliticsRestrictionLift4733Test {

  private static final String REFUSAL = "Cannot move to restricted territories";
  private static CanonicalGameData canonical;

  @BeforeAll
  static void init() {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load("ww2global40_2nd_edition.xml");
  }

  /** Seed 4609 is the seed of the failing AI-vs-AI run; the others show it is not the dice. */
  @Test
  void japanDeclaringWarOnTheUsInSimulatedPoliticsIsNotRefusedItsOwnPacificMoves() {
    for (final long seed : new long[] {4609L, 1L, 2L}) {
      final List<String> refusals = new ArrayList<>();
      final PurchasePlan plan = plan(seed, refusals);

      assertThat(plan.politicalActions())
          .as("seed %d: the scenario needs Japan to declare war on the Americans", seed)
          .contains(new WarDeclaration("Americans"));
      assertThat(refusals).as("seed %d: moves refused by the restriction", seed).isEmpty();
    }
  }

  private static PurchasePlan plan(final long seed, final List<String> refusals) {
    final var previous = ProLogUi.getExternalHandler();
    ProLogUi.setExternalHandler(
        msg -> {
          if (msg.contains(REFUSAL)) {
            synchronized (refusals) {
              refusals.add(msg.trim());
            }
          }
        });
    try {
      return new PurchaseExecutor()
          .execute(
              canonical,
              new PurchaseRequest(japaneseRound1Purchase(), seed, "restr-4733", "hard-beta"));
    } finally {
      ProLogUi.setExternalHandler(previous);
    }
  }

  private static WireState japaneseRound1Purchase() {
    try (InputStream in =
        SimulatedPoliticsRestrictionLift4733Test.class
            .getClassLoader()
            .getResourceAsStream("restriction-4733/wire-state-g1940-japanese-r1-purchase.json")) {
      return new ObjectMapper().readValue(in, WireState.class);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
