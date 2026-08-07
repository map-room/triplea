package org.triplea.ai.sidecar.wire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import games.strategy.engine.data.GameData;
import games.strategy.triplea.settings.ClientSetting;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;

/**
 * Regression for map-room#3535: WW2v3-1941 step names are singular adjectives ({@code
 * germanPurchase}), not the plural form the G40-era StepNameMapper constructed. Applying a purchase
 * wire state must park the sequence on the correct step — not leave it on {@code gameInitDelegate}.
 * A deliberate miss must throw, not soft-warn.
 */
class WireStateApplierWw2v3StepTest {

  private static CanonicalGameData canonical;

  @BeforeAll
  static void init() {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load("WW2v3-1941.xml");
  }

  @Test
  void advancesSequenceToGermanPurchase() {
    final GameData gd = canonical.cloneForSession();
    final WireState wire =
        new WireState(List.of(), List.of(), 1, "purchase", "Germans", List.of(), "ww2v3_1941");
    WireStateApplier.apply(gd, wire, new ConcurrentHashMap<String, UUID>());

    assertThat(gd.getSequence().getRound()).isEqualTo(1);
    assertThat(gd.getSequence().getStep().getName()).isEqualTo("germanPurchase");
    assertThat(gd.getSequence().getStep().getPlayerId().getName()).isEqualTo("Germans");
  }

  @Test
  void advancesSequenceToAmericanNonCombatMove() {
    final GameData gd = canonical.cloneForSession();
    final WireState wire =
        new WireState(
            List.of(), List.of(), 2, "nonCombatMove", "Americans", List.of(), "ww2v3_1941");
    WireStateApplier.apply(gd, wire, new ConcurrentHashMap<String, UUID>());

    assertThat(gd.getSequence().getStep().getName()).isEqualTo("americanNonCombatMove");
    assertThat(gd.getSequence().getStep().getPlayerId().getName()).isEqualTo("Americans");
  }

  @Test
  void advancesSequenceForAllSevenNationsOnPurchase() {
    for (final String nation :
        new String[] {
          "Germans", "Russians", "Japanese", "British", "Italians", "Americans", "Chinese"
        }) {
      final GameData gd = canonical.cloneForSession();
      final WireState wire =
          new WireState(List.of(), List.of(), 1, "purchase", nation, List.of(), "ww2v3_1941");
      WireStateApplier.apply(gd, wire, new ConcurrentHashMap<String, UUID>());
      assertThat(gd.getSequence().getStep().getName())
          .as("purchase step for %s", nation)
          .endsWithIgnoringCase("Purchase");
      assertThat(gd.getSequence().getStep().getPlayerId().getName()).isEqualTo(nation);
      // Must NOT still be parked on gameInitDelegate — that was the #3535 failure mode.
      assertThat(gd.getSequence().getStep().getName()).isNotEqualTo("gameInitDelegate");
    }
  }

  @Test
  void unmappedPhaseLeavesSequenceUntouched() {
    final GameData gd = canonical.cloneForSession();
    final String before = gd.getSequence().getStep().getName();
    final WireState wire =
        new WireState(List.of(), List.of(), 1, "tech", "Germans", List.of(), "ww2v3_1941");
    WireStateApplier.apply(gd, wire, new ConcurrentHashMap<String, UUID>());
    assertThat(gd.getSequence().getStep().getName()).isEqualTo(before);
  }

  @Test
  void unknownPlayerThrowsRatherThanSoftSkipping() {
    final GameData gd = canonical.cloneForSession();
    final WireState wire =
        new WireState(List.of(), List.of(), 1, "purchase", "Elves", List.of(), "ww2v3_1941");
    assertThatThrownBy(
            () -> WireStateApplier.apply(gd, wire, new ConcurrentHashMap<String, UUID>()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown player");
  }
}
