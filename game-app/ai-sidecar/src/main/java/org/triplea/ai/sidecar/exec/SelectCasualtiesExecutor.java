package org.triplea.ai.sidecar.exec;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.player.PlayerBridge;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.delegate.battle.BattleDelegate;
import games.strategy.triplea.delegate.battle.BattleTracker;
import games.strategy.triplea.delegate.battle.IBattle;
import games.strategy.triplea.delegate.data.CasualtyDetails;
import games.strategy.triplea.delegate.data.CasualtyList;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import org.triplea.ai.sidecar.dto.SelectCasualtiesPlan;
import org.triplea.ai.sidecar.dto.SelectCasualtiesRequest;
import org.triplea.ai.sidecar.session.Session;
import org.triplea.ai.sidecar.wire.WireStateApplier;
import org.triplea.ai.sidecar.wire.WireUnit;

/**
 * Executes a {@code select-casualties} decision by invoking {@link ProAi#selectCasualties}.
 *
 * <p>The ProAi entry point depends on transient combat-phase state that {@link
 * WireStateApplier} deliberately does <b>not</b> touch: it looks up an {@link IBattle} out of
 * {@code BattleDelegate.getBattleTracker()} by {@link UUID} and reads the attacker,
 * attacking-unit list, and defending-unit list off it. If no pending battle is registered for
 * the supplied {@code battleId}, ProAi silently returns an empty casualty list — which would
 * manifest as garbage flowing back over the wire to Map Room. To prevent that, this executor
 * constructs a minimal read-only {@link SyntheticBattle} carrying the attacker / defender /
 * attacking / defending collections from the request and splices it directly into the
 * tracker's private {@code pendingBattles} set via reflection for the duration of the call.
 * The synthetic battle is removed in a {@code finally} block regardless of outcome so shared
 * game-data state never leaks across executor invocations.
 *
 * <p>On first use per session the executor lazily attaches a {@link PlayerBridge} + {@link
 * GamePlayer} to the session's {@link ProAi} (which is constructed bridge-less by {@link
 * org.triplea.ai.sidecar.session.SessionRegistry}). The bridge wraps a no-op {@link
 * HeadlessGame}; it only exists so that {@code AbstractBasePlayer.getGameData()} — called
 * transitively from {@code ProData.initialize} — returns the session's cloned {@link
 * GameData} rather than NPE.
 */
