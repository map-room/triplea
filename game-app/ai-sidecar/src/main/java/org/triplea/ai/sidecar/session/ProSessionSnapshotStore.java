package org.triplea.ai.sidecar.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import games.strategy.triplea.ai.pro.data.ProSessionSnapshot;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Persists a {@link ProSessionSnapshot} to a per-session file so that ProAi stored maps survive
 * across HTTP request boundaries (purchase → combat-move → noncombat-move → place).
 *
 * <p><b>File layout:</b> one file per {@code (gameId, nation)} pair, named
 * {@code <gameId>_<nation>.json} inside a configurable directory. The file is atomically replaced
 * on each save via a tmp-file + rename, so a mid-write crash leaves the previous snapshot intact.
 * The file is deleted when the session is evicted from {@link SessionRegistry}.
 *
 * <p><b>Thread safety:</b> each {@code (gameId, nation)} pair is written only from the
 * session's single-threaded {@code offensiveExecutor}, so concurrent writes to the same file
 * cannot happen in normal operation. Reads happen on the HTTP request thread after
 * {@code WireStateApplier} has run; they are safe because the write has already completed by the
 * time the next request arrives. No additional synchronization is needed.
 */
public final class ProSessionSnapshotStore {

  private static final System.Logger LOG =
      System.getLogger(ProSessionSnapshotStore.class.getName());
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Path dir;

  public ProSessionSnapshotStore(final Path dir) {
    this.dir = dir;
  }

  /**
   * Serializes {@code snapshot} to a tmp file then atomically renames it to the stable snapshot
   * path for {@code key}. If the directory does not exist it is created on first use.
   */
  public void save(final SessionKey key, final ProSessionSnapshot snapshot) {
    final Path target = pathFor(key);
    final Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
    try {
      Files.createDirectories(dir);
      MAPPER.writeValue(tmp.toFile(), snapshot);
      try {
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (final AtomicMoveNotSupportedException ex) {
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (final IOException e) {
      LOG.log(System.Logger.Level.WARNING, "Failed to save snapshot for " + key, e);
      try {
        Files.deleteIfExists(tmp);
      } catch (final IOException ignored) {
        // best-effort cleanup
      }
    }
  }

  /**
   * Returns the stored snapshot for {@code key}, or {@link Optional#empty()} if no snapshot file
   * exists or it cannot be parsed.
   */
  public Optional<ProSessionSnapshot> load(final SessionKey key) {
    final Path p = pathFor(key);
    if (!Files.exists(p)) {
      return Optional.empty();
    }
    try {
      return Optional.of(MAPPER.readValue(p.toFile(), ProSessionSnapshot.class));
    } catch (final IOException e) {
      LOG.log(System.Logger.Level.WARNING, "Failed to load snapshot for " + key, e);
      return Optional.empty();
    }
  }

  /**
   * Pre-populates {@code liveUnitIdMap} with the wire-ID → UUID entries stored in
   * {@code snapshot.unitIdMap()}, using {@link java.util.concurrent.ConcurrentMap#putIfAbsent} to
   * avoid overwriting any mappings that were already assigned in the current JVM session.
   *
   * <p>This must be called BEFORE {@code WireStateApplier.apply()} in each offensive executor so
   * that {@code computeIfAbsent} inside the applier finds the pre-seeded UUIDs and creates
   * {@code Unit} objects with the same identity as the snapshot's UUID references.
   */
  public static void restoreUnitIdMap(
      final games.strategy.triplea.ai.pro.data.ProSessionSnapshot snapshot,
      final java.util.concurrent.ConcurrentMap<String, java.util.UUID> liveUnitIdMap) {
    for (final java.util.Map.Entry<String, String> e : snapshot.unitIdMap().entrySet()) {
      liveUnitIdMap.putIfAbsent(e.getKey(), java.util.UUID.fromString(e.getValue()));
    }
  }

  /** Deletes the snapshot file for {@code key}. No-op if the file does not exist. */
  public void delete(final SessionKey key) {
    try {
      Files.deleteIfExists(pathFor(key));
    } catch (final IOException e) {
      LOG.log(System.Logger.Level.WARNING, "Failed to delete snapshot for " + key, e);
    }
  }

  private Path pathFor(final SessionKey key) {
    // Sanitize to avoid path traversal: replace any non-alphanumeric chars with '_'
    final String safeGameId = key.gameId().replaceAll("[^A-Za-z0-9\\-]", "_");
    final String safeNation = key.nation().replaceAll("[^A-Za-z0-9\\-]", "_");
    return dir.resolve(safeGameId + "_" + safeNation + ".json");
  }
}
