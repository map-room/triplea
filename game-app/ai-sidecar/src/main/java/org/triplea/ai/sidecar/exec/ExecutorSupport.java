package org.triplea.ai.sidecar.exec;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Unit;
import games.strategy.engine.player.PlayerBridge;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.delegate.battle.BattleDelegate;
import games.strategy.triplea.delegate.battle.BattleTracker;
import games.strategy.triplea.delegate.battle.IBattle;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.triplea.ai.sidecar.dto.CombatMoveOrder;
import org.triplea.ai.sidecar.session.Session;

/**
 * Shared plumbing used by decision executors that dispatch into {@code AbstractProAi}.
 *
 * <p>Three concerns show up in every defensive executor and are centralised here:
 *
 * <ul>
 *   <li>Lazy {@link PlayerBridge}/{@link GamePlayer} initialisation on the session's {@link
 *       ProAi} (constructed bridge-less by {@link
 *       org.triplea.ai.sidecar.session.SessionRegistry}). Required so {@code
 *       AbstractBasePlayer.getGameData()} — called transitively from {@code
 *       ProData.initialize} — returns the session's cloned {@link GameData} rather than NPE.
 *   <li>Lazy {@link BattleDelegate} re-registration after a {@code GameData} round-trip clears
 *       the delegate map during {@code postDeSerialize}.
 *   <li>Reflective splicing of a {@link SyntheticBattle} into {@link
 *       BattleTracker#pendingBattles pendingBattles} so ProAi entry points that read the
 *       tracker by battle {@link java.util.UUID} see non-null battle state.
 * </ul>
 *
 * <p>Package-private: consumed only by executors in this package.
 */
final class ExecutorSupport {

  private static final Field PENDING_BATTLES_FIELD;

  static {
    try {
      PENDING_BATTLES_FIELD = BattleTracker.class.getDeclaredField("pendingBattles");
      PENDING_BATTLES_FIELD.setAccessible(true);
    } catch (final NoSuchFieldException e) {
      throw new ExceptionInInitializerError(
          "BattleTracker.pendingBattles field not found — TripleA internal API has drifted: "
              + e.getMessage());
    }
  }

  private ExecutorSupport() {}

  /**
   * Attach a {@link PlayerBridge} + {@link GamePlayer} to the session's {@link ProAi} on first
   * use. No-op if already initialised.
   *
   * <p>Synchronized on the {@link Session} instance to prevent a check-then-act race: two
   * concurrent HTTP threads on the same session could both observe {@code
   * proAi.getGamePlayer() == null} and call {@code initialize} twice, corrupting the ProAi
   * internal state. The session record is a stable reference shared across all executors on the
   * same session, so it is a safe monitor.
   */
  static void ensureProAiInitialized(final Session session, final GamePlayer player) {
    synchronized (session) {
      final ProAi proAi = session.proAi();
      if (proAi.getGamePlayer() != null) {
        return;
      }
      final PlayerBridge bridge = new PlayerBridge(new HeadlessGame(session.gameData()));
      proAi.initialize(bridge, player);
    }
  }

  /**
   * Ensure the cloned game data has a registered {@code battle} delegate. {@link
   * org.triplea.ai.sidecar.CanonicalGameData#cloneForSession} round-trips {@link GameData}
   * via {@code ObjectOutputStream}; the delegate map is transient and cleared by {@code
   * postDeSerialize()}, so a fresh {@link BattleDelegate} is re-registered here on first use.
   */
  static void ensureBattleDelegate(final GameData data) {
    if (data.getDelegateOptional("battle").isPresent()) {
      return;
    }
    final BattleDelegate delegate = new BattleDelegate();
    delegate.initialize("battle", "Combat");
    data.addDelegate(delegate);
  }

  @SuppressWarnings("unchecked")
  static void addPendingBattle(final BattleTracker tracker, final IBattle battle) {
    try {
      final Set<IBattle> set = (Set<IBattle>) PENDING_BATTLES_FIELD.get(tracker);
      set.add(battle);
    } catch (final IllegalAccessException e) {
      throw new IllegalStateException("Cannot reflectively access BattleTracker.pendingBattles", e);
    }
  }

