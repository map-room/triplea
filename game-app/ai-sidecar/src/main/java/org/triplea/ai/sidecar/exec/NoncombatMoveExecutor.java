package org.triplea.ai.sidecar.exec;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.triplea.ai.pro.simulate.ProDummyDelegateBridge;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.triplea.ai.sidecar.AiTraceLogger;
import org.triplea.ai.sidecar.dto.NoncombatMovePlan;
import org.triplea.ai.sidecar.dto.NoncombatMoveRequest;
import org.triplea.ai.sidecar.dto.WireMoveDescription;
import org.triplea.ai.sidecar.session.Session;
import org.triplea.ai.sidecar.wire.WireStateApplier;

/**
 * Stateless noncombat-move executor: runs {@link
 * games.strategy.triplea.ai.pro.ProAi#invokeNonCombatMoveForSidecar} on the session's bounded
 * offensive executor and projects each captured {@link games.strategy.engine.data.MoveDescription}
 * to a {@link org.triplea.ai.sidecar.dto.WireMoveDescription}.
 *
 * <p>The noncombat phase never issues strategic-bombing-raid moves, so all captured moves land in
 * {@code moves}; an {@link AssertionError} is thrown if any captured move has {@code isBombing ==
 * true} (belt-and-suspenders invariant).
 *
 * <h2>Statelessness contract (map-room#2385)</h2>
 *
 * <p>NCM does not consult any cross-call state on the {@code Session} — no {@link
 * org.triplea.ai.sidecar.session.ProSessionSnapshotStore snapshot store} read, no {@code
 * storedFactoryMoveMap} / {@code storedPurchaseTerritories} restore from a prior purchase call.
 * Before dispatch the executor calls {@code proAi.clearStoredMovePlans()} so any in-memory planning
 * maps left over from a prior decision on the same session are wiped, then dispatches {@code
 * invokeNonCombatMoveForSidecar}. {@code ProNonCombatMoveAi.doNonCombatMove(null, null, …)}
 * rebuilds the factoryMoveMap internally and falls back to {@code
 * ProPurchaseUtils.findMaxPurchaseDefenders} for the cantMove-unit estimate per factory territory
 * (the same path {@code simulateNonCombatMove} takes during purchase planning).
 *
 * <p>The result is that NCM is a pure function of (wire payload, seed) — input to the {@code
 * Session}-elimination work in #2386.
 *
 * <h2>Execution order</h2>
 *
 * <ol>
 *   <li>{@link WireStateApplier#apply}.
 *   <li>Reseed proData RNG and battle calculator from the per-call wire seed.
 *   <li>{@code proAi.clearStoredMovePlans()}.
 *   <li>Submit {@code invokeNonCombatMoveForSidecar} to the session's single-threaded offensive
 *       executor.
 * </ol>
 */
public final class NoncombatMoveExecutor
    implements DecisionExecutor<NoncombatMoveRequest, NoncombatMovePlan> {

  public NoncombatMoveExecutor() {}

  @Override
  public NoncombatMovePlan execute(final Session session, final NoncombatMoveRequest request) {
    final GameData data = session.gameData();

    // Step 1: hydrate GameData from wire state. unitIdMap is populated lazily by
    // WireStateApplier; cross-call persistence of the wire-id → UUID mapping is no longer
    // required because every NCM dispatch is self-contained — fresh UUIDs against fresh wire
    // IDs are equivalent (units are addressed by wire id, not UUID, in the response).
    WireStateApplier.apply(data, request.state(), session.unitIdMap());

    final GamePlayer player = data.getPlayerList().getPlayerId(request.state().currentPlayer());
    if (player == null) {
      throw new IllegalArgumentException(
          "Unknown player in NoncombatMoveRequest: " + request.state().currentPlayer());
    }

    ExecutorSupport.ensureProAiInitialized(session, player);
    ExecutorSupport.ensureBattleDelegate(data);
    // findMaxPurchaseDefenders (the null-storedPurchaseTerritories fallback inside
    // ProNonCombatMoveAi.findUnitsThatCantMove) calls data.getDelegate("place") — without this
    // ensure, fresh-session NCM (no prior purchase to register the delegate) throws
    // "place delegate not found".
    ExecutorSupport.ensurePlaceDelegate(data);

    final var proAi = session.proAi();

    // Reseed proData.getRng() and the battle calculator from the per-call wire seed so the
    // (gamestate, seed) → wire-response mapping is a pure function — independent of any
    // RNG drift from prior decision calls on this session. Mirrors the seeding pattern
    // established in SessionRegistry.buildSession (#2377) but at per-call granularity, which
    // is what the stateless-sidecar campaign needs (#2384, #2376 audit gate).
    proAi.getProData().setSeed(request.seed());
    proAi.seedBattleCalc(request.seed());

    // Step 2: enforce statelessness — wipe any storedFactoryMoveMap / storedPurchaseTerritories /
    // storedCombatMoveMap / storedPoliticalActions left over from a prior call on this session.
    // ProNonCombatMoveAi will rebuild factoryMoveMap internally (buildFactoryMoveMap) and
    // estimate cantMove units via findMaxPurchaseDefenders (the simulateNonCombatMove path).
    proAi.clearStoredMovePlans();

    // Step 3: dispatch on the session's single-threaded offensive executor.
    // reinitializeProDataForSidecar() re-binds proData.getData() to the session GameData
    // before planning — otherwise purchase's simulation dataCopy leaks through and
    // ProNonCombatMoveAi reads stale alreadyMoved values, producing plans that move
    // units the engine already recorded as spent (INVALID_MOVE: "<unit> already moved
    // during combat move"). Same pattern as PurchaseExecutor.
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

    final List<WireMoveDescription> moves =
        recorder.captured().stream()
            .peek(
                c -> {
                  // Invariant: noncombat phase must never issue a bombing move
                  if (c.isBombing()) {
                    throw new AssertionError(
                        "isBombing == true in noncombat-move phase — ProAI invariant violated");
                  }
                  AiTraceLogger.logCapturedMove(
                      player.getName(), "noncombat-move", c.move(), false, uuidToWireId);
                })
            .map(c -> WireMoveDescriptionBuilder.build(c.move(), uuidToWireId))
            .toList();

    return new NoncombatMovePlan(moves);
  }
}
