package org.triplea.ai.sidecar.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.strategy.triplea.ai.pro.data.ProSessionSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProSessionSnapshotStoreTest {

  @TempDir Path dir;

  private static ProSessionSnapshot emptySnapshot() {
    return new ProSessionSnapshot(Map.of(), Map.of(), Map.of());
  }

  @Test
  void saveAndLoadRoundTrip() {
    final ProSessionSnapshotStore store = new ProSessionSnapshotStore(dir);
    final SessionKey key = new SessionKey("g-1", "Germans");
    store.save(key, emptySnapshot());

    final Optional<ProSessionSnapshot> loaded = store.load(key);
    assertTrue(loaded.isPresent());
    assertTrue(loaded.get().combatMoveMap().isEmpty());
    assertTrue(loaded.get().factoryMoveMap().isEmpty());
    assertTrue(loaded.get().purchaseTerritories().isEmpty());
  }

  @Test
  void loadReturnsEmptyWhenNoFile() {
    final ProSessionSnapshotStore store = new ProSessionSnapshotStore(dir);
    assertTrue(store.load(new SessionKey("g-1", "Germans")).isEmpty());
  }

  @Test
  void deleteRemovesFile() throws Exception {
    final ProSessionSnapshotStore store = new ProSessionSnapshotStore(dir);
    final SessionKey key = new SessionKey("g-1", "Germans");
    store.save(key, emptySnapshot());

    // File exists after save
    final Path[] files = Files.list(dir).toArray(Path[]::new);
    assertTrue(files.length > 0, "snapshot file should exist after save");

    store.delete(key);
    assertTrue(store.load(key).isEmpty(), "load should return empty after delete");
    // No file left for this key
    assertFalse(Files.exists(dir.resolve("g-1_Germans.json")));
  }

  @Test
  void deleteIsNoOpWhenFileAbsent() {
    final ProSessionSnapshotStore store = new ProSessionSnapshotStore(dir);
    // Should not throw
    store.delete(new SessionKey("no-such-game", "Americans"));
  }

  @Test
  void sessionRegistryDeleteCallsSnapshotDelete() throws Exception {
    // Verify that SessionRegistry.delete() propagates to the snapshot store.
    final ProSessionSnapshotStore store = new ProSessionSnapshotStore(dir);

    games.strategy.triplea.settings.ClientSetting.setPreferences(
        new org.sonatype.goodies.prefs.memory.MemoryPreferences());
    final SessionRegistry registry =
        new SessionRegistry(org.triplea.ai.sidecar.CanonicalGameData.load(), store);

    final Session session = registry.createOrGet(new SessionKey("g-1", "Germans"), 42L);
    store.save(session.key(), emptySnapshot());
    assertTrue(store.load(session.key()).isPresent(), "snapshot should exist before delete");

    registry.delete(session.sessionId());
    assertTrue(store.load(session.key()).isEmpty(), "snapshot should be gone after registry.delete");
  }
}
