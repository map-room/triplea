package org.triplea.ai.sidecar.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class SessionKeyTest {
  @Test
  void equalsAndHashOnGameIdNationAndRound() {
    final SessionKey a = new SessionKey("g-1", "Germans", 1);
    final SessionKey b = new SessionKey("g-1", "Germans", 1);
    final SessionKey c = new SessionKey("g-1", "Russians", 1);
    final SessionKey d = new SessionKey("g-1", "Germans", 2);
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertNotEquals(a, c);
    assertNotEquals(a, d, "different rounds must produce different keys");
  }
}
