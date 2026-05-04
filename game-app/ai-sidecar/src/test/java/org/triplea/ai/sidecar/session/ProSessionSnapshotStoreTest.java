package org.triplea.ai.sidecar.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.strategy.triplea.ai.pro.data.ProSessionSnapshot;
import games.strategy.triplea.settings.ClientSetting;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

class ProSessionSnapshotStoreTest {

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
  }

  @TempDir Path dir;

  private static ProSessionSnapshot emptySnapshot() {
    return new ProSessionSnapshot(Map.of(), Map.of(), Map.of(), Map.of());
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
  void restoreUnitIdMapPrePopulatesLiveMap() {
    final UUID fixedUuid = UUID.fromString("11111111-2222-3333-4444-555555555555");
    final ProSessionSnapshot snap =
        new ProSessionSnapshot(
            Map.of(), Map.of(), Map.of(), Map.of("unit-abc", fixedUuid.toString()));

    final ConcurrentHashMap<String, UUID> live = new ConcurrentHashMap<>();
    ProSessionSnapshotStore.restoreUnitIdMap(snap, live);

    assertEquals(fixedUuid, live.get("unit-abc"), "pre-populated UUID should match snapshot");
  }

  @Test
  void restoreUnitIdMapDoesNotOverwriteExistingEntry() {
    final UUID existingUuid = UUID.randomUUID();
    final UUID snapshotUuid = UUID.fromString("11111111-2222-3333-4444-555555555555");
    final ProSessionSnapshot snap =
        new ProSessionSnapshot(
            Map.of(), Map.of(), Map.of(), Map.of("unit-abc", snapshotUuid.toString()));

    final ConcurrentHashMap<String, UUID> live = new ConcurrentHashMap<>();
    live.put("unit-abc", existingUuid); // already populated in current session

    ProSessionSnapshotStore.restoreUnitIdMap(snap, live);

    assertEquals(
        existingUuid, live.get("unit-abc"), "putIfAbsent must not overwrite existing mapping");
  }

  @Test
  void deleteIsNoOpWhenFileAbsent() {
    final ProSessionSnapshotStore store = new ProSessionSnapshotStore(dir);
    // Should not throw
    store.delete(new SessionKey("no-such-game", "Americans"));
  }

  /**
   * Regression test for map-room#2191: snapshot must survive a registry recreation so that a second
   * noncombat-move request after a sidecar restart can find the purchase-phase snapshot.
   *
   * <p>Before the fix, the production {@link SessionRegistry} constructor stored snapshots in
   * {@code java.io.tmpdir} (non-persistent across Docker restarts) while manifests went to a
   * persistent data dir. After the fix, both live under {@code dataDir} and survive registry
   * recreation.
   */
  @Test
  void snapshotSurvivesSessionRegistryRecreation() throws Exception {
    final SessionKey key = new SessionKey("g-restart", "British");
    final String sessionId = "g-restart:British";

    // First registry instance — simulates sidecar before restart
    final SessionRegistry reg1 =
        new SessionRegistry(org.triplea.ai.sidecar.CanonicalGameData.load(), dir);
    reg1.createOrGet(key, sessionId, 42L);
    final ProSessionSnapshot snapshot = emptySnapshot();
    reg1.snapshotStore().save(key, snapshot);
    assertTrue(reg1.snapshotStore().load(key).isPresent(), "snapshot must exist after save");

    // Second registry instance from the same dir — simulates sidecar restart + rehydrate
    final SessionRegistry reg2 =
        new SessionRegistry(org.triplea.ai.sidecar.CanonicalGameData.load(), dir);
    reg2.rehydrate();

    // The snapshot must still be loadable — this is the invariant that prevents
    // "storedFactoryMoveMap is null" after a sidecar container restart.
    assertTrue(
        reg2.snapshotStore().load(key).isPresent(),
        "snapshot must survive SessionRegistry recreation (regression: map-room#2191)");
  }

  @Test
  void sessionRegistryDeleteCallsSnapshotDelete() throws Exception {
    // Verify that SessionRegistry.delete() propagates to the snapshot store.
    final ProSessionSnapshotStore store = new ProSessionSnapshotStore(dir);

    games.strategy.triplea.settings.ClientSetting.setPreferences(
        new org.sonatype.goodies.prefs.memory.MemoryPreferences());
    final SessionRegistry registry =
        new SessionRegistry(org.triplea.ai.sidecar.CanonicalGameData.load(), store);

    final Session session =
        registry.createOrGet(new SessionKey("g-1", "Germans"), "g-1:Germans", 42L).session();
    store.save(session.key(), emptySnapshot());
    assertTrue(store.load(session.key()).isPresent(), "snapshot should exist before delete");

    registry.delete(session.sessionId());
    assertTrue(
        store.load(session.key()).isEmpty(), "snapshot should be gone after registry.delete");
  }
}
