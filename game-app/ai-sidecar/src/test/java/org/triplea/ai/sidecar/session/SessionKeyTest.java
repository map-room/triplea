package org.triplea.ai.sidecar.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class SessionKeyTest {
  @Test
  void equalsAndHashOnGameIdAndNation() {
    final SessionKey a = new SessionKey("g-1", "Germans");
    final SessionKey b = new SessionKey("g-1", "Germans");
    final SessionKey c = new SessionKey("g-1", "Russians");
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertNotEquals(a, c);
  }
}