public final class SelectCasualtiesExecutor
    implements DecisionExecutor<SelectCasualtiesRequest, SelectCasualtiesPlan> {

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

  @Override
  public SelectCasualtiesPlan execute(
      final Session session, final SelectCasualtiesRequest request) {
    final GameData data = session.gameData();
    final ConcurrentMap<String, UUID> idMap = session.unitIdMap();

    WireStateApplier.apply(data, request.state(), idMap);

    final SelectCasualtiesRequest.SelectCasualtiesBattle b = request.battle();
    final GamePlayer defender = requirePlayer(data, b.defenderNation());
    final GamePlayer attacker = requirePlayer(data, b.attackerNation());
    final Territory battleSite = data.getMap().getTerritoryOrNull(b.territory());
    if (battleSite == null) {
      throw new IllegalArgumentException("Unknown battle territory: " + b.territory());
    }

    ensureProAiInitialized(session, defender);

    // Resolve the wire-side unit lists to live Unit instances in the cloned game data. We
    // derive them from the territory (and the id map populated by WireStateApplier) rather
    // than re-creating new Unit objects — the ProAi logic later does reference-equality
    // comparisons against the units it was passed, so they must be the same instances that
    // live on the territory.
    final Map<UUID, Unit> unitsOnSite = indexById(battleSite.getUnits());
    final List<Unit> selectFrom = resolveUnits(b.selectFrom(), idMap, unitsOnSite);
    final List<Unit> friendlyUnits = resolveUnits(b.friendlyUnits(), idMap, unitsOnSite);
    final List<Unit> enemyUnits = resolveUnits(b.enemyUnits(), idMap, unitsOnSite);
    final List<Unit> amphibiousLandAttackers =
        resolveUnits(b.amphibiousLandAttackers(), idMap, unitsOnSite);

    final CasualtyList defaultCasualties = buildDefaultCasualties(b.defaultCasualties(), idMap, unitsOnSite, b.hitCount());

    // AbstractProAi.selectCasualties fetches the pending battle for this UUID; synthesise one
    // and register it in the tracker so the call sees non-null battle state. The ProAi entry
    // point only reads battle.getAttacker() / getAttackingUnits() / getDefendingUnits(). The
    // wire request is framed around the hit player as "defender" of the casualty step, so
    // friendlyUnits (from hit player's view) become the battle's defenders and enemyUnits
    // become the battle's attackers.
    final UUID battleUuid = UUID.nameUUIDFromBytes(b.battleId().getBytes());
    ensureBattleDelegate(data);
    final BattleTracker tracker = data.getBattleDelegate().getBattleTracker();
    final List<Unit> battleAttackers = new ArrayList<>(enemyUnits);
    final List<Unit> battleDefenders = new ArrayList<>(friendlyUnits);

    final SyntheticBattle synthetic =
        new SyntheticBattle(
            battleUuid,
            battleSite,
            attacker,
            defender,
            battleAttackers,
            battleDefenders,
            b.isAmphibious());
    addPendingBattle(tracker, synthetic);

    final CasualtyDetails details;
    try {
      details =
          session
              .proAi()
              .selectCasualties(
                  selectFrom,
                  /* dependents */ Map.of(),
                  b.hitCount(),
                  /* message */ "sidecar-select-casualties",
                  /* dice */ null,
                  /* hit */ defender,
                  /* friendlyUnits */ friendlyUnits,
                  /* enemyUnits */ enemyUnits,
                  b.isAmphibious(),
                  amphibiousLandAttackers,
                  defaultCasualties,
                  battleUuid,
                  battleSite,
                  b.allowMultipleHitsPerUnit());
    } finally {
      removePendingBattle(tracker, synthetic);
    }

    final Map<UUID, String> reverseIdMap = reverseIdMap(idMap);
    final List<String> killedIds = new ArrayList<>(details.getKilled().size());
    for (final Unit u : details.getKilled()) {
      killedIds.add(mapRoomIdOf(u, reverseIdMap));
    }
    final List<String> damagedIds = new ArrayList<>(details.getDamaged().size());
    for (final Unit u : details.getDamaged()) {
      damagedIds.add(mapRoomIdOf(u, reverseIdMap));
    }
    return new SelectCasualtiesPlan(killedIds, damagedIds);
  }

  private static GamePlayer requirePlayer(final GameData data, final String name) {
    final GamePlayer p = data.getPlayerList().getPlayerId(name);
    if (p == null) {
      throw new IllegalArgumentException("Unknown player in SelectCasualtiesRequest: " + name);
    }
    return p;
  }

  private static Map<UUID, Unit> indexById(final Collection<Unit> units) {
    final Map<UUID, Unit> out = new HashMap<>();
    for (final Unit u : units) {
      out.put(u.getId(), u);
    }
    return out;
  }

  private static List<Unit> resolveUnits(
      final List<WireUnit> wireUnits,
      final ConcurrentMap<String, UUID> idMap,
      final Map<UUID, Unit> onSite) {
    final List<Unit> out = new ArrayList<>(wireUnits.size());
    for (final WireUnit wu : wireUnits) {
      final UUID uuid = idMap.get(wu.unitId());
      if (uuid == null) {
        throw new IllegalArgumentException(
            "SelectCasualtiesRequest references unknown unit id (not in WireState): "
                + wu.unitId());
      }
      final Unit unit = onSite.get(uuid);
      if (unit == null) {
        throw new IllegalArgumentException(
            "SelectCasualtiesRequest unit " + wu.unitId() + " is not on battle territory");
      }
      out.add(unit);
    }
    return out;
  }

  private static CasualtyList buildDefaultCasualties(
      final List<String> defaultCasualtyIds,
      final ConcurrentMap<String, UUID> idMap,
      final Map<UUID, Unit> onSite,
      final int hitCount) {
    final List<Unit> killed = new ArrayList<>(defaultCasualtyIds.size());
    for (final String mapRoomId : defaultCasualtyIds) {
      final UUID uuid = idMap.get(mapRoomId);
      if (uuid == null) {
        throw new IllegalArgumentException(
            "defaultCasualties references unknown unit id: " + mapRoomId);
      }
      final Unit unit = onSite.get(uuid);
      if (unit == null) {
        throw new IllegalArgumentException(
            "defaultCasualties unit " + mapRoomId + " is not on battle territory");
      }
      killed.add(unit);
    }
    // AbstractProAi enforces defaultCasualties.size() == hitCount.
    if (killed.size() != hitCount) {
      throw new IllegalArgumentException(
          "defaultCasualties size (" + killed.size() + ") != hitCount (" + hitCount + ")");
    }
    return new CasualtyList(killed, new ArrayList<>());
  }

  private static void ensureBattleDelegate(final GameData data) {
    if (data.getDelegateOptional("battle").isPresent()) {
      return;
    }
    // CanonicalGameData.cloneForSession() round-trips GameData via ObjectOutputStream; the
    // delegate map is transient and cleared by postDeSerialize(), so we re-register a fresh
    // BattleDelegate on first use. BattleDelegate's BattleTracker is created in its default
    // constructor, so this is enough to satisfy data.getBattleDelegate() lookups from
    // AbstractProAi.selectCasualties.
    final BattleDelegate delegate = new BattleDelegate();
    delegate.initialize("battle", "Combat");
    data.addDelegate(delegate);
  }

  private static void ensureProAiInitialized(final Session session, final GamePlayer player) {
    final ProAi proAi = session.proAi();
    if (proAi.getGamePlayer() != null) {
      return;
    }
    final PlayerBridge bridge = new PlayerBridge(new HeadlessGame(session.gameData()));
    proAi.initialize(bridge, player);
  }

  @SuppressWarnings("unchecked")
  private static void addPendingBattle(final BattleTracker tracker, final IBattle battle) {
    try {
      final Set<IBattle> set = (Set<IBattle>) PENDING_BATTLES_FIELD.get(tracker);
      set.add(battle);
    } catch (final IllegalAccessException e) {
      throw new IllegalStateException("Cannot reflectively access BattleTracker.pendingBattles", e);
    }
  }

  @SuppressWarnings("unchecked")
  private static void removePendingBattle(final BattleTracker tracker, final IBattle battle) {
    try {
      final Set<IBattle> set = (Set<IBattle>) PENDING_BATTLES_FIELD.get(tracker);
      set.remove(battle);
    } catch (final IllegalAccessException e) {
      throw new IllegalStateException("Cannot reflectively access BattleTracker.pendingBattles", e);
    }
  }

  private static Map<UUID, String> reverseIdMap(final ConcurrentMap<String, UUID> idMap) {
    final Map<UUID, String> out = new HashMap<>(idMap.size());
    for (final Map.Entry<String, UUID> e : idMap.entrySet()) {
      out.put(e.getValue(), e.getKey());
    }
    return out;
  }

  private static String mapRoomIdOf(final Unit unit, final Map<UUID, String> reverse) {
    final String id = reverse.get(unit.getId());
    if (id == null) {
      throw new IllegalStateException(
          "ProAi returned casualty unit with UUID not in session idMap: " + unit.getId());
    }
    return id;
  }
}
