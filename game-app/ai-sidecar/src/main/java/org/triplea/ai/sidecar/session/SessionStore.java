package org.triplea.ai.sidecar.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists {@link SessionManifest} records to {@code <dataDir>/<gameId>/<nation>.json} using atomic
 * rename.
 *
 * <p>Thread safety: callers are responsible for serialising writes to the same {@code (gameId,
 * nation)} key. SessionRegistry's per-instance lock is sufficient — only one thread ever calls
 * {@code save} or {@code delete} for a given key at a time.
 */
public final class SessionStore {

  private static final System.Logger LOG = System.getLogger(SessionStore.class.getName());
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Path dataDir;

  public SessionStore(final Path dataDir) {
    this.dataDir = dataDir;
  }

  /** Writes {@code manifest} to disk atomically. Creates parent directories as needed. */
  public void save(final SessionManifest manifest) {
    final Path target = pathFor(new SessionKey(manifest.gameId(), manifest.nation()));
    final Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
    try {
      Files.createDirectories(target.getParent());
      MAPPER.writeValue(tmp.toFile(), manifest);
      try {
        Files.move(
            tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (final AtomicMoveNotSupportedException ex) {
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (final IOException e) {
      LOG.log(
          System.Logger.Level.WARNING,
          "Failed to save session manifest " + manifest.sessionId(),
          e);
      try {
        Files.deleteIfExists(tmp);
      } catch (final IOException ignored) {
      }
    }
  }

  /**
   * Updates only the {@code updatedAt} timestamp in the stored manifest. No-op if no manifest file
   * exists for the key.
   */
  public void updateTimestamp(final SessionKey key, final long updatedAt) {
    final Path target = pathFor(key);
    if (!Files.exists(target)) {
      return;
    }
    try {
      final SessionManifest existing = MAPPER.readValue(target.toFile(), SessionManifest.class);
      save(existing.withUpdatedAt(updatedAt));
    } catch (final IOException e) {
      LOG.log(System.Logger.Level.WARNING, "Failed to update timestamp for " + key, e);
    }
  }

  /** Deletes the manifest file for {@code key}. No-op if not present. */
  public void delete(final SessionKey key) {
    try {
      Files.deleteIfExists(pathFor(key));
    } catch (final IOException e) {
      LOG.log(System.Logger.Level.WARNING, "Failed to delete session manifest for " + key, e);
    }
  }

  /**
   * Loads all session manifests from the data directory tree. Returns an empty list if the
   * directory does not exist or contains no valid files.
   */
  public List<SessionManifest> loadAll() {
    final List<SessionManifest> result = new ArrayList<>();
    if (!Files.isDirectory(dataDir)) {
      return result;
    }
    try {
      // Only read files at exactly depth 2 (dataDir/{gameId}/{nation}.json).
      // The snapshots/ subdirectory (written by ProSessionSnapshotStore) is at depth 1
      // and its files are a different schema — skip them by constraining the walk depth.
      Files.walk(dataDir, 2)
          .filter(p -> p.getNameCount() == dataDir.getNameCount() + 2)
          .filter(p -> p.toString().endsWith(".json") && !p.toString().endsWith(".tmp"))
          .forEach(
              p -> {
                try {
                  result.add(MAPPER.readValue(p.toFile(), SessionManifest.class));
                } catch (final IOException e) {
                  LOG.log(
                      System.Logger.Level.WARNING, "Skipping unreadable session manifest: " + p, e);
                }
              });
    } catch (final IOException e) {
      LOG.log(System.Logger.Level.WARNING, "Failed to walk session data directory", e);
    }
    return result;
  }

  private Path pathFor(final SessionKey key) {
    // Sanitize to avoid path traversal.
    final String safeGameId = key.gameId().replaceAll("[^A-Za-z0-9\\-]", "_");
    final String safeNation = key.nation().replaceAll("[^A-Za-z0-9]", "_");
    return dataDir.resolve(safeGameId).resolve(safeNation + ".json");
  }
}
