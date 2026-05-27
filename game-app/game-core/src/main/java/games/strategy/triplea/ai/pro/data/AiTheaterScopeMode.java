package games.strategy.triplea.ai.pro.data;

/**
 * Off-theater scope filter mode for the KGF/KJF SVF (map-room#2755 Phase 3C / PR-E).
 *
 * <p>Controls how aggressively ProAi excludes off-theater targets from CM attack options and NCM
 * defend options. The default is {@link #OFF} so PR-E is backward-compatible — no behavior change
 * until the user opts in via {@code AI_THEATER_SCOPE_MODE} or wire field.
 *
 * <p>Rationale: the value-map blend can only re-rank candidates already in scope. Combat-move scope
 * is reachability-first, so European candidates never enter scope for a Pacific-staged US — the
 * blend has nothing to act on. The scope filter is the missing structural lever: it removes
 * off-theater targets from the candidate set entirely.
 */
public enum AiTheaterScopeMode {
  /** No filtering — backward-compatible default. SVF blend operates on its own. */
  OFF,

  /**
   * Off-theater offense always blocked for the AI player. Strictest interpretation of the theater
   * priority — under KGF the US never attacks Japanese-axis targets, period. Suitable when the user
   * wants a hard "Germany first" simulation even at the cost of letting Japan eat Pacific islands.
   */
  STRICT,

  /**
   * Off-theater offense blocked UNLESS the threat ring trips — any off-theater axis unit in or
   * occupying a tripwire territory unlocks engagement reach. Matches historical KGF doctrine: "hold
   * Pacific minimally, throw everything at Germany — but counter if Japan crosses into the
   * defensive perimeter." See {@code ProTheaterScopeFilter} for tripwire definitions.
   */
  ADAPTIVE
}