  @SuppressWarnings("unchecked")
  static void removePendingBattle(final BattleTracker tracker, final IBattle battle) {
    try {
      final Set<IBattle> set = (Set<IBattle>) PENDING_BATTLES_FIELD.get(tracker);
      set.remove(battle);
    } catch (final IllegalAccessException e) {
      throw new IllegalStateException("Cannot reflectively access BattleTracker.pendingBattles", e);
    }
  }

  /**
   * Projects a single validated {@link MoveDescription} to one or more {@link CombatMoveOrder}s.
   *
   * <p>Three cases:
   * <ul>
   *   <li><b>Load</b> ({@code route.isLoad()}): land → sea zone. {@code unitsToSeaTransports}
   *       maps cargo→transport. Grouped by transport so each transport emits one order with all
   *       its cargo. {@code kind="load"}, {@code transportId} = wire ID of the transport.
   *   <li><b>Unload</b> ({@code route.isUnload()}): sea zone → land. All units are cargo; the
   *       engine infers the transport from {@code transportedBy}. One order is emitted for the
   *       whole batch. {@code kind="unload"}, {@code transportId} = wire ID of first sea unit in
   *       the move (for traceability; engine does not consume it).
   *   <li><b>Plain move</b>: single order, {@code kind="move"}.
   * </ul>
   *
   * @param move the captured move description
   * @param uuidToWireId reverse map from Java UUID to Map Room wire ID
   * @return list of orders (may be empty if all unit IDs resolved to null)
   */
  static List<CombatMoveOrder> projectOrders(
      final MoveDescription move, final Map<UUID, String> uuidToWireId) {
    final String from = move.getRoute().getStart().getName();
    final String to = move.getRoute().getEnd().getName();

    if (move.getRoute().isLoad()) {
      // Group cargo by transport; emit one order per transport.
      final Map<Unit, List<Unit>> cargoByTransport = new LinkedHashMap<>();
      for (final Map.Entry<Unit, Unit> e : move.getUnitsToSeaTransports().entrySet()) {
        cargoByTransport.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
      }
      // If unitsToSeaTransports is empty despite isLoad(), fall through to plain-move.
      if (!cargoByTransport.isEmpty()) {
        final List<CombatMoveOrder> orders = new ArrayList<>();
        for (final Map.Entry<Unit, List<Unit>> e : cargoByTransport.entrySet()) {
          final String transportWireId = uuidToWireId.get(e.getKey().getId());
          final List<String> cargoWireIds = e.getValue().stream()
              .map(u -> uuidToWireId.get(u.getId()))
              .filter(Objects::nonNull)
              .toList();
          if (cargoWireIds.isEmpty()) {
            continue;
          }
          orders.add(new CombatMoveOrder(cargoWireIds, from, to, "load", transportWireId));
        }
        if (!orders.isEmpty()) {
          return orders;
        }
      }
    } else if (move.getRoute().isUnload()) {
      // All non-transport units in the move are cargo. Find the transport for traceability.
      final List<String> cargoWireIds = move.getUnits().stream()
          .filter(u -> !u.getUnitAttachment().isTransportCapacity())
          .map(u -> uuidToWireId.get(u.getId()))
          .filter(Objects::nonNull)
          .toList();
      if (!cargoWireIds.isEmpty()) {
        final String transportWireId = move.getUnits().stream()
            .filter(u -> u.getUnitAttachment().getTransportCapacity() > 0)
            .map(u -> uuidToWireId.get(u.getId()))
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
        return List.of(new CombatMoveOrder(cargoWireIds, from, to, "unload", transportWireId));
      }
    }

    // Plain move (or load/unload that resolved to no cargo IDs — fall back to full unit list).
    final List<String> unitIds = move.getUnits().stream()
        .map(u -> uuidToWireId.get(u.getId()))
        .filter(Objects::nonNull)
        .toList();
    return List.of(new CombatMoveOrder(unitIds, from, to));
  }
}
