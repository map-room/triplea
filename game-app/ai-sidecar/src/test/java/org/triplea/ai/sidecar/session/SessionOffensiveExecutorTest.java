package org.triplea.ai.sidecar.session;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.strategy.triplea.settings.ClientSetting;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;

class SessionOffensiveExecutorTest {

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
  }

  @Test
  void sessionHasSingleThreadOffensiveExecutor() throws Exception {
    final SessionRegistry reg = new SessionRegistry(CanonicalGameData.load());
    final Session s = reg.createOrGet(new SessionKey("g-1", "Germans"), "g-1:Germans", 42L).session();
    assertNotNull(s.offensiveExecutor());
    final Future<String> f =
        s.offensiveExecutor().submit(() -> Thread.currentThread().getName());
    final String threadName = f.get();
    assertTrue(
        threadName.contains("sidecar-offensive-" + s.sessionId()),
        "thread was " + threadName);
    assertTrue(reg.delete(s.sessionId()));
    assertTrue(s.offensiveExecutor().isShutdown());
  }
}
