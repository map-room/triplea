package games.strategy.triplea.ai.pro.util;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.data.AiTheaterPriority;
import games.strategy.triplea.ai.pro.data.AiTheaterScopeMode;
import java.util.HashSet;
import java.util.Set;
import lombok.experimental.UtilityClass;

/**
 * Off-theater scope filter for the KGF/KJF SVF (map-room#2755 Phase 3C / PR-E).
 *
 * <p>The value-map blend can only re-rank candidates already in scope. Combat-move scope is
 * reachability-first: ProAi's attack options come from "what can my units hit this turn?". For a
 * Pacific-staged US, only Pacific targets are reachable — European candidates never enter the list,
 * so no {@code wStrat} value re-ranks them in. The scope filter is the missing structural lever: it
 * removes off-theater targets from the candidate set so the Pacific-commit feedback loop is broken
 * and the AI has nothing to do offensively in Pacific (defense in place via Phase 3A floor still
 * applies).
 *
 * <p>Three modes, controlled by {@link ProData#getAiTheaterScopeMode()}:
 *
 * <ul>
 *   <li>{@link AiTheaterScopeMode#OFF} — no filtering, backward-compatible default.
 *   <li>{@link AiTheaterScopeMode#STRICT} — off-theater attacks always blocked. Hardest "Germany
 *       first" interpretation; US never attacks Japanese axis even if Japan eats Pacific islands.
 *   <li>{@link AiTheaterScopeMode#ADAPTIVE} — off-theater attacks blocked UNLESS the threat ring
 *       trips. Any axis unit in (or owner of) a tripwire territory unlocks engagement: US can
 *       attack the tripped tripwires + their immediate neighbors, but not Japan home / Korea /
 *       Manchuria. Matches historical KGF doctrine.
 * </ul>
 *
 * <p>Spec §2 US-only scope: filter returns false for any non-Americans player so other AI is not
 * affected. Defaults to {@code OFF} so PR-E ships zero-impact.
 */
@UtilityClass
public final class ProTheaterScopeFilter {

  /** Same Pacific-axis name as {@code ProStrategicValueField} — keep the two in sync. */
  private static final String PACIFIC_AXIS_NAME = "Japanese";

  /**
   * Tripwire territories for {@link AiTheaterPriority#KGF}. If any of these is owned by — or has a
   * unit of — the Japanese axis, the ring is tripped and US may engage in Pacific (ADAPTIVE only).
   * Covers the US-Pacific approach corridor + ANZAC defensive ring. Hardcoded by name per spec §2
   * "Phase 1 KGF/KJF semantics live in tables."
   */
  private static final Set<String> KGF_TRIPWIRES =
      Set.of(
          "Aleutian Islands",
          "Midway",
          "Wake Island",
          "Marshall Islands",
          "Marianas",
          "Gilbert Islands",
          "Johnston Island",
          "Hawaiian Islands",
          "Western United States",
          "Alaska",
          "Solomon Islands",
          "New Britain",
          "Fiji",
          "New Hebrides",
          "Samoa",
          "Queensland",
          "New South Wales",
          "Western Australia",
          "Northern Territory",
          "New Zealand");

  /**
   * Tripwire territories for {@link AiTheaterPriority#KJF}. European axis approach corridor toward
   * US East Coast. Rarely trips in practice (German surface fleet doesn't reach the US East Coast
   * in 1940 Global) — included for symmetry.
   */
  private static final Set<String> KJF_TRIPWIRES =
      Set.of(
          "Iceland",
          "Greenland",
          "Newfoundland Labrador",
          "Eastern United States",
          "Central United States",
          "Brazil",
          "French Guiana");

  /**
   * Returns {@code true} if the off-theater scope filter should remove {@code target} from CM
   * attack consideration. Returns {@code false} for non-Americans (spec §2 US-only), when mode is
   * {@link AiTheaterScopeMode#OFF}, or when {@code target} is in the targeted theater.
   *
   * <p>Under {@link AiTheaterScopeMode#STRICT} every off-theater target is filtered. Under {@link
   * AiTheaterScopeMode#ADAPTIVE} an off-theater target is filtered unless it falls in the {@link
   * #getEngagementSet engagement set} of the currently tripped ring.
   */
  public static boolean shouldSkipAttackTarget(final ProData proData, final Territory target) {
    if (!isAmericans(proData)) {
      return false;
    }
    final AiTheaterScopeMode mode = proData.getAiTheaterScopeMode();
    if (mode == AiTheaterScopeMode.OFF) {
      return false;
    }
    if (!isOffTheater(target, proData.getAiTheaterPriority())) {
      return false;
    }
    if (mode == AiTheaterScopeMode.STRICT) {
      return true;
    }
    // ADAPTIVE: allow if target is in the engagement set of a tripped ring.
    return !getEngagementSet(proData).contains(target);
  }

