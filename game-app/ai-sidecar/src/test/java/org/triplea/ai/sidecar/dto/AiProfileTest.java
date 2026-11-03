package org.triplea.ai.sidecar.dto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AiProfileTest {
  @Test
  void omittedAndHardAreStock() {
    assertFalse(AiProfile.isBeta(null));
    assertFalse(AiProfile.isBeta("hard"));
  }

  @Test
  void hardBetaIsBeta() {
    assertTrue(AiProfile.isBeta("hard-beta"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "beta", "true", "Hard Beta", "HARD", "hard-beta ", "easy"})
  void anythingElseIsRejected(final String profile) {
    assertThrows(IllegalArgumentException.class, () -> AiProfile.isBeta(profile));
  }
}
