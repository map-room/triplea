package games.strategy.triplea.ai.pro.util;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.data.AiTheaterPriority;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.experimental.UtilityClass;

/**
 * Off-theater defense floor for the SVF (map-room#2755 Phase 3A).
 *
 * <p>The strategic value field pulls US offensive force toward the targeted theater, but a strong
 * Berlin gradient under KGF will strip Pacific garrisons (Hawaii, US West Coast, ANZAC core) to
 * feed Europe — handing those territories to Japan. The defense floor is a second lever: enforce a
 * minimum garrison {@code D_floor(k)} on key off-theater holdings that the NCM "where to defend"
 * allocator may not pull below. Spec §4 "Auxiliary behaviors the value map cannot express (separate
 * hooks)".
 *
 * <p>Phase 1 scope: US-only (spec §2). Other players get an empty floor map and their NCM is
 * unaffected. The floor populates per request from the {@link ProData#getAiTheaterPriority()} flag
 * — KGF puts the floor on the Pacific holdings, KJF mirrors it onto Atlantic holdings.
 *
 * <p>Initial garrison values are placeholders per spec §7 — Phase 4A tuning may refine them against
 * AI-vs-AI win-rate sweeps. The set of guarded territories is small and intentional: just the
 * anchor positions whose loss would visibly collapse the unfavored theater.
 */
@UtilityClass
public final class ProDefenseFloor {

  /**
   * Off-theater garrison floors when {@code aiTheaterPriority == KGF}. Pacific holdings the US must
   * NOT strip to feed Europe. Hawaii is the headline case — without a floor, US tends to empty it
   * once Berlin's gradient is meaningful, and Japan invades in 2-3 turns.
   */
  private static final Map<String, Integer> KGF_FLOORS =
      Map.of(
          "Hawaiian Islands", 2,
          "Western United States", 2,
          "Queensland", 1,
          "New South Wales", 1);

  /**
   * Off-theater garrison floors when {@code aiTheaterPriority == KJF}. Atlantic holdings the US
   * must NOT strip to feed the Pacific.
   */
  private static final Map<String, Integer> KJF_FLOORS =
      Map.of(
          "Eastern United States", 2,
          "Central United States", 1);

  /**
   * Builds the {@code Territory → garrison-floor} map for the player. Returns an empty map for any
   * non-Americans player — Phase 1 spec §2 scopes the SVF to US-only. Caller stores the result on
   * {@link ProData#setDFloor}.
   */
  public static Map<Territory, Integer> compute(final ProData proData, final GamePlayer player) {
    if (player == null || !"Americans".equals(player.getName())) {
      return Map.of();
    }
    final AiTheaterPriority flag = proData.getAiTheaterPriority();
    final Map<String, Integer> floorByName =
        flag == AiTheaterPriority.KJF ? KJF_FLOORS : KGF_FLOORS;
    final GameData data = proData.getData();
    final Map<Territory, Integer> floor = new HashMap<>();
    for (final Map.Entry<String, Integer> entry : floorByName.entrySet()) {
      final Territory t = data.getMap().getTerritoryOrNull(entry.getKey());
      if (t != null) {
        floor.put(t, entry.getValue());
      }
    }
    return floor;
  }

  /**
   * Returns {@code true} if pulling {@code unit} out of {@code source} would drop the source's
   * player-owned garrison below the configured floor. Returns {@code false} when no floor is
   * configured for the source territory, or when the source isn't owned by the player making the
   * move (defending units somewhere they don't own get no protection).
   *
   * @param alreadyAssigned units the NCM allocator has already committed to move OUT of {@code
   *     source} this pass — counted as already-pulled when computing remaining garrison
   */
  public static boolean wouldViolateFloor(
      final ProData proData,
      final @Nullable Territory source,
      final Unit unit,
      final Collection<Unit> alreadyAssigned) {
    if (source == null) {
      return false;
    }
    final int floor = proData.getDFloor().getOrDefault(source, 0);
    if (floor <= 0) {
      return false;
    }
    final GamePlayer player = proData.getPlayer();
    if (player == null || !source.isOwnedBy(player)) {
      return false;
    }
    long remainingAtSource = 0;
    for (final Unit u : source.getUnits()) {
      if (!u.getOwner().equals(player)) {
        continue;
      }
      if (u.equals(unit)) {
        continue; // about-to-be-pulled
      }
      if (alreadyAssigned.contains(u)) {
        continue; // already pulled this NCM pass
      }
      remainingAtSource++;
    }
    return remainingAtSource < floor;
  }
}
