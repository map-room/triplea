package org.triplea.ai.sidecar;

import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Unit;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Emits single-line structured AI-TRACE log entries for sidecar executor decisions.
 *
 * <p>Format: {@code [AI-TRACE] matchID=<id> side=sidecar nation=X phase=Y key=value ...} Arrays use
 * {@code [a,b,c]} notation; unit counts use {@code type×N} notation; territory names containing
 * spaces are double-quoted.
 *
 * <p>The {@code matchID} tag is sourced from {@link #setMatchId(String)}, which the request
 * boundary in {@code DecisionHandler} sets before dispatching an executor and clears in a finally
 * block. The system uses a per-thread context (functionally an MDC slot for {@code
 * System.Logger}-based code) — every executor call site already runs on the HTTP request thread, so
 * a {@link ThreadLocal} carries the matchID through the executor stack without having to thread it
 * through every method signature.
 *
 * <p>If no matchID is set when an emit happens (e.g. tests, or a background path that never went
 * through {@code DecisionHandler}), the line is tagged {@code matchID=unknown} so the Loki query
 * {@code {matchID=~"unknown|..."} } still surfaces those lines for triage.
 */
public final class AiTraceLogger {

  private static final System.Logger LOG = System.getLogger(AiTraceLogger.class.getName());
  private static final String UNKNOWN_MATCH_ID = "unknown";
  private static final ThreadLocal<String> MATCH_ID = new ThreadLocal<>();

  private AiTraceLogger() {}

  /**
   * Bind the matchID for the current thread. Call at the request boundary (e.g. start of {@code
   * DecisionHandler.handle}). Pair with {@link #clearMatchId()} in a finally block so a thread-pool
   * worker doesn't leak a stale matchID into the next request.
   */
  public static void setMatchId(final String matchId) {
    MATCH_ID.set(matchId);
  }

  /** Clear the per-thread matchID. Always call from a finally block. */
  public static void clearMatchId() {
    MATCH_ID.remove();
  }

  /**
   * Read the per-thread matchID, falling back to {@code "unknown"} when no boundary set one. Public
   * so request-boundary tests can assert that {@code DecisionHandler} binds and clears the matchID
   * around executor dispatch.
   */
  public static String currentMatchId() {
    final String v = MATCH_ID.get();
    return v == null ? UNKNOWN_MATCH_ID : v;
  }

  /**
   * Log a captured move from CombatMoveExecutor or NoncombatMoveExecutor.
   *
   * @param uuidToWireId reverse map (Java UUID → Map Room wire ID) for transport resolution
   */
  public static void logCapturedMove(
      final String nation,
      final String phase,
      final MoveDescription move,
      final boolean isBombing,
      final Map<UUID, String> uuidToWireId) {
    final Collection<Unit> allUnits = move.getUnits();
    // Air units dispatched from carriers yield route.isUnload() == true on the TripleA side, but
    // the bot-worker translates them to plain moveUnit calls (not unloadFromTransport). Label the
    // sidecar trace as "air-move" so the sidecar → bot-worker correspondence reads cleanly.
    final boolean allAir =
        !allUnits.isEmpty() && allUnits.stream().allMatch(u -> u.getUnitAttachment().isAir());
    final String kind;
    if (isBombing) {
      kind = "sbr";
    } else if (move.getRoute().isLoad()) {
      kind = "load";
    } else if (move.getRoute().isUnload()) {
      kind = allAir ? "air-move" : "unload";
    } else {
      kind = "move";
    }
    final String from = maybeQuote(move.getRoute().getStart().getName());
    final String to = maybeQuote(move.getRoute().getEnd().getName());
    final String unitIds = unitWireIds(allUnits, uuidToWireId);
    final String types = unitTypeCounts(allUnits);
    final String transportId = resolveTransportId(move, uuidToWireId);
    LOG.log(
        System.Logger.Level.INFO,
        "[AI-TRACE] matchID={0} side=sidecar nation={1} phase={2} kind={3} from={4} to={5} unitIds=[{6}] types=[{7}] transportId={8}",
        currentMatchId(),
        nation,
        phase,
        kind,
        from,
        to,
        unitIds,
        types,
        transportId);
  }

  /** Log a single purchase order (call once per order in the trimmed plan). */
  public static void logPurchaseOrder(final String nation, final String unitType, final int count) {
    LOG.log(
        System.Logger.Level.INFO,
        "[AI-TRACE] matchID={0} side=sidecar nation={1} phase=purchase units=[{2}×{3}]",
        currentMatchId(),
        nation,
        unitType,
        count);
  }

  /** Log a single place order (call once per territory in the place plan). */
  public static void logPlaceOrder(
      final String nation, final String territory, final Collection<String> unitTypes) {
    LOG.log(
        System.Logger.Level.INFO,
        "[AI-TRACE] matchID={0} side=sidecar nation={1} phase=place territory={2} units=[{3}]",
        currentMatchId(),
        nation,
        maybeQuote(territory),
        stringTypeCounts(unitTypes));
  }

  /** Log a war declaration from PoliticsExecutor. */
  public static void logWarDeclaration(final String nation, final String target) {
    LOG.log(
        System.Logger.Level.INFO,
        "[AI-TRACE] matchID={0} side=sidecar nation={1} phase=politics target={2}",
        currentMatchId(),
        nation,
        target);
  }

  /**
   * Log the rationale for a casualty-selection decision (#2101).
   *
   * <p>Triagers chasing AI-stuck bugs (the #2057 / #2065 / #2066 class) need to see what the AI was
   * choosing between, what it picked, and whether it diverged from the client-supplied default.
   * ProAi's deeper reasoning (TUL ratio, cost math) lives inside {@code
   * AbstractProAi.selectCasualties} and would require its own instrumentation to surface — out of
   * scope for v1. The {@code reason} captured here is a coarse summary: {@code default-applied}
   * when ProAi accepted the client's default casualty set verbatim, otherwise {@code
   * overridden-from-default}. With {@code consideredIds} / {@code pickedIds} / {@code defaultIds}
   * the triager can manually reconstruct what changed.
   *
   * @param uuidToWireId reverse map (Java UUID → Map Room wire ID) for projecting Unit references
   *     back onto the wire identity space the bug-reporter sees.
   */
  public static void logCasualtyDecision(
      final String nation,
      final String battleId,
      final String territory,
      final int hitCount,
      final Collection<Unit> considered,
      final Collection<Unit> defaultCasualties,
      final Collection<Unit> picked,
      final Map<UUID, String> uuidToWireId) {
    LOG.log(
        System.Logger.Level.INFO,
        "[AI-TRACE] matchID={0} side=sidecar nation={1} phase=battle kind=select-casualties"
            + " battleId={2} territory={3} hitCount={4}"
            + " consideredIds=[{5}] consideredTypes=[{6}]"
            + " pickedIds=[{7}] pickedTypes=[{8}]"
            + " defaultIds=[{9}] reason={10}",
        currentMatchId(),
        nation,
        battleId,
        maybeQuote(territory),
        hitCount,
        unitWireIds(considered, uuidToWireId),
        unitTypeCounts(considered),
        unitWireIds(picked, uuidToWireId),
        unitTypeCounts(picked),
        unitWireIds(defaultCasualties, uuidToWireId),
        casualtyReason(picked, defaultCasualties));
  }

  /**
   * Log the rationale for a retreat-vs-press decision (#2103).
   *
   * <p>Captures the candidate retreat territories the AI was offered, where (if anywhere) it
   * decided to retreat, and a coarse {@code reason} tag: {@code no-options} when the candidate list
   * was empty (no decision was actually made), {@code press} when the AI chose to fight on, {@code
   * retreat} when it chose to retreat. ProAi's deeper rationale (per-territory TUL math,
   * defender-threat estimate) lives inside {@code ProRetreatAi} and is out of scope here.
   *
   * @param retreatTo the chosen retreat territory name, or {@code null} if the AI chose to press
   */
  public static void logRetreatDecision(
      final String nation,
      final String battleId,
      final String territory,
      final Collection<String> candidateTerritories,
      final String retreatTo) {
    LOG.log(
        System.Logger.Level.INFO,
        "[AI-TRACE] matchID={0} side=sidecar nation={1} phase=battle kind=retreat-decision"
            + " battleId={2} territory={3} candidates=[{4}] retreatTo={5} reason={6}",
        currentMatchId(),
        nation,
        battleId,
        maybeQuote(territory),
        territoryNamesList(candidateTerritories),
        retreatTo == null ? "null" : maybeQuote(retreatTo),
        retreatReason(candidateTerritories, retreatTo));
  }

  /**
   * Log the rationale for a scramble-selection decision (#2104).
   *
   * <p>Captures the flat list of scramble candidates ProScrambleAi was offered (across all source
   * territories) and what it picked, plus a coarse {@code reason} tag — {@code no-candidates} when
   * ProScrambleAi was never invoked (empty live candidate set), {@code none} / {@code partial} /
   * {@code all} based on pick-vs-candidate cardinality. The per-source breakdown is intentionally
   * flattened: the bug-stuck question is "why didn't the AI scramble", which only needs the
   * aggregate.
   */
  public static void logScrambleDecision(
      final String nation,
      final String territory,
      final Collection<Unit> candidates,
      final Collection<Unit> picked,
      final Map<UUID, String> uuidToWireId) {
    LOG.log(
        System.Logger.Level.INFO,
        "[AI-TRACE] matchID={0} side=sidecar nation={1} phase=battle kind=scramble-decision"
            + " territory={2}"
            + " candidatesIds=[{3}] candidatesTypes=[{4}]"
            + " pickedIds=[{5}] pickedTypes=[{6}]"
            + " reason={7}",
        currentMatchId(),
        nation,
        maybeQuote(territory),
        unitWireIds(candidates, uuidToWireId),
        unitTypeCounts(candidates),
        unitWireIds(picked, uuidToWireId),
        unitTypeCounts(picked),
        scrambleReason(candidates, picked));
  }

  /**
   * Log the rationale for an SBR interceptor-selection decision (#2105).
   *
   * <p>{@code InterceptExecutor} is currently a stub returning no interceptors — see the {@code
   * TODO} on that class. The rationale line still emits because triagers benefit from seeing
   * "intercept query reached the sidecar but the decision is not yet implemented" vs. silence. Once
   * ProAI integration lands, the call site can pass the real picked set + a non-stub reason (e.g.
   * {@code interceptor-favorable}) without changing this signature.
   *
   * <p>Wire-side identifiers are passed as parallel {@code candidateIds} / {@code candidateTypes}
   * lists rather than {@code Collection<Unit>} because the stub executor never resolves {@link
   * Unit} instances; keeping the helper string-typed avoids forcing the executor through an
   * unnecessary live-unit lookup just to log.
   *
   * @param reason caller-supplied — the InterceptExecutor passes {@code stub-not-implemented}
   *     today; the real heuristic moves in when ProAI integration replaces the stub.
   */
  public static void logSbrInterceptorDecision(
      final String nation,
      final String battleId,
      final String territory,
      final String attackerNation,
      final Collection<String> candidateIds,
      final Collection<String> candidateTypes,
      final Collection<String> pickedIds,
      final String reason) {
    LOG.log(
        System.Logger.Level.INFO,
        "[AI-TRACE] matchID={0} side=sidecar nation={1} phase=sbr kind=interceptor-decision"
            + " battleId={2} territory={3} attacker={4}"
            + " candidatesIds=[{5}] candidatesTypes=[{6}]"
            + " pickedIds=[{7}] reason={8}",
        currentMatchId(),
        nation,
        battleId,
        maybeQuote(territory),
        attackerNation,
        stringCommaJoin(candidateIds),
        stringTypeCounts(candidateTypes),
        stringCommaJoin(pickedIds),
        reason);
  }

  // --- package-private for tests ---

  /**
   * Coarse rationale tag for {@link #logCasualtyDecision}: {@code default-applied} when the AI's
   * pick is the same UUID set the client proposed as the default, else {@code
   * overridden-from-default}. Set comparison (not list comparison) — ProAi may reorder.
   */
  static String casualtyReason(
      final Collection<Unit> picked, final Collection<Unit> defaultCasualties) {
    if (picked.size() != defaultCasualties.size()) {
      return "overridden-from-default";
    }
    final Set<UUID> defaultIds = new HashSet<>(defaultCasualties.size());
    for (final Unit u : defaultCasualties) {
      defaultIds.add(u.getId());
    }
    for (final Unit u : picked) {
      if (!defaultIds.contains(u.getId())) {
        return "overridden-from-default";
      }
    }
    return "default-applied";
  }

  /**
   * Coarse rationale tag for {@link #logRetreatDecision}: {@code no-options} when ProRetreatAi was
   * never invoked (empty candidate list short-circuit), {@code press} when the AI chose to fight
   * (null retreatTo), {@code retreat} otherwise.
   */
  static String retreatReason(
      final Collection<String> candidateTerritories, final String retreatTo) {
    if (candidateTerritories.isEmpty()) {
      return "no-options";
    }
    return retreatTo == null ? "press" : "retreat";
  }

  /**
   * Coarse rationale tag for {@link #logScrambleDecision}: {@code no-candidates} when ProScrambleAi
   * was never invoked, otherwise the partition of picked-vs-candidate cardinality ({@code none} /
   * {@code partial} / {@code all}).
   */
  static String scrambleReason(final Collection<Unit> candidates, final Collection<Unit> picked) {
    if (candidates.isEmpty()) {
      return "no-candidates";
    }
    if (picked.isEmpty()) {
      return "none";
    }
    return picked.size() == candidates.size() ? "all" : "partial";
  }

  /**
   * Build a comma-separated list of Map Room wire IDs for each unit in the given collection, in
   * iteration order. Falls back to {@code uuid:<java-uuid>} when a unit is not registered in the
   * reverse map (should not happen in live dispatch but keeps the log non-empty if it does).
   */
  static String unitWireIds(final Collection<Unit> units, final Map<UUID, String> uuidToWireId) {
    if (units.isEmpty()) {
      return "";
    }
    final StringBuilder sb = new StringBuilder();
    for (final Unit u : units) {
      if (sb.length() > 0) {
        sb.append(',');
      }
      final String wireId = uuidToWireId.get(u.getId());
      sb.append(wireId != null ? wireId : "uuid:" + u.getId());
    }
    return sb.toString();
  }

  /**
   * Comma-join territory names, double-quoting any name containing a space. Used for
   * retreat-decision candidate lists where territory names like {@code "Western Europe"} need to
   * stay parseable inside a {@code [a,b,c]} bracket form.
   */
  static String territoryNamesList(final Collection<String> names) {
    if (names.isEmpty()) {
      return "";
    }
    final StringBuilder sb = new StringBuilder();
    for (final String n : names) {
      if (sb.length() > 0) {
        sb.append(',');
      }
      sb.append(maybeQuote(n));
    }
    return sb.toString();
  }

  /** Plain comma-join for already-string identifier lists (e.g. wire unit ids). */
  static String stringCommaJoin(final Collection<String> values) {
    if (values.isEmpty()) {
      return "";
    }
    final StringBuilder sb = new StringBuilder();
    for (final String v : values) {
      if (sb.length() > 0) {
        sb.append(',');
      }
      sb.append(v);
    }
    return sb.toString();
  }

  static String unitTypeCounts(final Collection<Unit> units) {
    final Map<String, Integer> counts = new LinkedHashMap<>();
    for (final Unit u : units) {
      counts.merge(u.getType().getName(), 1, Integer::sum);
    }
    return buildCountString(counts);
  }

  static String stringTypeCounts(final Collection<String> types) {
    final Map<String, Integer> counts = new LinkedHashMap<>();
    for (final String t : types) {
      counts.merge(t, 1, Integer::sum);
    }
    return buildCountString(counts);
  }

  private static String buildCountString(final Map<String, Integer> counts) {
    if (counts.isEmpty()) {
      return "";
    }
    final StringBuilder sb = new StringBuilder();
    counts.forEach(
        (type, n) -> {
          if (sb.length() > 0) {
            sb.append(',');
          }
          sb.append(type).append('×').append(n);
        });
    return sb.toString();
  }

  private static String resolveTransportId(
      final MoveDescription move, final Map<UUID, String> uuidToWireId) {
    if (move.getRoute().isLoad()) {
      return move.getUnitsToSeaTransports().values().stream()
          .findFirst()
          .map(t -> uuidToWireId.get(t.getId()))
          .map(id -> id == null ? "null" : id)
          .orElse("null");
    }
    if (move.getRoute().isUnload()) {
      return move.getUnits().stream()
          .filter(u -> u.getUnitAttachment().getTransportCapacity() > 0)
          .map(u -> uuidToWireId.get(u.getId()))
          .filter(id -> id != null)
          .findFirst()
          .orElse("null");
    }
    return "null";
  }

  private static String maybeQuote(final String s) {
    return s != null && s.contains(" ") ? '"' + s + '"' : s;
  }
}
