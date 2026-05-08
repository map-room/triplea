package org.triplea.ai.sidecar.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * TDD tests for SessionStore disk persistence. - save/load round-trip - atomic rename (no .tmp left
 * behind on save) - delete removes file - loadAll loads every manifest in the data dir
 */
class SessionStoreTest {

  @TempDir Path dataDir;

  @Test
  void saveAndLoadRoundTrip() throws IOException {
    final SessionStore store = new SessionStore(dataDir);
    final SessionManifest manifest =
        new SessionManifest("g-1:Germans:r1", "g-1", "Germans", 1, 42L, 1000L, 1000L);
    store.save(manifest);

    final List<SessionManifest> loaded = store.loadAll();
    assertEquals(1, loaded.size());
    final SessionManifest got = loaded.get(0);
    assertEquals("g-1:Germans:r1", got.sessionId());
    assertEquals("g-1", got.gameId());
    assertEquals("Germans", got.nation());
    assertEquals(1, got.round());
    assertEquals(42L, got.seed());
  }

  @Test
  void noTmpFilesLeftAfterSave() throws IOException {
    final SessionStore store = new SessionStore(dataDir);
    store.save(new SessionManifest("g-1:Germans:r1", "g-1", "Germans", 1, 42L, 1000L, 1000L));
    final long tmpCount = Files.walk(dataDir).filter(p -> p.toString().endsWith(".tmp")).count();
    assertEquals(0, tmpCount, "temp files remain after save");
  }

  @Test
  void deleteRemovesFile() throws IOException {
    final SessionStore store = new SessionStore(dataDir);
    store.save(new SessionManifest("g-1:Germans:r1", "g-1", "Germans", 1, 42L, 1000L, 1000L));
    store.delete(new SessionKey("g-1", "Germans", 1));

    final List<SessionManifest> after = store.loadAll();
    assertTrue(after.isEmpty(), "manifest should be deleted");
  }

  @Test
  void loadAllFindsMultipleManifests() throws IOException {
    final SessionStore store = new SessionStore(dataDir);
    store.save(new SessionManifest("g-1:Germans:r1", "g-1", "Germans", 1, 1L, 1000L, 1000L));
    store.save(new SessionManifest("g-1:Russians:r1", "g-1", "Russians", 1, 2L, 1000L, 1000L));
    store.save(new SessionManifest("g-2:Japanese:r1", "g-2", "Japanese", 1, 3L, 1000L, 1000L));

    final List<SessionManifest> loaded = store.loadAll();
    assertEquals(3, loaded.size());
  }

  @Test
  void updateTimestampPreservesOtherFields() throws IOException {
    final SessionStore store = new SessionStore(dataDir);
    final long createdAt = 1000L;
    store.save(
        new SessionManifest("g-1:Germans:r1", "g-1", "Germans", 1, 42L, createdAt, createdAt));
    store.updateTimestamp(new SessionKey("g-1", "Germans", 1), 9999L);

    final SessionManifest updated = store.loadAll().get(0);
    assertEquals(createdAt, updated.createdAt(), "createdAt should not change on update");
    assertEquals(9999L, updated.updatedAt(), "updatedAt should be refreshed");
    assertEquals(1, updated.round(), "round should not change on update");
    assertEquals(42L, updated.seed(), "seed should not change on update");
  }
}
