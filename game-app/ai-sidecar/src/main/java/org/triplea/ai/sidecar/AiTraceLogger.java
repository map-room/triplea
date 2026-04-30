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
