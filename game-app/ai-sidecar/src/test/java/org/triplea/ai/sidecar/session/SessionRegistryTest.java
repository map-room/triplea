package org.triplea.ai.sidecar.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.strategy.triplea.settings.ClientSetting;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;

class SessionRegistryTest {

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
  }

  @Test
  void createReturnsNewSession() {
    final SessionRegistry r = new SessionRegistry(CanonicalGameData.load());
    final Session s = r.createOrGet(new SessionKey("g-1", "Germans"), 42L);
    assertNotNull(s.sessionId());
    assertEquals("g-1", s.key().gameId());
    assertEquals("Germans", s.key().nation());
    assertEquals(42L, s.seed());
  }

  @Test
  void createIsIdempotentOnGameIdNationSeed() {
    final SessionRegistry r = new SessionRegistry(CanonicalGameData.load());
    final Session first = r.createOrGet(new SessionKey("g-1", "Germans"), 42L);
    final Session second = r.createOrGet(new SessionKey("g-1", "Germans"), 42L);
    assertSame(first, second);
  }

  @Test
  void differentSeedReplacesSession() {
    final SessionRegistry r = new SessionRegistry(CanonicalGameData.load());
    final Session first = r.createOrGet(new SessionKey("g-1", "Germans"), 42L);
    final Session second = r.createOrGet(new SessionKey("g-1", "Germans"), 99L);
    assertEquals(99L, second.seed());
    assertTrue(first != second);
  }

  @Test
  void getBySessionIdReturnsSessionWhenPresent() {
    final SessionRegistry r = new SessionRegistry(CanonicalGameData.load());
    final Session s = r.createOrGet(new SessionKey("g-1", "Germans"), 42L);
    final Optional<Session> found = r.get(s.sessionId());
    assertTrue(found.isPresent());
    assertSame(s, found.get());
  }

  @Test
  void getBySessionIdReturnsEmptyWhenAbsent() {
    final SessionRegistry r = new SessionRegistry(CanonicalGameData.load());
    assertTrue(r.get("unknown").isEmpty());
  }

  @Test
  void deleteRemovesSession() {
    final SessionRegistry r = new SessionRegistry(CanonicalGameData.load());
    final Session s = r.createOrGet(new SessionKey("g-1", "Germans"), 42L);
    assertTrue(r.delete(s.sessionId()));
    assertTrue(r.get(s.sessionId()).isEmpty());
  }

  @Test
  void deleteReturnsFalseForUnknownId() {
    final SessionRegistry r = new SessionRegistry(CanonicalGameData.load());
    assertTrue(!r.delete("unknown"));
  }
}
