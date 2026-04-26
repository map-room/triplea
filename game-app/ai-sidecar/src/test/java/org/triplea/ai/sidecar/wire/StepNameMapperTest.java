package org.triplea.ai.sidecar.wire;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class StepNameMapperTest {
  @Test
  void mapsPurchasePhaseToPurchaseStep() {
    assertEquals("GermansPurchase", StepNameMapper.toJavaStepName("purchase", "Germans"));
  }

  @Test
  void mapsCombatMovePhaseToCombatMoveStep() {
    assertEquals("GermansCombatMove", StepNameMapper.toJavaStepName("combatMove", "Germans"));
  }

  @Test
  void mapsBattlePhaseToBattleStep() {
    assertEquals("GermansBattle", StepNameMapper.toJavaStepName("battle", "Germans"));
  }

  @Test
  void mapsNonCombatMovePhaseToNonCombatMoveStep() {
    assertEquals("GermansNonCombatMove", StepNameMapper.toJavaStepName("nonCombatMove", "Germans"));
  }

  @Test
  void mapsPlacePhaseToPlaceStep() {
    assertEquals("GermansPlace", StepNameMapper.toJavaStepName("place", "Germans"));
  }

  @Test
  void mapsBritishPlacePhaseToNoAirCheckStep() {
    // British placement uses the placeNoAirCheck delegate, so the XML step name is
    // britishNoAirCheckPlace — not britishPlace. Regression fence for #2012.
    assertEquals("britishNoAirCheckPlace", StepNameMapper.toJavaStepName("place", "British"));
  }

  @Test
  void mapsBritishPurchasePhaseToGenericPattern() {
    // Other British phases follow the standard pattern; only place is overridden.
    assertEquals("BritishPurchase", StepNameMapper.toJavaStepName("purchase", "British"));
  }

  @Test
  void unknownPhaseThrows() {
    assertThrows(
        IllegalArgumentException.class, () -> StepNameMapper.toJavaStepName("mystery", "Germans"));
  }
}
