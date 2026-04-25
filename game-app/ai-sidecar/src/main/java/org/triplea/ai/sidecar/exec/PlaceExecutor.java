package org.triplea.ai.sidecar.exec;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.attachments.RulesAttachment;
import games.strategy.triplea.delegate.battle.BattleTracker;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.triplea.ai.sidecar.dto.PlaceOrder;
import org.triplea.ai.sidecar.dto.PlacePlan;
import org.triplea.ai.sidecar.dto.PlaceRequest;
import org.triplea.ai.sidecar.session.ProSessionSnapshotStore;
import org.triplea.ai.sidecar.session.Session;
import org.triplea.ai.sidecar.wire.WireStateApplier;

/**
 * Runs {@link games.strategy.triplea.ai.pro.ProAi#invokePlaceForSidecar} on the session's bounded
 * offensive executor, captures every {@link
 * games.strategy.triplea.delegate.remote.IAbstractPlaceDelegate#placeUnits} call via a {@link
 * RecordingPlaceDelegate}, and groups the captures by territory into a {@link PlacePlan}.
 *
 * <h2>Ordering contract</h2>
 *
 * <ol>
 *   <li>Load snapshot; call {@link ProSessionSnapshotStore#restoreUnitIdMap} before
 *       {@link WireStateApplier}.
 *   <li>{@link WireStateApplier#apply}.
 *   <li>{@link games.strategy.triplea.ai.pro.ProAi#restorePurchaseTerritoriesFromSnapshot} —
 *       re-populates {@code storedPurchaseTerritories} when crossing an HTTP boundary.
 *   <li>Guard: {@code storedPurchaseTerritories} must be non-null at dispatch time (it is either
 *       carried in-memory from the purchase phase, or just restored from snapshot).
 *   <li>Submit {@code invokePlaceForSidecar} to the session's single-threaded executor.
 * </ol>
 *
 * <h2>Type-mismatch silent no-op</h2>
 *
 * <p>{@code ProPurchaseAi.place()} resolves each {@code placeUnit} in
 * {@code storedPurchaseTerritories} by scanning {@code player.getUnitCollection()} for a unit with
 * a matching type. If no match is found (e.g., the session's {@link GameData} was deserialized
 * without the purchased units in the player's collection), the inner loop emits an empty list and
 * {@code doPlace()} is called with zero units. This is faithful to the reference algorithm: no
 * exception is thrown, but a WARNING is logged when the total captured unit count is smaller than
 * the count stored in {@code storedPurchaseTerritories}.
 */
public final class PlaceExecutor implements DecisionExecutor<PlaceRequest, PlacePlan> {

  private static final System.Logger LOG = System.getLogger(PlaceExecutor.class.getName());

  private final ProSessionSnapshotStore snapshotStore;

  public PlaceExecutor(final ProSessionSnapshotStore snapshotStore) {
    this.snapshotStore = snapshotStore;
  }

