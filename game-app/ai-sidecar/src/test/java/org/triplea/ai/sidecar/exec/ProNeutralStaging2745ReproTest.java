package org.triplea.ai.sidecar.exec;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.triplea.ai.pro.logging.ProLogUi;
import games.strategy.triplea.settings.ClientSetting;
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
              .execute(
                  canonical,
                  new NoncombatMoveRequest(
                      new WireState(
                          List.of(),
                          List.of(),
                          1,
                          "nonCombatMove",
                          "Americans",
                          List.of(),
                          "ww2global40_2nd_edition"),
                      SEED))
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
