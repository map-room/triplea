package org.triplea.ai.sidecar.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import games.strategy.engine.data.GameData;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.settings.ClientSetting;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;

class SessionTest {

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
  }

  @Test
  void sessionHoldsIdAndKeyAndSeedAndProAi() {
    final CanonicalGameData canonical = CanonicalGameData.load();
    final GameData data = canonical.cloneForSession();
    final ProAi proAi = new ProAi("session-test", "Germans");
    final Session s =
        new Session(
            "s-1",
            new SessionKey("g-1", "Germans"),
            42L,
            proAi,
            data,
            new ConcurrentHashMap<>());
    assertEquals("s-1", s.sessionId());
    assertEquals("g-1", s.key().gameId());
    assertEquals("Germans", s.key().nation());
    assertEquals(42L, s.seed());
    assertNotNull(s.proAi());
    assertNotNull(s.gameData());
  }
}