  @Override
  public PlacePlan execute(final Session session, final PlaceRequest request) {
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
          "Unknown player in PlaceRequest: " + request.state().currentPlayer());
    }

    ExecutorSupport.ensureProAiInitialized(session, player);
    ExecutorSupport.ensureBattleDelegate(data);

    final var proAi = session.proAi();

    // Step 3: restore storedPurchaseTerritories from snapshot (no-op if already populated)
    snapOpt.ifPresent(snap -> proAi.restorePurchaseTerritoriesFromSnapshot(snap, data));

    // Guard: storedPurchaseTerritories must be non-null — purchase must have run first
    if (proAi.storedPurchaseTerritoriesIsNull()) {
      throw new IllegalStateException(
          "place called without preceding purchase in this session lifecycle"
              + " — check restore path and dispatch order");
    }

    // Step 4: dispatch on the session's single-threaded offensive executor
    final RecordingPlaceDelegate recorder = new RecordingPlaceDelegate();
    final Future<Void> future =
        session
            .offensiveExecutor()
            .submit(
                () -> {
                  proAi.invokePlaceForSidecar(recorder, data, player);
                  return null;
                });
    try {
      future.get();
    } catch (final InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("PlaceExecutor interrupted", ie);
    } catch (final ExecutionException ee) {
      final Throwable cause = ee.getCause();
      if (cause instanceof RuntimeException re) {
        throw re;
      }
      throw new RuntimeException("PlaceExecutor failed", cause);
    }

    // The map-room engine rejects placement at any territory captured this turn
    // (rules §16/§20: a freshly captured factory cannot produce in the same turn).
    // Pro AI's purchase planner does not consult wasConquered when picking placement
    // targets, and RecordingPlaceDelegate accepts every placeUnits call without
    // validation — so without this filter, freshly conquered territories show up in
    // the plan and the engine rejects the whole place phase, leaving the bot stuck.
    //
    // Exception: players with placementAnyTerritory=true (China) may place in any
    // owned territory including those conquered this turn — do not filter them out.
    final Set<Territory> conqueredThisTurn = collectConqueredThisTurn(data);
    final RulesAttachment ra = player.getRulesAttachment();
    final boolean canPlaceInConquered = ra != null && ra.getPlacementAnyTerritory();

    return groupCapturesIntoPlan(
        recorder.captured(), conqueredThisTurn, session.key().toString(), canPlaceInConquered);
  }

  static Set<Territory> collectConqueredThisTurn(final GameData data) {
    if (data.getBattleDelegate() == null) {
      return Set.of();
    }
    final BattleTracker tracker = data.getBattleDelegate().getBattleTracker();
    return new HashSet<>(tracker.getConquered());
  }

  /**
   * Groups recorded placeUnits captures into a {@link PlacePlan}, dropping any capture whose
   * territory was conquered this turn — unless {@code canPlaceInConquered} is true (e.g. China,
   * which has {@code placementAnyTerritory=true} and may place in any owned territory including
   * those captured this turn). Public-package for direct unit testing.
   */
  static PlacePlan groupCapturesIntoPlan(
      final List<RecordingPlaceDelegate.PlaceCapture> captures,
      final Set<Territory> conqueredThisTurn,
      final String sessionKey,
      final boolean canPlaceInConquered) {
    final Map<String, List<String>> byTerritory = new LinkedHashMap<>();
    int totalCaptured = 0;
    int droppedConquered = 0;
    for (final RecordingPlaceDelegate.PlaceCapture cap : captures) {
      if (!canPlaceInConquered && conqueredThisTurn.contains(cap.territory())) {
        droppedConquered += cap.units().size();
        continue;
      }
      final String tName = cap.territory().getName();
      final List<String> types = byTerritory.computeIfAbsent(tName, k -> new ArrayList<>());
      for (final Unit unit : cap.units()) {
        types.add(unit.getType().getName());
        totalCaptured++;
      }
    }

    if (droppedConquered > 0) {
      LOG.log(
          System.Logger.Level.INFO,
          "PlaceExecutor: dropped " + droppedConquered
              + " unit placements at conquered-this-turn territories for session " + sessionKey);
    }

    if (totalCaptured == 0 && !captures.isEmpty() && droppedConquered == 0) {
      // Captures exist but all had empty unit lists — type-mismatch no-op path
      LOG.log(System.Logger.Level.WARNING,
          "PlaceExecutor: all placeUnits calls returned empty unit lists for session "
              + sessionKey + " — player.getUnitCollection() likely missing purchased units");
    }

    final List<PlaceOrder> placements = new ArrayList<>();
    for (final Map.Entry<String, List<String>> e : byTerritory.entrySet()) {
      if (!e.getValue().isEmpty()) {
        placements.add(new PlaceOrder(e.getKey(), e.getValue()));
      }
    }

    return new PlacePlan(placements);
  }
}
