package org.triplea.ai.sidecar.exec;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.AbstractProAi;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.battle.BattleTracker;
import games.strategy.triplea.delegate.battle.IBattle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import org.triplea.ai.sidecar.AiTraceLogger;
import org.triplea.ai.sidecar.dto.ScramblePlan;
import org.triplea.ai.sidecar.dto.ScrambleRequest;
import org.triplea.ai.sidecar.session.Session;
import org.triplea.ai.sidecar.wire.WireStateApplier;
import org.triplea.ai.sidecar.wire.WireUnit;
import org.triplea.util.Tuple;

/**
 * Executes a {@code scramble} decision by invoking {@link
 * AbstractProAi#scrambleUnitsQuery(Territory, Map)}.
 *
 * <p>Symmetric with {@link RetreatQueryExecutor}: applies the embedded {@link
 * org.triplea.ai.sidecar.wire.WireState}, synthesises a pending {@link IBattle} at the scramble-to
 * territory so {@code ProScrambleAi} can find it via {@code BattleTracker.getPendingBattle},
 * dispatches into ProAi, and maps the resulting {@code Map<Territory, Collection<Unit>>} back to a
 * wire-shaped {@link ScramblePlan} keyed by source territory name with Map Room unit IDs.
 *
 * <h2>Airbase resolution — why the wire {@code maxCount} is advisory</h2>
 *
 * <p>ProScrambleAi constructs a per-source {@code Tuple<Collection<Unit>, Collection<Unit>>} where
 * the {@code First} collection is the set of <b>airbases</b> on the source territory (see {@code
 * ScrambleLogic#getMaxScrambleCount} which requires every member to match {@code
 * Matches.unitIsAirBase()}). The {@code Second} collection is the air units eligible to scramble.
 * The task description's gloss about "max N scramblers" in First is a simplification: the real
 * TripleA contract is airbases-in-first, and the cap is computed by summing per-airbase {@code
 * maxScrambleCount}.
 *
 * <p>This executor therefore derives {@code First} directly from the live source territory by
 * matching {@code unitIsAirBase()}, and {@code Second} from the wire-supplied {@code
 * ScrambleSource.units} resolved via the session unit id map. The wire {@code
 * ScrambleSource.maxCount} field is intentionally not enforced here — it is advisory for Map Room's
 * own pre-flight and the authoritative cap comes from TripleA's airbase attachments. A source with
 * no airbase on the live territory is skipped (ProScrambleAi would throw on it), which is
 * consistent with Map Room only requesting a scramble when at least one air-base is present.
 *
 * <h2>Empty {@code possibleScramblers} short-circuit</h2>
 *
 * <p>If the wire request has no source territories we return an empty {@link ScramblePlan} without
 * invoking ProAi — ProScrambleAi would short-circuit to {@code null} on its first {@code
 * calculateBattleResults} loop anyway, and avoiding the call path keeps the no-op fast and removes
 * a needless {@code BattleTracker} round-trip.
 */
public final class ScrambleExecutor implements DecisionExecutor<ScrambleRequest, ScramblePlan> {

