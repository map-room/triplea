package org.triplea.ai.sidecar.exec;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.NamedAttachable;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.RepairRule;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.Constants;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.ai.pro.simulate.ProDummyDelegateBridge;
import games.strategy.engine.delegate.IDelegate;
import games.strategy.triplea.delegate.EndRoundDelegate;
import games.strategy.triplea.delegate.MoveDelegate;
import games.strategy.triplea.delegate.PlaceDelegate;
import games.strategy.triplea.delegate.PoliticsDelegate;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.triplea.ai.sidecar.dto.PurchaseOrder;
import org.triplea.ai.sidecar.dto.PurchasePlan;
import org.triplea.ai.sidecar.dto.PurchaseRequest;
import org.triplea.ai.sidecar.dto.RepairOrder;
import org.triplea.ai.sidecar.session.ProSessionSnapshotStore;
import org.triplea.ai.sidecar.session.Session;
import org.triplea.ai.sidecar.wire.WireStateApplier;
import org.triplea.java.collections.IntegerMap;

/**
 * Runs {@link ProAi#invokePurchaseForSidecar} on the session's bounded offensive executor,
 * captures the resulting {@code IntegerMap<ProductionRule>} via a {@link
 * RecordingPurchaseDelegate}, trims the captured map to fit the session player's PU budget,
 * and projects the result into a wire-shaped {@link PurchasePlan}.
 *
 * <h2>Why trim-to-fit is a necessary backstop (not a reimplementation)</h2>
 *
 * <p>TripleA's {@code ProPurchaseAi} consults a {@code ProResourceTracker} inside the
 * per-territory purchase loop, but the final {@code IntegerMap<ProductionRule>} that is handed
 * to {@code purchaseDelegate.purchase(...)} is produced by
 * {@code ProPurchaseAi.populateProductionRuleMap} (see
 * {@code game-core/src/main/java/games/strategy/triplea/ai/pro/ProPurchaseAi.java:2404-2429}):
 * that method iterates {@code ProPurchaseOption}s and counts
 * {@code ProPlaceTerritory.getPlaceUnits()} entries per option. It does <b>not</b>
 * re-consult {@code ProResourceTracker} at aggregation time, so nothing forces the aggregated
 * cost to equal the cost tracked during the loop.
 *
 * <p>The map produced by {@code populateProductionRuleMap} is then passed directly to
 * {@code purchaseDelegate.purchase(purchaseMap)} at
 * {@code ProPurchaseAi.java:251} (main purchase path) and {@code ProPurchaseAi.java:380}
 * (upgrade / second-pass path), with no intervening validation. Neither
 * {@code ai/pro/util/ProPurchaseUtils.java} nor {@code ai/pro/util/ProPurchaseValidationUtils.java}
 * contains any clamp/trim/budget-check helper — a grep for {@code trim|clamp|budget|exceed}
 * across both files returns zero hits. A 123 PU overrun against a 120 PU budget has been
 * observed in practice (see Phase 3 spec §5 note); this is consistent with the gap described
 * above.
 *
 * <p>The {@link #trimToFit} backstop implemented here is therefore <b>a necessary backstop,
 * not a paraphrase of an existing TripleA routine</b>. It reads exactly the map that ProAi
 * would have handed to the live purchase delegate and drops production-rule units from the
 * lowest-priority end until the total PU cost fits the caller's budget.
 *
 * <p><b>Trim order:</b> reverse iteration over the captured {@code IntegerMap<ProductionRule>}.
 * {@code populateProductionRuleMap} iterates {@code ProPurchaseOptionMap.getAllOptions()} in
 * descending priority, so the resulting {@code IntegerMap} preserves that ordering; dropping
 * from the tail therefore drops the lowest priority rules first.
 */
public final class PurchaseExecutor implements DecisionExecutor<PurchaseRequest, PurchasePlan> {

  private final ProSessionSnapshotStore snapshotStore;

  /** Production constructor — uses the provided snapshot store. */
  public PurchaseExecutor(final ProSessionSnapshotStore snapshotStore) {
    this.snapshotStore = snapshotStore;
  }

  /**
   * No-arg constructor for tests and contexts where snapshot persistence is not needed. Saves go
   * to a subdirectory of {@code java.io.tmpdir} and are harmless.
   */
  public PurchaseExecutor() {
    this(new ProSessionSnapshotStore(
        Path.of(System.getProperty("java.io.tmpdir"), "sidecar-snapshots")));
  }

