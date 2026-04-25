package org.triplea.ai.sidecar.exec;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.delegate.battle.BattleTracker;
import games.strategy.triplea.delegate.battle.IBattle;
import games.strategy.triplea.delegate.data.CasualtyDetails;
import games.strategy.triplea.delegate.data.CasualtyList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * <p>The ProAi entry point depends on transient combat-phase state that {@link WireStateApplier}
 * deliberately does <b>not</b> touch: it looks up an {@link IBattle} out of {@code
 * BattleDelegate.getBattleTracker()} by {@link UUID} and reads the attacker, attacking-unit list,
 * and defending-unit list off it. If no pending battle is registered for the supplied {@code
 * battleId}, ProAi silently returns an empty casualty list — which would manifest as garbage
 * flowing back over the wire to Map Room. To prevent that, this executor constructs a minimal
 * read-only {@link SyntheticBattle} carrying the attacker / defender / attacking / defending
 * collections from the request and splices it directly into the tracker's private {@code
 * pendingBattles} set via reflection for the duration of the call. The synthetic battle is removed
 * in a {@code finally} block regardless of outcome so shared game-data state never leaks across
 * executor invocations.
 *
 * <p>On first use per session the executor lazily attaches a {@link PlayerBridge} + {@link
 * GamePlayer} to the session's {@link ProAi} (which is constructed bridge-less by {@link
 * org.triplea.ai.sidecar.session.SessionRegistry}). The bridge wraps a no-op {@link HeadlessGame};
 * it only exists so that {@code AbstractBasePlayer.getGameData()} — called transitively from {@code
 * ProData.initialize} — returns the session's cloned {@link GameData} rather than NPE.
 */
public final class SelectCasualtiesExecutor
    implements DecisionExecutor<SelectCasualtiesRequest, SelectCasualtiesPlan> {

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

    ExecutorSupport.ensureProAiInitialized(session, defender);

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

    final CasualtyList defaultCasualties =
        buildDefaultCasualties(b.defaultCasualties(), idMap, unitsOnSite, b.hitCount());

    // AbstractProAi.selectCasualties fetches the pending battle for this UUID; synthesise one
    // and register it in the tracker so the call sees non-null battle state. The ProAi entry
    // point only reads battle.getAttacker() / getAttackingUnits() / getDefendingUnits(). The
    // wire request is framed around the hit player as "defender" of the casualty step, so
    // friendlyUnits (from hit player's view) become the battle's defenders and enemyUnits
    // become the battle's attackers.
    final UUID battleUuid = UUID.nameUUIDFromBytes(b.battleId().getBytes());
    ExecutorSupport.ensureBattleDelegate(data);
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
    ExecutorSupport.addPendingBattle(tracker, synthetic);

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
      ExecutorSupport.removePendingBattle(tracker, synthetic);
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
    // AbstractProAi.selectCasualties (AbstractProAi.java:390) enforces
    // defaultCasualties.size() == hitCount and throws IllegalStateException otherwise.
    // Map Room must therefore supply exactly hitCount default-casualty IDs. The upstream
    // builder (packages/server/src/ai/decision-detectors.ts:120) reads
    // battle.autoDefenseCasualties which is only populated by the auto-profile path
    // (battle-steps.ts:432,739) — in that path the invariant holds because
    // autoCasualtiesWithProfile is called with the same hit groups used to derive hitCount.
    // We enforce the invariant here at the sidecar boundary so a violation produces a clear
    // 400 rather than an opaque 500 from ProAi's own check.
    if (killed.size() != hitCount) {
      throw new IllegalArgumentException(
          "defaultCasualties size (" + killed.size() + ") != hitCount (" + hitCount + ")");
    }
    return new CasualtyList(killed, new ArrayList<>());
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
