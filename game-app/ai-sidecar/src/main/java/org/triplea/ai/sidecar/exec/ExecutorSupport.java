package org.triplea.ai.sidecar.exec;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.player.PlayerBridge;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.delegate.PlaceDelegate;
import games.strategy.triplea.delegate.battle.BattleDelegate;
import games.strategy.triplea.delegate.battle.BattleTracker;
import games.strategy.triplea.delegate.battle.IBattle;
import java.lang.reflect.Field;
import java.util.Set;

/**
 * Shared plumbing used by decision executors that dispatch into {@code AbstractProAi}.
 *
 * <p>Two concerns show up in every executor and are centralised here:
 *
 * <ul>
 *   <li>{@link PlayerBridge}/{@link GamePlayer} initialisation on the per-call {@link ProAi} —
 *       every freshly-constructed ProAi is bridge-less until {@link ProAi#initialize(PlayerBridge,
 *       GamePlayer)} runs. Required so {@code AbstractBasePlayer.getGameData()} — called
 *       transitively from {@code ProData.initialize} — returns the cloned {@link GameData} rather
 *       than NPE.
 *   <li>Lazy {@link BattleDelegate} re-registration after a {@code GameData} round-trip clears the
 *       delegate map during {@code postDeSerialize}.
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
   * Attach a {@link PlayerBridge} + {@link GamePlayer} to the per-call {@link ProAi}. Always called
   * exactly once per executor invocation, before the first ProAi entry point runs.
   */
  static void initializeProAi(final ProAi proAi, final GameData data, final GamePlayer player) {
    final PlayerBridge bridge = new PlayerBridge(new HeadlessGame(data));
    proAi.initialize(bridge, player);
  }

  /**
   * Ensure the cloned game data has a registered {@code battle} delegate. {@link
   * org.triplea.ai.sidecar.CanonicalGameData#cloneForSession} round-trips {@link GameData} via
   * {@code ObjectOutputStream}; the delegate map is transient and cleared by {@code
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

  /**
   * Ensure the {@code place} delegate is registered. Required by the stateless {@link
   * NoncombatMoveExecutor} path: with no snapshot-restored {@code storedPurchaseTerritories},
   * {@link games.strategy.triplea.ai.pro.ProNonCombatMoveAi#findUnitsThatCantMove} falls through to
   * {@link games.strategy.triplea.ai.pro.util.ProPurchaseUtils#findMaxPurchaseDefenders}, which
   * calls {@code data.getDelegate("place")} via {@code ProPurchaseValidationUtils.canUnitsBePlaced}
   * — and throws {@code "place delegate not found"} when that delegate is not registered. Same
   * rationale as {@link #ensureBattleDelegate}: the canonical clone strips transient delegates
   * during {@code postDeSerialize}.
   */
  static void ensurePlaceDelegate(final GameData data) {
    if (data.getDelegateOptional("place").isPresent()) {
      return;
    }
    final PlaceDelegate delegate = new PlaceDelegate();
    delegate.initialize("place", "Place");
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
}
