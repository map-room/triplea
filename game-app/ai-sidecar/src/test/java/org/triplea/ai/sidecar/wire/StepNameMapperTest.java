package org.triplea.ai.sidecar.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameStep;
import games.strategy.triplea.settings.ClientSetting;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;

/**
 * Step resolution is per-edition: Global 1940 uses plural player names in step tags ({@code
 * germansPurchase}); WW2v3-1941 uses singular adjectives ({@code germanPurchase}). Both must
 * resolve, and a miss must throw rather than soft-warn.
 */
class StepNameMapperTest {

  private static GameData g40;
  private static GameData ww2v3;

  @BeforeAll
  static void loadMaps() {
    ClientSetting.setPreferences(new MemoryPreferences());
    g40 = CanonicalGameData.load("ww2global40_2nd_edition.xml").cloneForSession();
    ww2v3 = CanonicalGameData.load("WW2v3-1941.xml").cloneForSession();
  }

  // --- G40 (plural player name in step tags) ---

  @Test
  void g40_germansPurchase() {
    final GameStep step = StepNameMapper.resolve(g40, "purchase", "Germans");
    assertEquals("germansPurchase", step.getName());
  }

  @Test
  void g40_americansCombatMove() {
    final GameStep step = StepNameMapper.resolve(g40, "combatMove", "Americans");
    assertEquals("americansCombatMove", step.getName());
  }

  @Test
  void g40_germansNonCombatMove_notCombatMove() {
    final GameStep step = StepNameMapper.resolve(g40, "nonCombatMove", "Germans");
    assertEquals("germansNonCombatMove", step.getName());
  }

  @Test
  void g40_britishPlace_isNoAirCheck() {
    // G40 British placement uses placeNoAirCheck — name is britishNoAirCheckPlace, not
    // britishPlace.
    final GameStep step = StepNameMapper.resolve(g40, "place", "British");
    assertEquals("britishNoAirCheckPlace", step.getName());
  }

  @Test
  void g40_combatMove_doesNotPickAirborne() {
    final GameStep step = StepNameMapper.resolve(g40, "combatMove", "Germans");
    assertEquals("germansCombatMove", step.getName());
  }

  // --- WW2v3-1941 (singular adjective in step tags) — the #3535 regression ---

  @Test
  void ww2v3_germanPurchase_singular() {
    final GameStep step = StepNameMapper.resolve(ww2v3, "purchase", "Germans");
    assertEquals("germanPurchase", step.getName());
  }

  @Test
  void ww2v3_americanCombatMove_singular() {
    final GameStep step = StepNameMapper.resolve(ww2v3, "combatMove", "Americans");
    assertEquals("americanCombatMove", step.getName());
  }

  @Test
  void ww2v3_italianNonCombatMove_singular() {
    final GameStep step = StepNameMapper.resolve(ww2v3, "nonCombatMove", "Italians");
    assertEquals("italianNonCombatMove", step.getName());
  }

  @Test
  void ww2v3_britishPlace_isPlainPlace() {
    final GameStep step = StepNameMapper.resolve(ww2v3, "place", "British");
    assertEquals("britishPlace", step.getName());
  }

  @Test
  void ww2v3_allSevenNationsPurchase() {
    for (final String nation :
        new String[] {
          "Germans", "Russians", "Japanese", "British", "Italians", "Americans", "Chinese"
        }) {
      final GameStep step = StepNameMapper.resolve(ww2v3, "purchase", nation);
      assertTrue(
          step.getName().toLowerCase().endsWith("purchase"),
          () -> "expected *Purchase for " + nation + ", got " + step.getName());
      assertEquals(nation, step.getPlayerId().getName());
    }
  }

  // --- Fail closed ---

  @Test
  void unknownPhaseThrows() {
    assertThrows(
        IllegalArgumentException.class, () -> StepNameMapper.resolve(g40, "mystery", "Germans"));
  }

  @Test
  void unknownPlayerThrows() {
    assertThrows(
        IllegalArgumentException.class, () -> StepNameMapper.resolve(g40, "purchase", "Elves"));
  }

  @Test
  void legacyToJavaStepName_stillConstructsG40Form() {
    // Kept only as a unit-test helper for the old construction form.
    assertEquals("GermansPurchase", StepNameMapper.toJavaStepName("purchase", "Germans"));
    assertEquals("GermansCombatMove", StepNameMapper.toJavaStepName("combatMove", "Germans"));
    assertEquals("GermansBattle", StepNameMapper.toJavaStepName("battle", "Germans"));
    assertEquals("GermansNonCombatMove", StepNameMapper.toJavaStepName("nonCombatMove", "Germans"));
    assertEquals("GermansPlace", StepNameMapper.toJavaStepName("place", "Germans"));
  }
}
