package org.triplea.ai.sidecar.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import games.strategy.triplea.settings.ClientSetting;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;

/**
 * TDD tests for SessionReaper. - Sessions updated within 30 days are NOT reaped. - Sessions with
 * updatedAt older than 30 days ARE reaped.
 */
class SessionReaperTest {

  @TempDir Path dataDir;

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
  }

  @Test
  void doesNotReapRecentSession() throws Exception {
    final long now = System.currentTimeMillis();
    final SessionRegistry registry = new SessionRegistry(CanonicalGameData.load(), dataDir);
    // Create a session — updatedAt = now
    registry.createOrGet(new SessionKey("g-1", "Germans", 1), "g-1:Germans:r1", 42L);

    final Clock clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneId.systemDefault());
    final SessionReaper reaper = new SessionReaper(registry, clock);
    reaper.runOnce();

    assertEquals(1, registry.sessionCount(), "recent session should not be reaped");
  }

  @Test
  void reapsStaleSession() throws Exception {
    final long now = System.currentTimeMillis();
    // Set updatedAt to 31 days ago
    final long stalePast = now - TimeUnit.DAYS.toMillis(31);
    final SessionRegistry registry = new SessionRegistry(CanonicalGameData.load(), dataDir);
    registry.createOrGet(new SessionKey("g-1", "Germans", 1), "g-1:Germans:r1", 42L);
    // Override updatedAt to simulate staleness
    registry.setUpdatedAtForTesting(new SessionKey("g-1", "Germans", 1), stalePast);

    final Clock clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneId.systemDefault());
    final SessionReaper reaper = new SessionReaper(registry, clock);
    reaper.runOnce();

    assertEquals(0, registry.sessionCount(), "stale session should be reaped");
  }
}
