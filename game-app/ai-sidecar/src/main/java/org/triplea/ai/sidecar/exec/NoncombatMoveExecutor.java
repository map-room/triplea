package org.triplea.ai.sidecar.exec;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.triplea.ai.pro.simulate.ProDummyDelegateBridge;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.triplea.ai.sidecar.AiTraceLogger;
import org.triplea.ai.sidecar.dto.CombatMoveOrder;
import org.triplea.ai.sidecar.dto.NoncombatMovePlan;
import org.triplea.ai.sidecar.dto.NoncombatMoveRequest;
import org.triplea.ai.sidecar.session.ProSessionSnapshotStore;
import org.triplea.ai.sidecar.session.Session;
import org.triplea.ai.sidecar.wire.WireStateApplier;

/**
 * Runs {@link games.strategy.triplea.ai.pro.ProAi#invokeNonCombatMoveForSidecar} on the session's
 * bounded offensive executor, captures every {@link games.strategy.engine.data.MoveDescription} via
 * a {@link RecordingMoveDelegate}, and projects each to a wire-shaped {@link CombatMoveOrder}.
 *
 * <p>The noncombat phase never issues strategic-bombing-raid moves, so all captured moves land in
 * {@code moves}; an {@link AssertionError} is thrown if any captured move has {@code isBombing ==
 * true} (belt-and-suspenders invariant).
 *
 * <h2>Ordering contract</h2>
 *
 * <ol>
 *   <li>Load snapshot; call {@link ProSessionSnapshotStore#restoreUnitIdMap} before {@link
 *       WireStateApplier}.
 *   <li>{@link WireStateApplier#apply}.
 *   <li>{@link games.strategy.triplea.ai.pro.ProAi#restoreFactoryMoveMapFromSnapshot} and {@link
 *       games.strategy.triplea.ai.pro.ProAi#restorePurchaseTerritoriesFromSnapshot} — both maps are
 *       needed; {@code storedPurchaseTerritories} is preserved (not cleared) after this phase so
 *       the place executor can consume it.
 *   <li>Submit {@code invokeNonCombatMoveForSidecar} to the session's single-threaded executor.
 * </ol>
 */
public final class NoncombatMoveExecutor
    implements DecisionExecutor<NoncombatMoveRequest, NoncombatMovePlan> {

  private final ProSessionSnapshotStore snapshotStore;

  public NoncombatMoveExecutor(final ProSessionSnapshotStore snapshotStore) {
    this.snapshotStore = snapshotStore;
  }

  @Override
  public NoncombatMovePlan execute(final Session session, final NoncombatMoveRequest request) {
    final GameData data = session.gameData();

    // Step 1: load snapshot once; pre-seed unitIdMap before WireStateApplier runs
    final Optional<games.strategy.triplea.ai.pro.data.ProSessionSnapshot> snapOpt =
        snapshotStore.load(session.key());
    snapOpt.ifPresent(snap -> ProSessionSnapshotStore.restoreUnitIdMap(snap, session.unitIdMap()));

    // Step 2: hydrate GameData from wire state
    WireStateApplier.apply(data, request.state(), session.unitIdMap());

    final GamePlayer player = data.getPlayerList().getPlayerId(request.state().currentPlayer());
    if (player == null) {
      throw new IllegalArgumentException(
          "Unknown player in NoncombatMoveRequest: " + request.state().currentPlayer());
    }

    ExecutorSupport.ensureProAiInitialized(session, player);
    ExecutorSupport.ensureBattleDelegate(data);

    final var proAi = session.proAi();

    // Step 3: restore storedFactoryMoveMap and storedPurchaseTerritories
    snapOpt.ifPresent(
        snap -> {
          proAi.restoreFactoryMoveMapFromSnapshot(snap, data);
          proAi.restorePurchaseTerritoriesFromSnapshot(snap, data);
        });

    if (proAi.storedFactoryMoveMapIsNull()) {
      throw new IllegalStateException(
          "storedFactoryMoveMap is null for session "
              + session.key()
              + " — purchase must run before noncombat-move");
    }

    // Step 4: dispatch on the session's single-threaded offensive executor.
    // reinitializeProDataForSidecar() re-binds proData.getData() to the session GameData
    // before planning — otherwise purchase's simulation dataCopy leaks through and
    // ProNonCombatMoveAi reads stale alreadyMoved values, producing plans that move
    // units the engine already recorded as spent (INVALID_MOVE: "<unit> already moved
    // during combat move"). Same pattern applied to PoliticsExecutor and
    // CombatMoveExecutor.
    final RecordingMoveDelegate recorder = new RecordingMoveDelegate(proAi);
    recorder.initialize("move", "Move");
    recorder.setDelegateBridgeAndPlayer(new ProDummyDelegateBridge(proAi, player, data));
    final Future<Void> future =
        session
            .offensiveExecutor()
            .submit(
                () -> {
                  proAi.reinitializeProDataForSidecar();
                  proAi.invokeNonCombatMoveForSidecar(recorder, data, player);
                  return null;
                });
    try {
      future.get();
    } catch (final InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("NoncombatMoveExecutor interrupted", ie);
    } catch (final ExecutionException ee) {
      final Throwable cause = ee.getCause();
      if (cause instanceof RuntimeException re) {
        throw re;
      }
      throw new RuntimeException("NoncombatMoveExecutor failed", cause);
    }

    // Build reverse map: UUID → wireId
    final Map<UUID, String> uuidToWireId = new HashMap<>();
    session.unitIdMap().forEach((wireId, uuid) -> uuidToWireId.put(uuid, wireId));

    final List<CombatMoveOrder> moves = new ArrayList<>();

    for (final RecordingMoveDelegate.CapturedMove captured : recorder.captured()) {
      // Invariant: noncombat phase must never issue a bombing move
      if (captured.isBombing()) {
        throw new AssertionError(
            "isBombing == true in noncombat-move phase — ProAI invariant violated");
      }
      AiTraceLogger.logCapturedMove(
          player.getName(), "noncombat-move", captured.move(), false, uuidToWireId);
      moves.addAll(ExecutorSupport.projectOrders(captured.move(), uuidToWireId));
    }

    return new NoncombatMovePlan(moves);
  }
}