  @Override
  public PurchasePlan execute(final Session session, final PurchaseRequest request) {
    final GameData data = session.gameData();
    WireStateApplier.apply(data, request.state(), session.unitIdMap());

    final GamePlayer player =
        data.getPlayerList().getPlayerId(request.state().currentPlayer());
    if (player == null) {
      throw new IllegalArgumentException(
          "Unknown player in PurchaseRequest: " + request.state().currentPlayer());
    }

    ExecutorSupport.ensureProAiInitialized(session, player);
    // ProPurchaseAi.repair -> ProMatches.territoryIsNotConqueredOwnedLand reads the
    // BattleTracker via GameData.getBattleDelegate(); the clone's internal GameDataUtils copy
    // (GameDataManager save/load with withDelegates=true) only persists delegates that exist
    // on the source, so we must pre-register move/place/battle before dispatching. Our
    // CanonicalGameData.cloneForSession uses a raw ObjectOutputStream round-trip that wipes
    // the transient delegates map (GameData.java:111).
    ExecutorSupport.ensureBattleDelegate(data);
    ensureDelegate(data, "move", "Move", new MoveDelegate());
    ensureDelegate(data, "place", "Place", new PlaceDelegate());
    ensureDelegate(data, "politics", "Politics", new PoliticsDelegate());
    ensureDelegate(data, "endRound", "End Round", new EndRoundDelegate());

    final Resource pus = data.getResourceList().getResourceOrThrow(Constants.PUS);
    final int pusToSpend = player.getResources().getQuantity(pus);

    final ProAi proAi = session.proAi();
    final RecordingPurchaseDelegate recorder = new RecordingPurchaseDelegate();
    recorder.initialize("purchase", "Purchase");
    recorder.setDelegateBridgeAndPlayer(new ProDummyDelegateBridge(proAi, player, data));

    final Future<Void> future =
        session
            .offensiveExecutor()
            .submit(
                () -> {
                  proAi.invokePurchaseForSidecar(
                      /* purchaseForBid */ false, pusToSpend, recorder, data, player);
                  return null;
                });
    try {
      future.get();
    } catch (final InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("PurchaseExecutor interrupted", ie);
    } catch (final ExecutionException ee) {
      final Throwable cause = ee.getCause();
      if (cause instanceof RuntimeException re) {
        throw re;
      }
      throw new RuntimeException("PurchaseExecutor failed", cause);
    }

    // Persist stored maps so that combat-move / noncombat-move / place executors can restore them
    // if the next request arrives after a process restart or session re-use.
    // Serialize the session's unitIdMap (wireId → UUID) into the snapshot so that subsequent
    // executors can pre-populate it before WireStateApplier.apply() runs, ensuring the same
    // UUIDs are assigned to the same wire unit IDs after a restart.
    final Map<String, String> wireToUuid = new HashMap<>();
    session.unitIdMap().forEach((wireId, uuid) -> wireToUuid.put(wireId, uuid.toString()));
    snapshotStore.save(session.key(), session.proAi().snapshotForSidecar(wireToUuid));

    final IntegerMap<ProductionRule> captured = recorder.capturedPurchase();
    final IntegerMap<ProductionRule> trimmed = trimToFit(captured, pusToSpend, pus);

    final List<PurchaseOrder> buys = toPurchaseOrders(trimmed);
    final List<RepairOrder> repairs = toRepairOrders(recorder.capturedRepair(), data);
    return new PurchasePlan(buys, repairs);
  }

  /**
   * Drop rules in reverse-priority order (iterating from the tail of the captured map) until
   * the total PU cost fits {@code pusBudget}. See the class Javadoc for the justification.
   */
  static IntegerMap<ProductionRule> trimToFit(
      final IntegerMap<ProductionRule> captured, final int pusBudget, final Resource pus) {
    final IntegerMap<ProductionRule> result = new IntegerMap<>(captured);
    final List<ProductionRule> order = new ArrayList<>(result.keySet());
    int idx = order.size() - 1;
    while (totalPuCost(result, pus) > pusBudget && idx >= 0) {
      final ProductionRule rule = order.get(idx);
      final int n = result.getInt(rule);
      if (n > 0) {
        result.put(rule, n - 1);
        if (result.getInt(rule) == 0) {
          result.removeKey(rule);
          idx--;
        }
      } else {
        idx--;
      }
    }
    return result;
  }

  private static int totalPuCost(final IntegerMap<ProductionRule> map, final Resource pus) {
    int sum = 0;
    for (final ProductionRule rule : map.keySet()) {
      sum += rule.getCosts().getInt(pus) * map.getInt(rule);
    }
    return sum;
  }

  private static List<PurchaseOrder> toPurchaseOrders(final IntegerMap<ProductionRule> map) {
    final List<PurchaseOrder> out = new ArrayList<>();
    for (final ProductionRule rule : map.keySet()) {
      final int count = map.getInt(rule);
      if (count <= 0) {
        continue;
      }
      // A ProductionRule can in principle produce multiple results; in practice for Global
      // 1940 each rule produces one UnitType. Take the first UnitType result and drop any
      // non-unit (pure-resource) rules — those cannot be placed by Map Room's placement API.
      NamedAttachable firstUnitResult = null;
      for (final NamedAttachable na : rule.getResults().keySet()) {
        if (na instanceof UnitType) {
          firstUnitResult = na;
          break;
        }
      }
      if (firstUnitResult instanceof UnitType ut) {
        out.add(new PurchaseOrder(ut.getName(), count, null));
      }
    }
    return out;
  }

  private static List<RepairOrder> toRepairOrders(
      final Map<Unit, IntegerMap<RepairRule>> map, final GameData data) {
    final List<RepairOrder> out = new ArrayList<>();
    for (final Map.Entry<Unit, IntegerMap<RepairRule>> entry : map.entrySet()) {
      final Unit unit = entry.getKey();
      final String territoryName = findTerritoryNameOf(data, unit);
      final String unitTypeName = unit.getType().getName();
      for (final RepairRule rule : entry.getValue().keySet()) {
        final int count = entry.getValue().getInt(rule);
        if (count <= 0) {
          continue;
        }
        out.add(new RepairOrder(territoryName, unitTypeName, count));
      }
    }
    return out;
  }

  private static void ensureDelegate(
      final GameData data, final String name, final String displayName, final IDelegate delegate) {
    if (data.getDelegateOptional(name).isPresent()) {
      return;
    }
    delegate.initialize(name, displayName);
    data.addDelegate(delegate);
  }

  private static String findTerritoryNameOf(final GameData data, final Unit unit) {
    for (final Territory t : data.getMap().getTerritories()) {
      if (t.getUnits().contains(unit)) {
        return t.getName();
      }
    }
    return "";
  }
}
