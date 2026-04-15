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
  void mapsNonCombatMovePhaseToNonCombatMoveStep() {
    assertEquals("GermansNonCombatMove", StepNameMapper.toJavaStepName("nonCombatMove", "Germans"));
  }

  @Test
  void mapsPlacePhaseToPlaceStep() {
    assertEquals("GermansPlace", StepNameMapper.toJavaStepName("place", "Germans"));
  }

  @Test
  void unknownPhaseThrows() {
    assertThrows(IllegalArgumentException.class,
        () -> StepNameMapper.toJavaStepName("mystery", "Germans"));
  }
}
