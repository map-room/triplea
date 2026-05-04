package org.triplea.ai.sidecar.exec;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.data.ProBattleResult;
import games.strategy.triplea.ai.pro.util.ProBattleUtils;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import org.triplea.ai.sidecar.AiTraceLogger;
import org.triplea.ai.sidecar.dto.InterceptPlan;
import org.triplea.ai.sidecar.dto.InterceptRequest;
import org.triplea.ai.sidecar.session.Session;
import org.triplea.ai.sidecar.wire.WireStateApplier;

/**
 * Executes an {@code intercept} decision by choosing which pending interceptors to commit to an SBR
 * air battle using ProAi's TUV-swing analysis.
 *
 * <p>The approach parallels {@link ScrambleExecutor} (which uses {@code ProScrambleAi}) but is
 * self-contained because TripleA's {@code AbstractProAi} does not override {@code
 * selectUnitsQuery()} — there is no existing ProAi entry point for SBR interception. Instead, this
 * executor drives {@link ProOddsCalculator} directly:
 *
 * <ol>
 *   <li>Simulate the air battle with <em>no</em> interceptors (baseline TUV swing).
 *   <li>Simulate with <em>all</em> interceptors. If the outcome is no better than baseline, skip.
 *   <li>Add interceptors one at a time (strongest first) until the defender is winning comfortably
 *       or we run out of candidates. Return the minimum set that achieves a good outcome.
 * </ol>
 *
 * <h2>Why the simulation uses land-battle combat values</h2>
 *
 * <p>{@code ProOddsCalculator.calculateBattleResults} calls TripleA's standard battle simulator,
 * which uses regular attack/defense values (e.g. bomber att=4, fighter def=4), not the air-battle
 * reduced values (bomber att=1, fighter def=2) that the actual SBR intercept uses. The TUV
 * comparison is still directionally correct: fighters dominate bombers under both value sets, so
 * the analysis reliably identifies when interception is worthwhile.
 *
 * <h2>Baseline ProBattleResult sentinel</h2>
 *
 * <p>With no interceptors the territory is land attacked only by air units; {@code
 * ProOddsCalculator} short-circuits and returns {@code ProBattleResult(tuvSwing=-1)} — a sentinel
 * meaning "no real combat, attacker gains nothing". Any simulation with fighters present produces a
 * more-negative TUV swing (defender kills expensive bombers), so the comparison reliably detects
 * improvement.
 */
public final class InterceptExecutor implements DecisionExecutor<InterceptRequest, InterceptPlan> {

