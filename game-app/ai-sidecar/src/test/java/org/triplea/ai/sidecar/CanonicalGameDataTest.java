package org.triplea.ai.sidecar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import games.strategy.engine.data.GameData;
import games.strategy.triplea.settings.ClientSetting;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

class CanonicalGameDataTest {

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
  }

  @Test
  void loadsGlobal1940() {
    final CanonicalGameData canonical = CanonicalGameData.load();
    final GameData data = canonical.template();
    assertNotNull(data);
    assertNotNull(data.getPlayerList().getPlayerId("Germans"));
  }

  @Test
  void cloneIsIndependent() {
    final CanonicalGameData canonical = CanonicalGameData.load();
    final GameData a = canonical.cloneForSession();
    final GameData b = canonical.cloneForSession();
    assertNotSame(a, b);
    // PU mutation on A must not leak into B
    a.getPlayerList()
        .getPlayerId("Germans")
        .getResources()
        .addResource(a.getResourceList().getResourceOrThrow("PUs"), 100);
    final int bPus = b.getPlayerList().getPlayerId("Germans").getResources().getQuantity("PUs");
    assertEquals(30, bPus);
  }
}
