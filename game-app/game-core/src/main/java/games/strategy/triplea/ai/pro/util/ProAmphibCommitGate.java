package games.strategy.triplea.ai.pro.util;

import games.strategy.engine.data.Territory;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.data.AiTheaterPriority;
import games.strategy.triplea.ai.pro.data.ProBattleResult;
import lombok.experimental.UtilityClass;

/**
 * Amphib commit gate for the SVF (map-room#2755 Phase 3B).
 *
 * <p>The strategic value field can make a beachhead look very attractive on paper. Without a commit
 * gate ProAi will gladly drop two infantry on a 6-defender shoreline and lose the lot — exactly the
 * "dribble into the meat grinder" failure mode spec §5 risk register predicted for unconstrained
 * {@code wStrat}.
 *
 * <p>Two checks live here:
 *
 * <ol>
 *   <li><b>Value gate:</b> {@code value(t) * pWin >= tCommit}. {@code tCommit} comes from {@link
 *       ProData#getTCommitTargeted()} for targeted-theater beaches, {@link
 *       ProData#getTCommitOffTheater()} otherwise. Default thresholds are {@code 0.0} so the gate
 *       is a no-op at the Phase 1 ship; Phase 4A tuning raises them.
 *   <li><b>Mass gate:</b> a hard win-percentage floor under which no commit happens regardless of
 *       value. Holds the transport for next turn instead of dribbling. Default floor is {@code 0.0}
 *       so this is also no-op at default; tuning can raise it (~0.55-0.70 per spec §"hard sanity
 *       floor").
 * </ol>
 *
 * <p>Spec §2 Phase 1 scopes SVF behavior to US-only — {@link #shouldCommit} returns true for any
 * non-Americans player so their amphib commits are unaffected. Defaults are zero-impact, so a user
 * who hasn't enabled the theater flag also sees no change.
 *
 * <p>"Should we commit" is the only judgment exposed; the caller decides what to do on a false
 * (typically: skip {@code putAmphibAttackMap}, and add the transport to {@link
 * ProData#getTransportsToHold()} so NCM keeps it at its sea zone).
 */
@UtilityClass
public final class ProAmphibCommitGate {

  /**
   * Pacific axis is just Japan in the WW2 1940 Global scenario — mirrors {@link
   * ProStrategicValueField}'s classifier so the gate and the field agree on which beaches are
   * "in-theater."
   */
  private static final String PACIFIC_AXIS_NAME = "Japanese";

  /** Hard mass-gate win-percentage floor — Phase 4A tuning raises above 0. */
  private static final double MASS_GATE_MIN_WIN_PCT = 0.0;

  /**
   * Returns {@code true} if the amphib commit at {@code target} should proceed. {@code false} means
   * the caller should hold the transport. Always {@code true} for non-Americans (spec §2).
   *
   * @param target the prospective amphib target territory (its enemy ownership feeds the theater
   *     classifier)
   * @param territoryValue the value of {@code target} per the active value map (post-SVF blend if
   *     {@code wStrat > 0}; raw baseline otherwise)
   * @param result the pre-commit battle estimate for {@code target}
   */
  public static boolean shouldCommit(
      final ProData proData,
      final Territory target,
      final double territoryValue,
      final ProBattleResult result) {
    if (proData.getPlayer() == null || !"Americans".equals(proData.getPlayer().getName())) {
      return true; // spec §2: SVF is US-only in Phase 1
    }
    if (result == null) {
      return true; // no estimate available; fall back to legacy commit
    }
    final double pWin = result.getWinPercentage() / 100.0;
    if (pWin < MASS_GATE_MIN_WIN_PCT) {
      return false; // hard sanity floor
    }
    final double tCommit = pickTCommit(proData, target);
    if (tCommit <= 0.0) {
      return true; // zero threshold = gate off
    }
    final double commitScore = territoryValue * pWin;
    return commitScore >= tCommit;
  }

  /**
   * Returns the threshold to test against for {@code target}. Targeted-theater targets get {@link
   * ProData#getTCommitTargeted()} — typically a *lower* bar because the goal is to throw force at
   * Berlin/Tokyo. Off-theater targets get {@link ProData#getTCommitOffTheater()} — typically a
   * higher bar so the AI doesn't dabble in non-decisive theaters.
   */
  private static double pickTCommit(final ProData proData, final Territory target) {
    return inTargetedTheater(proData.getAiTheaterPriority(), target)
        ? proData.getTCommitTargeted()
        : proData.getTCommitOffTheater();
  }

  /**
   * Classifies {@code target} as in/out of the targeted theater by its current owner's name. KGF
   * targets non-Japanese axis (Germans, Italians, etc.); KJF targets Japanese.
   */
  private static boolean inTargetedTheater(final AiTheaterPriority flag, final Territory target) {
    final boolean ownerIsPacific = PACIFIC_AXIS_NAME.equals(target.getOwner().getName());
    final boolean targetingPacific = flag == AiTheaterPriority.KJF;
    return ownerIsPacific == targetingPacific;
  }
}