  @Override
  public InterceptPlan execute(final Session session, final InterceptRequest request) {
    final GameData data = session.gameData();
    final ConcurrentMap<String, UUID> idMap = session.unitIdMap();

    WireStateApplier.apply(data, request.state(), idMap);

    final InterceptRequest.InterceptBattle b = request.battle();

    // Build candidate ID lists for trace logging (always emitted, even on short-circuit).
    final List<String> candidateIds = new ArrayList<>(b.pendingInterceptors().size());
    final List<String> candidateTypes = new ArrayList<>(b.pendingInterceptors().size());
    for (final InterceptRequest.PendingInterceptor pi : b.pendingInterceptors()) {
      candidateIds.add(pi.unit().unitId());
      candidateTypes.add(pi.unit().unitType());
    }

    if (b.pendingInterceptors().isEmpty()) {
      AiTraceLogger.logSbrInterceptorDecision(
          b.defenderNation(),
          b.battleId(),
          b.territory(),
          b.attackerNation(),
          List.of(),
          List.of(),
          List.of(),
          "no-candidates");
      return new InterceptPlan(List.of());
    }

    final Territory territory = requireTerritory(data, b.territory());

    // Collect attacker's bombers from the live territory after wire-state application.
    final GamePlayer attacker = requirePlayer(data, b.attackerNation());
    final List<Unit> bombers = new ArrayList<>();
    for (final Unit u : territory.getUnits()) {
      if (attacker.equals(u.getOwner())) {
        bombers.add(u);
      }
    }

    if (bombers.isEmpty()) {
      AiTraceLogger.logSbrInterceptorDecision(
          b.defenderNation(),
          b.battleId(),
          b.territory(),
          b.attackerNation(),
          candidateIds,
          candidateTypes,
          List.of(),
          "no-bombers-in-territory");
      return new InterceptPlan(List.of());
    }

    // Resolve wire interceptors to live Unit objects on their source territories.
    final List<Unit> candidates = resolveCandidates(data, idMap, b.pendingInterceptors());

    if (candidates.isEmpty()) {
      AiTraceLogger.logSbrInterceptorDecision(
          b.defenderNation(),
          b.battleId(),
          b.territory(),
          b.attackerNation(),
          candidateIds,
          candidateTypes,
          List.of(),
          "no-live-candidates");
      return new InterceptPlan(List.of());
    }

    final GamePlayer defender = requirePlayer(data, session.key().nation());
    ExecutorSupport.ensureProAiInitialized(session, defender);
    ExecutorSupport.ensureBattleDelegate(data);
    // Re-bind ProData to the live session GameData. AbstractProAi.scrambleUnitsQuery() calls
    // initializeData() implicitly, but here we call ProOddsCalculator directly, so we must
    // trigger the same re-bind to avoid proData.getData() returning null or a stale copy.
    session.proAi().reinitializeProDataForSidecar();

    final ProOddsCalculator calc = session.proAi().getCalc();
    final ProData proData = session.proAi().getProData();

    // Baseline: no interceptors. ProOddsCalculator returns ProBattleResult(tuvSwing=-1) for
    // all-air attacker vs no defenders on a land territory.
    final ProBattleResult baseline =
        calc.calculateBattleResults(proData, territory, bombers, List.of(), List.of());

    // Check if any interception helps at all (all interceptors vs none).
    final ProBattleResult allResult =
        calc.calculateBattleResults(proData, territory, bombers, candidates, List.of());

    if (allResult.getTuvSwing() >= baseline.getTuvSwing()) {
      AiTraceLogger.logSbrInterceptorDecision(
          b.defenderNation(),
          b.battleId(),
          b.territory(),
          b.attackerNation(),
          candidateIds,
          candidateTypes,
          List.of(),
          "intercept-not-favorable");
      return new InterceptPlan(List.of());
    }

    // Add interceptors one at a time (strongest first), stopping when the defense is already
    // winning comfortably — matching ProScrambleAi's greedy-until-sufficient termination.
    final List<Unit> sorted = new ArrayList<>(candidates);
    sorted.sort(
        Comparator.comparingDouble(
                (Unit u) ->
                    ProBattleUtils.estimateStrength(territory, List.of(u), List.of(), false))
            .reversed());

    final double minWinPct = proData.getMinWinPercentage();
    final List<Unit> chosen = new ArrayList<>();
    ProBattleResult current = baseline;
    for (final Unit u : sorted) {
      chosen.add(u);
      current = calc.calculateBattleResults(proData, territory, bombers, chosen, List.of());
      if (current.getTuvSwing() <= 0 && current.getWinPercentage() < (100 - minWinPct)) {
        break;
      }
    }

    // If the incremental result is still no better than baseline, fall back to all interceptors
    // (we already confirmed allResult < baseline above, so this is always better than none).
    if (current.getTuvSwing() >= baseline.getTuvSwing()) {
      chosen.clear();
      chosen.addAll(candidates);
    }

    final Map<UUID, String> reverse = reverseIdMap(idMap);
    final List<String> pickedIds = new ArrayList<>(chosen.size());
    for (final Unit u : chosen) {
      final String mrId = reverse.get(u.getId());
      if (mrId != null) {
        pickedIds.add(mrId);
      }
    }

    AiTraceLogger.logSbrInterceptorDecision(
        b.defenderNation(),
        b.battleId(),
        b.territory(),
        b.attackerNation(),
        candidateIds,
        candidateTypes,
        pickedIds,
        "tuv-swing");
    return new InterceptPlan(pickedIds);
  }

  private static Territory requireTerritory(final GameData data, final String name) {
    final Territory t = data.getMap().getTerritoryOrNull(name);
    if (t == null) {
      throw new IllegalArgumentException("Unknown territory in InterceptRequest: " + name);
    }
    return t;
  }

  private static GamePlayer requirePlayer(final GameData data, final String name) {
    final GamePlayer p = data.getPlayerList().getPlayerId(name);
    if (p == null) {
      throw new IllegalArgumentException("Unknown player in InterceptRequest: " + name);
    }
    return p;
  }

  private static List<Unit> resolveCandidates(
      final GameData data,
      final ConcurrentMap<String, UUID> idMap,
      final Collection<InterceptRequest.PendingInterceptor> pendingInterceptors) {
    final List<Unit> out = new ArrayList<>();
    for (final InterceptRequest.PendingInterceptor pi : pendingInterceptors) {
      final UUID uuid = idMap.get(pi.unit().unitId());
      if (uuid == null) {
        continue;
      }
      final Territory source = data.getMap().getTerritoryOrNull(pi.fromTerritory());
      if (source == null) {
        continue;
      }
      for (final Unit u : source.getUnits()) {
        if (u.getId().equals(uuid)) {
          out.add(u);
          break;
        }
      }
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