  /**
   * Returns the set of off-theater territories the AI is currently allowed to engage in. Computed
   * from the tripped tripwires + their immediate neighbors so a Japan landing on Marshall Islands
   * unlocks US engagement of Marshalls + adjacent sea zones — but does not unlock Japan home /
   * Korea / Manchuria as side-effect attack scope.
   *
   * <p>Empty when mode is not {@link AiTheaterScopeMode#ADAPTIVE} or when no tripwire is currently
   * occupied by off-theater axis. Computed fresh each call — the sidecar is stateless per HTTP
   * request and there is no opportunity to cache across requests.
   */
  public static Set<Territory> getEngagementSet(final ProData proData) {
    if (proData.getAiTheaterScopeMode() != AiTheaterScopeMode.ADAPTIVE) {
      return Set.of();
    }
    final Set<Territory> trippedTripwires = getTrippedTripwires(proData);
    if (trippedTripwires.isEmpty()) {
      return Set.of();
    }
    final Set<Territory> engagement = new HashSet<>(trippedTripwires);
    final GameData data = proData.getData();
    for (final Territory t : trippedTripwires) {
      engagement.addAll(data.getMap().getNeighbors(t));
    }
    return engagement;
  }

  /**
   * Returns the tripwire territories currently occupied by — or owned by — off-theater axis. A
   * tripwire is "tripped" when the threat has crossed into the perimeter; the AI may then engage.
   * Visible for testing and logging.
   */
  static Set<Territory> getTrippedTripwires(final ProData proData) {
    final AiTheaterPriority flag = proData.getAiTheaterPriority();
    final Set<String> tripwireNames = flag == AiTheaterPriority.KJF ? KJF_TRIPWIRES : KGF_TRIPWIRES;
    final GameData data = proData.getData();
    final Set<Territory> tripped = new HashSet<>();
    for (final String name : tripwireNames) {
      final Territory t = data.getMap().getTerritoryOrNull(name);
      if (t == null) {
        continue;
      }
      if (hasOffTheaterAxis(t, flag, data)) {
        tripped.add(t);
      }
    }
    return tripped;
  }

  /**
   * Returns true if territory is owned by — or has any unit belonging to — an off-theater axis
   * power. Off-theater axis under KGF is the Japanese; under KJF is every axis power EXCEPT
   * Japanese.
   */
  private static boolean hasOffTheaterAxis(
      final Territory t, final AiTheaterPriority flag, final GameData data) {
    final boolean ownerIsOffTheater = isOffTheaterPlayer(t.getOwner(), flag);
    if (ownerIsOffTheater) {
      return true;
    }
    return t.getUnits().stream().anyMatch(u -> isOffTheaterPlayer(u.getOwner(), flag));
  }

  /**
   * Classifies a player as off-theater axis for the given priority flag. Under KGF, Japanese is
   * off-theater. Under KJF, every axis power that is NOT Japanese is off-theater (Germans,
   * Italians, etc.). True Neutrals and Allies are never off-theater axis.
   */
  private static boolean isOffTheaterPlayer(final GamePlayer player, final AiTheaterPriority flag) {
    if (player == null) {
      return false;
    }
    final boolean playerIsPacific = PACIFIC_AXIS_NAME.equals(player.getName());
    final boolean targetingPacific = flag == AiTheaterPriority.KJF;
    // Off-theater axis = the axis we are NOT targeting. Filter to actual axis players via the
    // simple Pacific/non-Pacific split kept consistent with ProStrategicValueField.
    if (!isAxisPlayer(player)) {
      return false;
    }
    return playerIsPacific != targetingPacific;
  }

  /**
   * Classifies a player as an axis combatant. Hardcoded to the 1940 Global roster — same approach
   * as {@link ProStrategicValueField}. The set is closed in this scenario; adding scenarios will
   * need its own theater map.
   */
  private static boolean isAxisPlayer(final GamePlayer player) {
    final String name = player.getName();
    return "Germans".equals(name) || "Italians".equals(name) || "Japanese".equals(name);
  }

  /** Classifies {@code target}'s owner as in the off-theater axis (mirror of the field code). */
  private static boolean isOffTheater(final Territory target, final AiTheaterPriority flag) {
    return isOffTheaterPlayer(target.getOwner(), flag);
  }

  private static boolean isAmericans(final ProData proData) {
    final GamePlayer player = proData.getPlayer();
    return player != null && "Americans".equals(player.getName());
  }

  /**
   * Pluggable smoke-test entrypoint used by callers that want to short-circuit the boilerplate.
   * Returns {@code true} if SVF is active (mode != OFF) for the AI player. Allows the wire site to
   * skip the loop entirely when filtering wouldn't apply.
   */
  public static boolean isFilterActive(final ProData proData) {
    return isAmericans(proData) && proData.getAiTheaterScopeMode() != AiTheaterScopeMode.OFF;
  }
}