  @Override
  public ScramblePlan execute(final Session session, final ScrambleRequest request) {
    final GameData data = session.gameData();
    final ConcurrentMap<String, UUID> idMap = session.unitIdMap();

    WireStateApplier.apply(data, request.state(), idMap);

    final ScrambleRequest.ScrambleBattle b = request.battle();
    if (b.possibleScramblers() == null || b.possibleScramblers().isEmpty()) {
      // Emit the rationale line on the short-circuit too (#2104). reason=no-candidates.
      AiTraceLogger.logScrambleDecision(
          session.key().nation(),
          b.defendingTerritory(),
          /* candidates */ List.of(),
          /* picked */ List.of(),
          Map.of());
      return new ScramblePlan(Map.of());
    }

    final Territory scrambleTo = data.getMap().getTerritoryOrNull(b.defendingTerritory());
    if (scrambleTo == null) {
      throw new IllegalArgumentException(
          "Unknown defending territory in ScrambleRequest: " + b.defendingTerritory());
    }

    // Build the possibleScramblers map that ProScrambleAi expects:
    //   Map<Territory (source), Tuple<airbases, canScrambleAir>>
    final Map<Territory, Tuple<Collection<Unit>, Collection<Unit>>> possibleScramblers =
        new LinkedHashMap<>();
    for (final Map.Entry<String, ScrambleRequest.ScrambleSource> e :
        b.possibleScramblers().entrySet()) {
      final Territory source = data.getMap().getTerritoryOrNull(e.getKey());
      if (source == null) {
        throw new IllegalArgumentException(
            "ScrambleRequest references unknown source territory: " + e.getKey());
      }
      final List<Unit> airbases = new ArrayList<>();
      for (final Unit u : source.getUnits()) {
        if (Matches.unitIsAirBase().test(u)) {
          airbases.add(u);
        }
      }
      if (airbases.isEmpty()) {
        // No airbase on the live source territory — ScrambleLogic.getMaxScrambleCount would
        // throw. Silently skip this source: it's a caller bug to ask for a scramble from a
        // territory with no airbase, but throwing here would break the whole decision when the
        // other sources are perfectly usable.
        continue;
      }
      final Map<UUID, Unit> onSource = indexById(source.getUnits());
      final List<Unit> canScrambleAir = new ArrayList<>(e.getValue().units().size());
      for (final WireUnit wu : e.getValue().units()) {
        final UUID uuid = idMap.get(wu.unitId());
        if (uuid == null) {
          throw new IllegalArgumentException(
              "ScrambleRequest references unknown unit id (not in WireState): " + wu.unitId());
        }
        final Unit live = onSource.get(uuid);
        if (live == null) {
          throw new IllegalArgumentException(
              "ScrambleRequest unit " + wu.unitId() + " is not on source territory " + source);
        }
        canScrambleAir.add(live);
      }
      possibleScramblers.put(source, Tuple.of(airbases, canScrambleAir));
    }

    if (possibleScramblers.isEmpty()) {
      // All wire-side sources were skipped (no airbase on the live source territory). Emit
      // the rationale line so triagers see "scramble queried but no live candidates".
      AiTraceLogger.logScrambleDecision(
          session.key().nation(),
          b.defendingTerritory(),
          /* candidates */ List.of(),
          /* picked */ List.of(),
          Map.of());
      return new ScramblePlan(Map.of());
    }

    // ProScrambleAi fetches the pending battle at scrambleTo via BattleTracker; synthesise one
    // so the lookup returns non-null. We pick an arbitrary non-session player as the synthetic
    // attacker (the "invader" trying to take the sea zone); ProScrambleAi only reads
    // getAttackingUnits / getDefendingUnits / getBombardingUnits off the battle, so the exact
    // GamePlayer identity is not load-bearing.
    final GamePlayer defender = requirePlayer(data, session.key().nation());
    final GamePlayer attacker =
        data.getPlayerList().getPlayers().stream()
            .filter(p -> !p.equals(defender) && !p.isNull())
            .findFirst()
            .orElse(defender);

    final List<Unit> battleAttackers = new ArrayList<>();
    final List<Unit> battleDefenders = new ArrayList<>();
    for (final Unit u : scrambleTo.getUnits()) {
      if (attacker.equals(u.getOwner())) {
        battleAttackers.add(u);
      } else {
        battleDefenders.add(u);
      }
    }

    ExecutorSupport.ensureProAiInitialized(session, defender);
    ExecutorSupport.ensureBattleDelegate(data);
    final BattleTracker tracker = data.getBattleDelegate().getBattleTracker();

    final UUID battleUuid =
        UUID.nameUUIDFromBytes(("scramble-" + b.defendingTerritory()).getBytes());
    final SyntheticBattle synthetic =
        new SyntheticBattle(
            battleUuid,
            scrambleTo,
            attacker,
            defender,
            battleAttackers,
            battleDefenders,
            /* amphibious */ false);
    ExecutorSupport.addPendingBattle(tracker, synthetic);

    final Map<Territory, Collection<Unit>> chosen;
    try {
      chosen = session.proAi().scrambleUnitsQuery(scrambleTo, possibleScramblers);
    } finally {
      ExecutorSupport.removePendingBattle(tracker, synthetic);
    }

    final Map<UUID, String> reverse = reverseIdMap(idMap);

    // Decision-rationale trace (#2104). One flat list each for candidates and picks across
    // all source territories — matches the helper's "aggregate" model. Emitted before the
    // wire-projection loop so it captures live Unit references.
    final List<Unit> candidatesFlat = new ArrayList<>();
    for (final Tuple<Collection<Unit>, Collection<Unit>> t : possibleScramblers.values()) {
      candidatesFlat.addAll(t.getSecond());
    }
    final List<Unit> pickedFlat = new ArrayList<>();
    if (chosen != null) {
      for (final Collection<Unit> v : chosen.values()) {
        pickedFlat.addAll(v);
      }
    }
    AiTraceLogger.logScrambleDecision(
        defender.getName(), b.defendingTerritory(), candidatesFlat, pickedFlat, reverse);

    if (chosen == null || chosen.isEmpty()) {
      return new ScramblePlan(Map.of());
    }

    final Map<String, List<String>> out = new LinkedHashMap<>();
    for (final Map.Entry<Territory, Collection<Unit>> e : chosen.entrySet()) {
      final List<String> ids = new ArrayList<>(e.getValue().size());
      for (final Unit u : e.getValue()) {
        final String mrId = reverse.get(u.getId());
        if (mrId == null) {
          throw new IllegalStateException(
              "ProAi returned scramble unit with UUID not in session idMap: " + u.getId());
        }
        ids.add(mrId);
      }
      out.put(e.getKey().getName(), ids);
    }
    return new ScramblePlan(out);
  }

  private static GamePlayer requirePlayer(final GameData data, final String name) {
    final GamePlayer p = data.getPlayerList().getPlayerId(name);
    if (p == null) {
      throw new IllegalArgumentException("Unknown player in ScrambleRequest session: " + name);
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

  private static Map<UUID, String> reverseIdMap(final ConcurrentMap<String, UUID> idMap) {
    final Map<UUID, String> out = new HashMap<>(idMap.size());
    for (final Map.Entry<String, UUID> e : idMap.entrySet()) {
      out.put(e.getValue(), e.getKey());
    }
    return out;
  }
}
