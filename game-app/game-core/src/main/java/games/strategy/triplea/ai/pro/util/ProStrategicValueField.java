package games.strategy.triplea.ai.pro.util;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.util.BreadthFirstSearch;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.data.AiTheaterPriority;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.Matches;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import lombok.experimental.UtilityClass;

/**
 * Strategic Value Field S(n) for the KGF/KJF theater pull (map-room#2755, Phase 1B+1C / PR-A).
 *
 * <p>Computes a per-territory potential field anchored on the enemy axis the {@link
 * AiTheaterPriority} flag targets. Each anchor (enemy capital, factory, victory city) projects a
 * geometric decay {@code G_o * gamma^d(n, o)} through the movement graph; the field at a node is
 * the {@code max} across all anchors. The off-theater axis contributes a suppressed signal ({@code
 * G_o * alphaOff}) so the unfavored theater isn't completely forgotten.
 *
 * <p><b>Full-board computation (§10 Finding 3):</b> {@link #compute} enumerates every {@link
 * Territory} in {@code data.getMap().getTerritories()} once and returns the full mapping. Callers
 * downstream of the blend look up {@code S[t]} for whatever subset they happen to be evaluating —
 * they never trigger a recompute.
 *
 * <p><b>Asymmetry warning (§10 Finding 1 / §5 risk register):</b> baseline {@code B(n)} is not flat
 * — it already slopes Pacific-ward for the US because Tokyo is reachable via the Alaska land path
 * while Berlin requires an Atlantic crossing. <b>KGF mode must overcome the existing gradient; KJF
 * mode reinforces it.</b> Phase 4A tuning will need to size {@code wStrat} (or {@code gCap})
 * per-flag — a symmetric default would under-pull KGF and over-pull KJF.
 *
 * <p>Phase 1B/1C (PR-A) ships this class with {@code wStrat=0.0} default so the field is
 * computed-and-dumpable but never blended. The blend lands in PR-B alongside wire + lobby UI.
 *
 * <p>Performance note: each call performs {@code O(|anchors| * (V + E))} where V = territories, E =
 * adjacencies. With ~5 anchors and ~120 territories the cost is negligible (single-digit ms). The
 * sidecar is stateless per HTTP request, so this work is re-done per phase call (3-4× per US turn)
 * — see plan §0 nuance.
 */
@UtilityClass
public final class ProStrategicValueField {

  /** Factory anchor strength as a fraction of {@code G_cap}. */
  private static final double G_FAC_FRACTION = 0.6;

  /** Victory-city anchor strength as a fraction of {@code G_cap}. */
  private static final double G_VC_FRACTION = 0.4;

  /**
   * Pacific axis is just Japan in the WW2 1940 Global scenario. Anything else in the enemy axis set
   * classifies as European theater. Data-driven via {@code GamePlayer.getName()} (no hardcoded
   * territory list) per spec §10 question 4.
   */
  private static final String PACIFIC_AXIS_NAME = "Japanese";

  /**
   * Computes the full-board strategic value field for {@code player} from {@code proData}.
   *
   * <p>Returns a map keyed by every {@link Territory} on the board (including water, including
   * impassable) — the per-call subset filtering lives at the consumer, not here.
   */
  public static Map<Territory, Double> compute(final ProData proData, final GamePlayer player) {
    final GameData data = proData.getData();
    final Map<Territory, Double> anchors = buildObjectives(proData, player);
    final BiPredicate<Territory, Territory> edgeCond = combinedMovementEdgeCond(player);

    final Map<Territory, Double> field = new HashMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      field.put(t, 0.0);
    }
    if (anchors.isEmpty()) {
      return field;
    }

