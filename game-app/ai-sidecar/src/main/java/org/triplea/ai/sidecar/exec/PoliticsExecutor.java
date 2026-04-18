package org.triplea.ai.sidecar.exec;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.triplea.ai.pro.simulate.ProDummyDelegateBridge;
import games.strategy.triplea.delegate.EndRoundDelegate;
import games.strategy.triplea.delegate.MoveDelegate;
import games.strategy.triplea.delegate.PlaceDelegate;
import games.strategy.triplea.delegate.PoliticsDelegate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.triplea.ai.sidecar.AiTraceLogger;
import org.triplea.ai.sidecar.dto.PoliticsPlan;
import org.triplea.ai.sidecar.dto.PoliticsRequest;
import org.triplea.ai.sidecar.dto.WarDeclaration;
import org.triplea.ai.sidecar.session.ProSessionSnapshotStore;
import org.triplea.ai.sidecar.session.Session;
import org.triplea.ai.sidecar.wire.WireStateApplier;

/**
 * Runs {@link games.strategy.triplea.ai.pro.ProAi#invokePoliticsForSidecar} on the session's
 * bounded offensive executor, captures war declarations via {@link PoliticsObserver}, and returns
 * them as a {@link PoliticsPlan}.
 *
 * <h2>Option X flow (map-room#1824)</h2>
 *
 * <ol>
 *   <li>Bot calls {@code /decide} with {@code kind="politics"} and the current WireState.
 *   <li>This executor hydrates relationships, runs politics, and returns declared wars.
 *   <li>Bot dispatches {@code declareWar} moves to Map Room's engine (cascade runs server-side).
 *   <li>Bot calls {@code /decide} with {@code kind="combat-move"} and a fresh post-war WireState.
 *   <li>{@link CombatMoveExecutor} receives a WireState with post-politics relationships already
 *       applied — no politics step needed there.
 * </ol>
 *
 * <h2>Session setup</h2>
 *
 * <p>Mirrors the setup in {@link CombatMoveExecutor}: snapshot restore, WireState hydration, ProAi
 * initialization, and delegate registration. In addition this executor installs the politics and
 * other required delegates (same as {@link PurchaseExecutor} does) since they may have been cleared
 * by the GameData clone round-trip.
 */
public final class PoliticsExecutor implements DecisionExecutor<PoliticsRequest, PoliticsPlan> {

  private final ProSessionSnapshotStore snapshotStore;

  public PoliticsExecutor(final ProSessionSnapshotStore snapshotStore) {
    this.snapshotStore = snapshotStore;
  }

  @Override
  public PoliticsPlan execute(final Session session, final PoliticsRequest request) {
    final GameData data = session.gameData();

    // Step 1: load snapshot once; pre-seed unitIdMap before WireStateApplier runs
    final Optional<games.strategy.triplea.ai.pro.data.ProSessionSnapshot> snapOpt =
        snapshotStore.load(session.key());
    snapOpt.ifPresent(snap -> ProSessionSnapshotStore.restoreUnitIdMap(snap, session.unitIdMap()));

    // Step 2: hydrate GameData from wire state (includes relationships field)
    WireStateApplier.apply(data, request.state(), session.unitIdMap());

    final GamePlayer player = data.getPlayerList().getPlayerId(request.state().currentPlayer());
    if (player == null) {
      throw new IllegalArgumentException(
          "Unknown player in PoliticsRequest: " + request.state().currentPlayer());
    }

    ExecutorSupport.ensureProAiInitialized(session, player);
    ExecutorSupport.ensureBattleDelegate(data);
    ensureDelegate(data, "move", "Move", new MoveDelegate());
    ensureDelegate(data, "place", "Place", new PlaceDelegate());
    ensureDelegate(data, "politics", "Politics", new PoliticsDelegate());
    ensureDelegate(data, "endRound", "End Round", new EndRoundDelegate());

    final var proAi = session.proAi();

    // Submit politics to the session's single-threaded offensive executor.
    // Re-bind proData to the live session GameData before attaching the observer.
    // The purchase phase leaves proData pointing at its simulation copy (dataCopy)
    // via the last proData.initializeSimulation call. Without re-initializing here,
    // proData.getData() inside politicsAi.politicalActions() returns dataCopy instead
    // of session.gameData(), causing politicsDelegate lookup to find dataCopy's delegate map
    // (which has no ObservingPoliticsDelegate installed). The result is that the politics
    // delegate on dataCopy has attemptsLeftThisTurn=0 (already consumed in the purchase
    // simulation), filtering out all valid war actions.
    final AtomicReference<List<WarDeclaration>> declarationsRef = new AtomicReference<>(List.of());
    final Future<Void> future =
        session
            .offensiveExecutor()
            .submit(
                () -> {
                  proAi.reinitializeProDataForSidecar();

                  final PoliticsObserver politicsObserver = PoliticsObserver.attach(data);
                  final ProDummyDelegateBridge politicsBridge =
                      new ProDummyDelegateBridge(proAi, player, data);
                  data.getPoliticsDelegate().setDelegateBridgeAndPlayer(politicsBridge);
                  try {
                    proAi.invokePoliticsForSidecar();
                  } finally {
                    // Snapshot declarations even if politicalActions() throws partially.
                    declarationsRef.set(politicsObserver.toWarDeclarations(player));
                    politicsObserver.detach();
                  }
                  return null;
                });
    try {
      future.get();
    } catch (final InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("PoliticsExecutor interrupted", ie);
    } catch (final ExecutionException ee) {
      final Throwable cause = ee.getCause();
      if (cause instanceof RuntimeException re) {
        throw re;
      }
      throw new RuntimeException("PoliticsExecutor failed", cause);
    }

    for (final WarDeclaration decl : declarationsRef.get()) {
      AiTraceLogger.logWarDeclaration(player.getName(), decl.target());
    }
    return new PoliticsPlan(declarationsRef.get());
  }

  private static void ensureDelegate(
      final GameData data,
      final String name,
      final String displayName,
      final games.strategy.engine.delegate.IDelegate delegate) {
    if (data.getDelegateOptional(name).isPresent()) {
      return;
    }
    delegate.initialize(name, displayName);
    data.addDelegate(delegate);
  }
}
