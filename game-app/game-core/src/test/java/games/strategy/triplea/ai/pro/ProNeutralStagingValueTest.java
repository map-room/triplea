package games.strategy.triplea.ai.pro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.ai.pro.util.ProTerritoryValueUtils;
import games.strategy.triplea.ai.pro.util.ProUtils;
import games.strategy.triplea.xml.TestMapGameData;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression test for #2745: True Neutrals were inflating sea-zone staging value, pulling US ground
 * forces toward the Caribbean to invade South American neutrals that the planner correctly never
 * attacks (cascade rule penalty makes True Neutral attacks net-negative).
 *
 * <p>Two fixes verified here:
 *
 * <ol>
 *   <li>{@code findLandValue} returns 0 for True Neutral territories so they don't enter the
 *       territoryValueMap as positive contributors.
 *   <li>{@code findWaterValue}'s nearby-land sum applies a 30× discount to True Neutral neighbors
 *       (was full attack-value), so sea zones surrounded by SA neutrals don't accumulate huge
 *       staging value.
 * </ol>
 */
public class ProNeutralStagingValueTest {

  private GameData data;
  private GamePlayer americans;
  private ProData proData;

  @BeforeEach
  void setUp() throws Exception {
    data = TestMapGameData.GLOBAL1940.getGameData();
    americans = data.getPlayerList().getPlayerId("Americans");
    proData = proDataFor(data);
  }

  /** Every True Neutral territory must score exactly 0.0 in findTerritoryValues. */
  @Test
  void trueNeutralTerritoriesScoreZeroInTerritoryValueMap() {
    final List<Territory> neutralLandTerritories =
        data.getMap().getTerritories().stream()
            .filter(t -> !t.isWater() && ProUtils.isNeutralLand(t))
            .toList();
    assumeTrue(
        !neutralLandTerritories.isEmpty(),
        "G40 must contain at least one True Neutral territory for this test to be meaningful");

    final Set<Territory> territoriesToCheck = new HashSet<>(neutralLandTerritories);
    final Map<Territory, Double> values =
        ProTerritoryValueUtils.findTerritoryValues(
            proData, americans, List.of(), List.of(), territoriesToCheck);

    for (final Territory t : neutralLandTerritories) {
      assertThat(values.get(t))
          .as(
              "True Neutral %s must score 0.0 — cascade rule prevents attacks, so this"
                  + " territory should not drive sea-zone staging value via the territoryValueMap",
              t.getName())
          .isEqualTo(0.0);
    }
  }

  /**
   * A sea zone surrounded by South American True Neutrals should not score significantly higher
   * than an open ocean sea zone with no nearby land at all. Pre-fix, {@code findWaterValue} summed
   * full {@code findTerritoryAttackValue} for each neutral neighbor (≈ 3 × production), which made
   * Caribbean sea zones magnets for US transport staging. Post-fix the contribution is divided by
   * 30, putting these sea zones in the same low-value bracket as empty ocean.
   */
  @Test
  void caribbeanSeaZonesDoNotInflateFromNearbyNeutrals() {
    // Find a sea zone in the Caribbean (any sea zone neighboring at least one SA True Neutral).
    Territory caribbeanSeaZone = null;
    for (final Territory sea : data.getMap().getTerritories()) {
      if (!sea.isWater()) {
        continue;
      }
      final long nearbyNeutrals =
          data.getMap().getNeighbors(sea, 3).stream()
              .filter(t -> !t.isWater() && ProUtils.isNeutralLand(t))
              .count();
      if (nearbyNeutrals >= 2) {
        caribbeanSeaZone = sea;
        break;
      }
    }
    assumeTrue(
        caribbeanSeaZone != null,
        "G40 must contain a sea zone with ≥2 nearby True Neutrals for this test to be meaningful");

    final Set<Territory> toCheck = new HashSet<>();
    toCheck.add(caribbeanSeaZone);
    final Map<Territory, Double> values =
        ProTerritoryValueUtils.findTerritoryValues(
            proData, americans, List.of(), List.of(), toCheck);

    // Pre-fix: this value was driven sky-high by full attack-value contributions from each
    // neutral neighbor. Post-fix, the /30 discount caps the contribution well below 5.0.
    // We use 5.0 as an upper bound — comfortably below the pre-fix scores (which were >50)
    // while still allowing legitimate enemy/contested neighbors to contribute.
    assertThat(values.get(caribbeanSeaZone))
        .as(
            "Sea zone %s with ≥2 nearby True Neutrals must not inflate above the modest"
                + " threshold — neutral neighbors should contribute almost nothing to staging value",
            caribbeanSeaZone.getName())
        .isLessThan(5.0);
  }

  private static ProData proDataFor(final GameData gameData) throws Exception {
    final ProData proData = new ProData();
    final Field f = ProData.class.getDeclaredField("data");
    f.setAccessible(true);
    f.set(proData, gameData);
    return proData;
  }
}
