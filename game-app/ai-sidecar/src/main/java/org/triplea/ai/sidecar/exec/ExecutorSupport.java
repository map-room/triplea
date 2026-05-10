package org.triplea.ai.sidecar.exec;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.player.PlayerBridge;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.delegate.battle.BattleDelegate;
import games.strategy.triplea.delegate.battle.BattleTracker;
import games.strategy.triplea.delegate.battle.IBattle;
import java.lang.reflect.Field;
import java.util.Set;
import org.triplea.ai.sidecar.session.Session;

/**
 * Shared plumbing used by decision executors that dispatch into {@code AbstractProAi}.
 *
 * <p>Two concerns show up in every executor and are centralised here:
 *
 * <ul>
 *   <li>Lazy {@link PlayerBridge}/{@link GamePlayer} initialisation on the session's {@link ProAi}
 *       (constructed bridge-less by {@link org.triplea.ai.sidecar.session.SessionRegistry}).
 *       Required so {@code AbstractBasePlayer.getGameData()} — called transitively from {@code
 *       ProData.initialize} — returns the session's cloned {@link GameData} rather than NPE.
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
   * Attach a {@link PlayerBridge} + {@link GamePlayer} to the session's {@link ProAi} on first use.
   * No-op if already initialised.
   *
   * <p>Synchronized on the {@link Session} instance to prevent a check-then-act race: two
   * concurrent HTTP threads on the same session could both observe {@code proAi.getGamePlayer() ==
   * null} and call {@code initialize} twice, corrupting the ProAi internal state. The session
   * record is a stable reference shared across all executors on the same session, so it is a safe
   * monitor.
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
