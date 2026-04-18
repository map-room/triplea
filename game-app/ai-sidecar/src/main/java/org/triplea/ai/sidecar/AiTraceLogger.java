package org.triplea.ai.sidecar;

import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Unit;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Emits single-line structured AI-TRACE log entries for sidecar executor decisions.
 *
 * <p>Format: {@code [AI-TRACE] side=sidecar nation=X phase=Y key=value ...} Arrays use {@code
 * [a,b,c]} notation; unit counts use {@code type×N} notation; territory names containing spaces are
 * double-quoted.
 */
public final class AiTraceLogger {

  private static final System.Logger LOG = System.getLogger(AiTraceLogger.class.getName());

  private AiTraceLogger() {}

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
    final String kind =
        isBombing
            ? "sbr"
            : move.getRoute().isLoad() ? "load" : move.getRoute().isUnload() ? "unload" : "move";
    final String from = maybeQuote(move.getRoute().getStart().getName());
    final String to = maybeQuote(move.getRoute().getEnd().getName());
    final String units = unitTypeCounts(move.getUnits());
    final String transportId = resolveTransportId(move, uuidToWireId);
    LOG.log(
        System.Logger.Level.INFO,
        "[AI-TRACE] side=sidecar nation={0} phase={1} kind={2} from={3} to={4} units=[{5}] transportId={6}",
        nation,
        phase,
        kind,
        from,
        to,
        units,
        transportId);
  }

  /** Log a single purchase order (call once per order in the trimmed plan). */
  public static void logPurchaseOrder(final String nation, final String unitType, final int count) {
    LOG.log(
        System.Logger.Level.INFO,
        "[AI-TRACE] side=sidecar nation={0} phase=purchase units=[{1}×{2}]",
        nation,
        unitType,
        count);
  }

  /** Log a single place order (call once per territory in the place plan). */
  public static void logPlaceOrder(
      final String nation, final String territory, final Collection<String> unitTypes) {
    LOG.log(
        System.Logger.Level.INFO,
        "[AI-TRACE] side=sidecar nation={0} phase=place territory={1} units=[{2}]",
        nation,
        maybeQuote(territory),
        stringTypeCounts(unitTypes));
  }

  /** Log a war declaration from PoliticsExecutor. */
  public static void logWarDeclaration(final String nation, final String target) {
    LOG.log(
        System.Logger.Level.INFO,
        "[AI-TRACE] side=sidecar nation={0} phase=politics target={1}",
        nation,
        target);
  }

  // --- package-private for tests ---

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
