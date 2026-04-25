package org.triplea.ai.sidecar.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

  // Convenience helper: derives deterministic sessionId as gameId:nation
  private static Session create(final SessionRegistry r, final SessionKey key, final long seed) {
    return r.createOrGet(key, key.gameId() + ":" + key.nation(), seed).session();
  }

  @Test
  void createReturnsNewSession() {
    final SessionRegistry r = new SessionRegistry(CanonicalGameData.load());
    final Session s = create(r, new SessionKey("g-1", "Germans"), 42L);
    assertEquals("g-1:Germans", s.sessionId());
    assertEquals("g-1", s.key().gameId());
    assertEquals("Germans", s.key().nation());
    assertEquals(42L, s.seed());
  }

  @Test
  void createIsIdempotentOnSessionId() {
    final SessionRegistry r = new SessionRegistry(CanonicalGameData.load());
    final Session first = create(r, new SessionKey("g-1", "Germans"), 42L);
    final Session second = create(r, new SessionKey("g-1", "Germans"), 42L);
    assertSame(first, second);
  }

  @Test
  void reopenWithSameSessionIdReturnsFalseCreated() {
    final SessionRegistry r = new SessionRegistry(CanonicalGameData.load());
    final SessionKey key = new SessionKey("g-1", "Germans");
    final SessionRegistry.CreateResult first = r.createOrGet(key, "g-1:Germans", 42L);
    final SessionRegistry.CreateResult second = r.createOrGet(key, "g-1:Germans", 42L);
    assertTrue(first.created());
    assertTrue(!second.created());
    assertSame(first.session(), second.session());
  }

  @Test
  void getBySessionIdReturnsSessionWhenPresent() {
    final SessionRegistry r = new SessionRegistry(CanonicalGameData.load());
    final Session s = create(r, new SessionKey("g-1", "Germans"), 42L);
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
    final Session s = create(r, new SessionKey("g-1", "Germans"), 42L);
    assertTrue(r.delete(s.sessionId()));
    assertTrue(r.get(s.sessionId()).isEmpty());
  }

  @Test
  void deleteReturnsFalseForUnknownId() {
    final SessionRegistry r = new SessionRegistry(CanonicalGameData.load());
    assertTrue(!r.delete("unknown"));
  }
}
