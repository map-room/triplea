package org.triplea.ai.sidecar.exec;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.ai.pro.simulate.ProDummyDelegateBridge;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.triplea.ai.sidecar.AiTraceLogger;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.dto.NoncombatMovePlan;
import org.triplea.ai.sidecar.dto.NoncombatMoveRequest;
import org.triplea.ai.sidecar.dto.WireMoveDescription;
import org.triplea.ai.sidecar.wire.WireStateApplier;

/**
 * Stateless noncombat-move executor: builds a fresh {@link GameData} clone + {@link ProAi} per
 * call, applies the wire state, runs {@link ProAi#invokeNonCombatMoveForSidecar}, and projects each
 * captured {@link games.strategy.engine.data.MoveDescription} to a {@link
 * org.triplea.ai.sidecar.dto.WireMoveDescription}.
 *
 * <p>The noncombat phase never issues strategic-bombing-raid moves, so all captured moves land in
 * {@code moves}; an {@link AssertionError} is thrown if any captured move has {@code isBombing ==
 * true} (belt-and-suspenders invariant).
 *
 * <h2>Statelessness contract (map-room#2385, #2386)</h2>
 *
 * <p>NCM does not consult any cross-call state — the executor builds a fresh ProAi and GameData
 * clone on every invocation, so there are no stored maps left over from a prior call to clear.
 * {@code ProNonCombatMoveAi.doNonCombatMove(null, null, …)} rebuilds the factoryMoveMap internally
 * and falls back to {@code ProPurchaseUtils.findMaxPurchaseDefenders} for the cantMove-unit
 * estimate per factory territory (the same path {@code simulateNonCombatMove} takes during purchase
 * planning).
 *
 * <p>The result is that NCM is a pure function of (wire payload, seed) — input to the {@code
 * Session}-elimination work in #2386.
 *
 * <h2>Execution order</h2>
 *
 * <ol>
 *   <li>Clone {@link GameData} from {@link CanonicalGameData}; build fresh {@link ProAi}.
 *   <li>{@link WireStateApplier#apply}.
 *   <li>Reseed proData RNG and battle calculator from the per-call wire seed.
 *   <li>Dispatch {@code invokeNonCombatMoveForSidecar} inline.
 * </ol>
 */
public final class NoncombatMoveExecutor
    implements DecisionExecutor<NoncombatMoveRequest, NoncombatMovePlan> {

  public NoncombatMoveExecutor() {}

  @Override
  public NoncombatMovePlan execute(
      final CanonicalGameData canonical, final NoncombatMoveRequest request) {
    return executeOn(canonical.cloneForSession(), request);
  }

  /**
   * Test/internal entry point: runs NCM planning on a caller-supplied {@link GameData}. See {@code
   * PurchaseExecutor#executeOn} for the rationale.
   */
  NoncombatMovePlan executeOn(final GameData data, final NoncombatMoveRequest request) {
    final ConcurrentMap<String, UUID> unitIdMap = new ConcurrentHashMap<>();
    WireStateApplier.apply(data, request.state(), unitIdMap);

    final GamePlayer player = data.getPlayerList().getPlayerId(request.state().currentPlayer());
    if (player == null) {
      throw new IllegalArgumentException(
          "Unknown player in NoncombatMoveRequest: " + request.state().currentPlayer());
    }

    final ProAi proAi =
        new ProAi("sidecar-stateless-" + request.state().currentPlayer(), player.getName());
    ExecutorSupport.initializeProAi(proAi, data, player);
    ExecutorSupport.ensureBattleDelegate(data);
    // findMaxPurchaseDefenders (the null-storedPurchaseTerritories fallback inside
    // ProNonCombatMoveAi.findUnitsThatCantMove) calls data.getDelegate("place") — without this
    // ensure, fresh-call NCM (no prior purchase to register the delegate) throws
    // "place delegate not found".
    ExecutorSupport.ensurePlaceDelegate(data);

    // Reseed proData.getRng() and the battle calculator from the per-call wire seed so the
    // (gamestate, seed) → wire-response mapping is a pure function (#2384, #2376 audit gate).
    proAi.getProData().setSeed(request.seed());
    proAi.seedBattleCalc(request.seed());

    // reinitializeProDataForSidecar() re-binds proData.getData() to the fresh GameData before
    // planning — same defensive pattern as PurchaseExecutor.
    final RecordingMoveDelegate recorder = new RecordingMoveDelegate(proAi);
    recorder.initialize("move", "Move");
    recorder.setDelegateBridgeAndPlayer(new ProDummyDelegateBridge(proAi, player, data));
    proAi.reinitializeProDataForSidecar();
    proAi.invokeNonCombatMoveForSidecar(recorder, data, player);

    // Build reverse map: UUID → wireId
    final Map<UUID, String> uuidToWireId = new HashMap<>();
    unitIdMap.forEach((wireId, uuid) -> uuidToWireId.put(uuid, wireId));

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
