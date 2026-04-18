package org.triplea.ai.sidecar.exec;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Unit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.triplea.ai.sidecar.dto.CombatMoveOrder;
import org.triplea.ai.sidecar.dto.CombatMovePlan;
import org.triplea.ai.sidecar.dto.CombatMoveRequest;
import org.triplea.ai.sidecar.session.ProSessionSnapshotStore;
import org.triplea.ai.sidecar.session.Session;
import org.triplea.ai.sidecar.wire.WireStateApplier;

/**
 * Runs {@link games.strategy.triplea.ai.pro.ProAi#invokeCombatMoveForSidecar} on the session's
 * bounded offensive executor, captures every {@link games.strategy.engine.data.MoveDescription}
 * via a {@link RecordingMoveDelegate}, partitions captured moves into standard moves and
 * strategic-bombing-raid moves, and projects each to a wire-shaped {@link CombatMoveOrder}.
 *
 * <h2>Ordering contract</h2>
 *
 * <p>Before dispatching to the ProAi this executor enforces the contract established in #1763:
 * <ol>
 *   <li>Load the session snapshot (if present) and call
 *       {@link ProSessionSnapshotStore#restoreUnitIdMap} — pre-seeds the live {@code unitIdMap}
 *       so that {@code computeIfAbsent} inside {@link WireStateApplier} assigns the same UUIDs
 *       that the purchase snapshot used, not fresh random ones.
 *   <li>{@link WireStateApplier#apply} — hydrates live {@link GameData} from wire state.
 *   <li>{@link games.strategy.triplea.ai.pro.ProAi#restoreCombatMoveMapFromSnapshot} — restores
 *       {@code storedCombatMoveMap} from the snapshot. No-op if it was already populated in this
 *       JVM session (purchase ran in the same process).
 *   <li>Submit {@code invokeCombatMoveForSidecar} to the session's single-threaded executor.
 * </ol>
 */
public final class CombatMoveExecutor implements DecisionExecutor<CombatMoveRequest, CombatMovePlan> {

  private final ProSessionSnapshotStore snapshotStore;

  public CombatMoveExecutor(final ProSessionSnapshotStore snapshotStore) {
    this.snapshotStore = snapshotStore;
  }

  @Override
  public CombatMovePlan execute(final Session session, final CombatMoveRequest request) {
    final GameData data = session.gameData();

    // Step 1: load snapshot once; pre-seed unitIdMap before WireStateApplier runs
    final Optional<games.strategy.triplea.ai.pro.data.ProSessionSnapshot> snapOpt =
        snapshotStore.load(session.key());
    snapOpt.ifPresent(snap -> ProSessionSnapshotStore.restoreUnitIdMap(snap, session.unitIdMap()));

    // Step 2: hydrate GameData from wire state
    WireStateApplier.apply(data, request.state(), session.unitIdMap());

    final GamePlayer player =
        data.getPlayerList().getPlayerId(request.state().currentPlayer());
    if (player == null) {
      throw new IllegalArgumentException(
          "Unknown player in CombatMoveRequest: " + request.state().currentPlayer());
    }

    ExecutorSupport.ensureProAiInitialized(session, player);
    ExecutorSupport.ensureBattleDelegate(data);

    final var proAi = session.proAi();

    // Step 3: restore storedCombatMoveMap (no-op if already populated this JVM session)
    snapOpt.ifPresent(snap -> proAi.restoreCombatMoveMapFromSnapshot(snap, data));

    if (proAi.storedCombatMoveMapIsNull()) {
      throw new IllegalStateException(
          "storedCombatMoveMap is null for session " + session.key()
              + " — purchase must run before combat-move");
    }

    // Step 4: dispatch on the session's single-threaded offensive executor
    final RecordingMoveDelegate recorder = new RecordingMoveDelegate(proAi);
    final Future<Void> future =
        session
            .offensiveExecutor()
            .submit(
                () -> {
                  proAi.invokeCombatMoveForSidecar(recorder, data, player);
                  return null;
                });
    try {
      future.get();
    } catch (final InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("CombatMoveExecutor interrupted", ie);
    } catch (final ExecutionException ee) {
      final Throwable cause = ee.getCause();
      if (cause instanceof RuntimeException re) {
        throw re;
      }
      throw new RuntimeException("CombatMoveExecutor failed", cause);
    }

    // Build reverse map: UUID → wireId (needed to project Unit references back to wire IDs)
    final Map<UUID, String> uuidToWireId = new HashMap<>();
    session.unitIdMap().forEach((wireId, uuid) -> uuidToWireId.put(uuid, wireId));

    final List<CombatMoveOrder> moves = new ArrayList<>();
    final List<CombatMoveOrder> sbrMoves = new ArrayList<>();

    for (final RecordingMoveDelegate.CapturedMove captured : recorder.captured()) {
      final List<String> unitIds = new ArrayList<>();
      for (final Unit unit : captured.move().getUnits()) {
        final String wireId = uuidToWireId.get(unit.getId());
        if (wireId != null) {
          unitIds.add(wireId);
        }
      }
      final String from = captured.move().getRoute().getStart().getName();
      final String to = captured.move().getRoute().getEnd().getName();
      final CombatMoveOrder order = new CombatMoveOrder(unitIds, from, to);
      if (captured.isBombing()) {
        sbrMoves.add(order);
      } else {
        moves.add(order);
      }
    }

    return new CombatMovePlan(List.of(), moves, sbrMoves);
  }
}
