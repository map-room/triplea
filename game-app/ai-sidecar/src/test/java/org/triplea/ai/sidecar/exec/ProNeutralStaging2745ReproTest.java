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
import org.triplea.ai.sidecar.dto.NoncombatMoveRequest;
import org.triplea.ai.sidecar.dto.WireMoveDescription;
import org.triplea.ai.sidecar.wire.WireState;

/**
 * Seeded round-1 Americans noncombat on ww2global40_2nd_edition for map-room#2745.
 *
 * <p>Before the fix the Atlantic transport left 101 Sea Zone for 64 Sea Zone. 101 looked
 * unsurvivable because British ships were scored as attackers while America was not at war with
 * anyone, and 64 was the quiet sea zone beside the South American True Neutrals. Ground was then
 * one tied territory-value away from following it (Central America / Southeast Mexico
 * needAmphibUnitValue 5502 in match yE0Uc2-WT27).
 */
class ProNeutralStaging2745ReproTest {

  private static final long SEED = 2745L;
  private static CanonicalGameData canonical;

  @BeforeAll
  static void init() {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load("ww2global40_2nd_edition.xml");
  }

  /**
   * The real round-1 board as Map Room serializes it, moved to the Americans' noncombat step. The
   * wire carries each unit's naval/air-base bonus (map-room#4648); the planner no longer adds it a
   * second time, so an empty wire (canonical units, no bonus) would under-reach.
   */
  private static WireState americansRound1Noncombat() {
    try (InputStream in =
        ProNeutralStaging2745ReproTest.class
            .getClassLoader()
            .getResourceAsStream("wire-contract/wire-state-g1940-uk-economies.json")) {
      final ObjectNode tree = (ObjectNode) new ObjectMapper().readTree(in);
      tree.put("phase", "nonCombatMove").put("currentPlayer", "Americans");
      return new ObjectMapper().treeToValue(tree, WireState.class);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void seededAmericansRound1DoesNotStageOnTheNeutralCoast() {
    final List<String> threatLines = new ArrayList<>();
    final var previous = ProLogUi.getExternalHandler();
    ProLogUi.setExternalHandler(
        msg -> {
          if (msg.contains("strengthDifference")
              || msg.contains("moved to safest territory")
              || msg.contains("added to best territory")
              || msg.contains("seaValue=")
              || msg.contains("load value")
              || msg.contains("moved towards best loading")) {
            synchronized (threatLines) {
              threatLines.add(msg);
            }
          }
        });
    final List<WireMoveDescription> moves;
    try {
      moves =
          new NoncombatMoveExecutor()
              .execute(canonical, new NoncombatMoveRequest(americansRound1Noncombat(), SEED))
              .moves();
    } finally {
      ProLogUi.setExternalHandler(previous);
    }

    assertThat(moves).isNotEmpty();
    assertThat(moves)
        .as("transport threat lines:%n%s", String.join("\n", threatLines))
        .noneMatch(m -> "101 Sea Zone".equals(m.from()) && "64 Sea Zone".equals(m.to()))
        .noneMatch(m -> "64 Sea Zone".equals(m.to()))
        .noneMatch(
            m ->
                "Central America".equals(m.to())
                    || "Southeast Mexico".equals(m.to())
                    || "West Indies".equals(m.to()));
    assertThat(threatLines)
        .as("101 Sea Zone must not be treated as under British attack")
        .noneMatch(line -> line.contains("101 Sea Zone") && line.contains("owned by British"));
  }
}