    final double gamma = proData.getGamma();
    for (final Map.Entry<Territory, Double> entry : anchors.entrySet()) {
      final Territory anchor = entry.getKey();
      final double strength = entry.getValue();
      // BreadthFirstSearch.traverse only invokes the visitor on *neighbors* (distance >= 1) —
      // it never reports the start territory itself. Seed the anchor's own decay value (d=0,
      // i.e. strength * gamma^0 = strength) explicitly so S(anchor) lands at the full pull.
      final Double existingAtAnchor = field.get(anchor);
      if (existingAtAnchor == null || strength > existingAtAnchor) {
        field.put(anchor, strength);
      }
      final BreadthFirstSearch bfs = new BreadthFirstSearch(List.of(anchor), edgeCond);
      bfs.traverse(
          (territory, distance) -> {
            // Convert BFS edge distance to game-turn distance per spec §4 "movement-step
            // distance". Transport movement is 2 sea zones per turn, so divide by 2 to
            // approximate turns. Kept as a FLOAT (not integer floor): although a single
            // game turn is discrete, the SVF is a continuous value field used to compare
            // territory attractiveness — a 0.5-turn-closer territory IS strictly
            // preferable even when both round to the same integer "1 turn". Integer
            // truncation (early attempt at this fix) collapsed 2-3 BFS edges into the
            // same bucket and produced a visually-uniform heatmap with only ~12 distinct
            // values across the entire board; the float version gives every BFS edge a
            // distinct decay factor and substantially sharper AI preferences.
            //
            // For pure-land paths this over-credits infantry-paced advance (BFS 4 = 2
            // turns by formula vs 4 turns for infantry, 2 for armour). The SVF is
            // US-only post-gate (spec §2), and US always crosses water to reach Eurasian
            // targets, so naval-dominated routes dominate the math — the approximation
            // is biased correctly for the deployed use case. A future phase may refine
            // per-edge with a Dijkstra accumulator.
            final double effectiveTurns = distance / 2.0;
            final double decayed = strength * Math.pow(gamma, effectiveTurns);
            final Double existing = field.get(territory);
            if (existing == null || decayed > existing) {
              field.put(territory, decayed);
            }
            return true;
          });
    }
    // launchBonus is sea-only and bumps water nodes adjacent to high-S coastal targets.
    for (final Territory t : data.getMap().getTerritories()) {
      if (t.isWater()) {
        final double bonus = launchBonus(t, field, proData);
        if (bonus > 0) {
          field.merge(t, bonus, Double::sum);
        }
      }
    }
    return field;
  }

  /**
   * Returns the per-edge predicate for the combined land+sea movement graph. Per-power canal access
   * ({@code ProMatches.noCanalsBetweenTerritories}) is honored — US route to Tokyo via Suez is
   * closed for non-British powers; the BFS falls back through the Pacific.
   */
  private static BiPredicate<Territory, Territory> combinedMovementEdgeCond(
      final GamePlayer player) {
    return (from, to) -> {
      if (!ProMatches.noCanalsBetweenTerritories(player).test(from, to)) {
        return false;
      }
      return ProMatches.territoryCanPotentiallyMoveLandUnits(player).test(to)
          || ProMatches.territoryCanMoveSeaUnits(player, true).test(to);
    };
  }

  /**
   * Builds the anchor → strength map per spec §4 "Objectives O and the flag" pseudocode.
   *
   * <p>Dynamically derives anchors from live enemy capitals + enemy-owned factory territories +
   * victory cities owned by an enemy power (§10 question 4). Per-flag theater filter applies the
   * suppression multiplier {@code alphaOff} to off-theater anchors so the off-theater isn't fully
   * forgotten.
   */
  static Map<Territory, Double> buildObjectives(final ProData proData, final GamePlayer player) {
    final GameData data = proData.getData();
    final AiTheaterPriority flag = proData.getAiTheaterPriority();
    final double gCap = proData.getGCap();
    final double gFac = gCap * G_FAC_FRACTION;
    final double gVc = gCap * G_VC_FRACTION;
    final double alphaOff = proData.getAlphaOff();

    final Set<GamePlayer> enemies = new HashSet<>(ProUtils.getPotentialEnemyPlayers(player));
    if (enemies.isEmpty()) {
      return Map.of();
    }

    final Map<Territory, Double> anchors = new HashMap<>();

    // Capitals: live enemy capitals — strongest pull.
    for (final Territory cap : ProUtils.getLiveEnemyCapitals(data, player)) {
      final double weight = theaterWeight(cap.getOwner(), flag, alphaOff);
      mergeMax(anchors, cap, gCap * weight);
    }

    // Factories + victory cities: enumerate enemy-owned land territories once.
    for (final Territory t : data.getMap().getTerritories()) {
      if (t.isWater() || !enemies.contains(t.getOwner())) {
        continue;
      }
      final double weight = theaterWeight(t.getOwner(), flag, alphaOff);
      if (ProMatches.territoryHasInfraFactoryAndIsLand().test(t)) {
        mergeMax(anchors, t, gFac * weight);
      }
      if (TerritoryAttachment.get(t).map(ta -> ta.getVictoryCity() != 0).orElse(false)) {
        mergeMax(anchors, t, gVc * weight);
      }
    }

    return anchors;
  }

  /**
   * Launch bonus {@code L(n)} per spec §4: bumps a water node if any neighboring land territory is
   * a high-{@code S} targeted-theater objective worth assaulting. Cutoff defaults to 50 % of {@code
   * G_cap}.
   */
  static double launchBonus(
      final Territory seaZone, final Map<Territory, Double> field, final ProData proData) {
    if (!seaZone.isWater()) {
      return 0.0;
    }
    final double beta = proData.getBetaLaunch();
    if (beta <= 0.0) {
      return 0.0;
    }
    final double cutoff = proData.getGCap() * 0.5;
    final Set<Territory> neighbors =
        proData.getData().getMap().getNeighbors(seaZone, Matches.territoryIsLand());
    for (final Territory n : neighbors) {
      final Double v = field.get(n);
      if (v != null && v >= cutoff) {
        return beta;
      }
    }
    return 0.0;
  }

  /**
   * Returns {@code 1.0} for an anchor in the targeted theater, {@code alphaOff} otherwise. The
   * European-theater bucket is "every axis power that isn't {@value PACIFIC_AXIS_NAME}" — keeps the
   * classification data-driven and survives an Italian-only Mediterranean scenario.
   */
  private static double theaterWeight(
      final GamePlayer owner, final AiTheaterPriority flag, final double alphaOff) {
    final boolean ownerInPacific = PACIFIC_AXIS_NAME.equals(owner.getName());
    final boolean targetingPacific = flag == AiTheaterPriority.KJF;
    return ownerInPacific == targetingPacific ? 1.0 : alphaOff;
  }

  private static void mergeMax(final Map<Territory, Double> m, final Territory t, final double v) {
    m.merge(t, v, Math::max);
  }
}
