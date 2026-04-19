package org.triplea.ai.sidecar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.logging.Level;
import org.junit.jupiter.api.Test;

class SidecarMainLoggingTest {

  @Test
  void parseJulLevelReturnsInfoForNull() {
    assertEquals(Level.INFO, SidecarMain.parseJulLevel(null));
  }

  @Test
  void parseJulLevelReturnsInfoForEmpty() {
    assertEquals(Level.INFO, SidecarMain.parseJulLevel(""));
  }

  @Test
  void parseJulLevelReturnsInfoForBlank() {
    assertEquals(Level.INFO, SidecarMain.parseJulLevel("   "));
  }

  @Test
  void parseJulLevelReturnsInfoForGarbage() {
    assertEquals(Level.INFO, SidecarMain.parseJulLevel("NONSENSE"));
  }

  @Test
  void parseJulLevelReturnsFineForLowerCase() {
    assertEquals(Level.FINE, SidecarMain.parseJulLevel("fine"));
  }

  @Test
  void parseJulLevelReturnsFineForUpperCase() {
    assertEquals(Level.FINE, SidecarMain.parseJulLevel("FINE"));
  }

  @Test
  void parseJulLevelReturnsFineForMixedCase() {
    assertEquals(Level.FINE, SidecarMain.parseJulLevel("Fine"));
  }

  @Test
  void parseJulLevelReturnsFiner() {
    assertEquals(Level.FINER, SidecarMain.parseJulLevel("FINER"));
  }

  @Test
  void parseJulLevelReturnsFinest() {
    assertEquals(Level.FINEST, SidecarMain.parseJulLevel("FINEST"));
  }

  @Test
  void parseJulLevelReturnsWarning() {
    assertEquals(Level.WARNING, SidecarMain.parseJulLevel("WARNING"));
  }

  @Test
  void parseJulLevelReturnsSevere() {
    assertEquals(Level.SEVERE, SidecarMain.parseJulLevel("SEVERE"));
  }
}
