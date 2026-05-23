package games.strategy.triplea.ai.pro.util;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.delegate.GameDataTestUtil;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

/**
 * Tests for {@link ProAmphUtils#survivalProbability} and {@link
 * ProAmphUtils#DISEMBARK_SURVIVAL_THRESHOLD}.
 *
 * <p>Verifies the survival-gate semantics described in design §6.6 and §5.2-1.
 */
public class ProAmphUtilsSurvivalTest {

  private static final String SEA_ZONE_6 = "6 Sea Zone";

  private GameData data;
  private GamePlayer americans;
  private GamePlayer japanese;

  @BeforeEach
  void setUp() {
    ClientSetting.setPreferences(new MemoryPreferences());
    data = TestMapGameData.GLOBAL1940.getGameData();
    americans = data.getPlayerList().getPlayerId("Americans");
    japanese = data.getPlayerList().getPlayerId("Japanese");
    declareWar(americans, japanese);
    for (final Territory t : data.getMap().getTerritories()) {
      data.performChange(ChangeFactory.removeUnits(t, t.getUnits()));
    }
  }

  // ─── constant ────────��───────────────────────────────────────────────────

  @Test
  void disembarkSurvivalThresholdIsSeventyPercent() {
    assertThat(ProAmphUtils.DISEMBARK_SURVIVAL_THRESHOLD)
        .as("DISEMBARK_SURVIVAL_THRESHOLD must equal 0.70 (design §6.6)")
        .isEqualTo(0.70);
  }

  // ─── no enemy threat → P = 1.0 ────────────────────────���──────────────────

  @Test
  void noEnemyUnitsNearbyYieldsProbabilityOne() {
    // Staging SZ with one American infantry and zero enemy units anywhere.
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);
    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);

    final double p = ProAmphUtils.survivalProbability(List.of(infantry), sz6, americans);

    assertThat(p).as("No enemy threat → P must be 1.0 (definitely safe)").isEqualTo(1.0);
  }

  // ─── overwhelming attack → P < threshold ───────────────────────��─────────

  @Test
  void overwhelmingEnemyFleetYieldsProbabilityBelowThreshold() {
    // Place a large Japanese fleet adjacent to 6 SZ (e.g., in Korea → not water; use another SZ).
    // 5 battleships adjacent to 6 SZ with only 1 infantry defending → attacker wins overwhelmingly.
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);
    // Put enemy battleships IN 6 SZ (or an adjacent SZ adjacent to 6 SZ)
    // Using 5 SZ as the adjacent hostile location.
    final Territory sz5 = data.getMap().getTerritoryOrThrow("5 Sea Zone");
    final List<Unit> enemyFleet = GameDataTestUtil.battleship(data).create(5, japanese);
    data.performChange(ChangeFactory.addUnits(sz5, enemyFleet));

    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);

    final double p = ProAmphUtils.survivalProbability(List.of(infantry), sz6, americans);

    assertThat(p)
        .as(
            "5 Japanese battleships adjacent to 6 SZ vs 1 American infantry → "
                + "P must be below the survival threshold (%s)",
            ProAmphUtils.DISEMBARK_SURVIVAL_THRESHOLD)
        .isLessThan(ProAmphUtils.DISEMBARK_SURVIVAL_THRESHOLD);
  }

  // ─── strong friendly escort → P ≥ threshold ──────────────────────��───────

  @Test
  void strongFriendlyEscortYieldsProbabilityAboveThreshold() {
    // Large American fleet in 6 SZ as escorts, vs weak enemy threat.
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);
    final List<Unit> americanFleet = GameDataTestUtil.battleship(data).create(5, americans);
    data.performChange(ChangeFactory.addUnits(sz6, americanFleet));

    // Weak enemy threat
    final Territory sz5 = data.getMap().getTerritoryOrThrow("5 Sea Zone");
    final List<Unit> enemyOne = GameDataTestUtil.destroyer(data).create(1, japanese);
    data.performChange(ChangeFactory.addUnits(sz5, enemyOne));

    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    // Stack includes both the infantry and the friendly escorts
    final List<Unit> stack = new java.util.ArrayList<>();
    stack.add(infantry);
    stack.addAll(americanFleet);

    final double p = ProAmphUtils.survivalProbability(stack, sz6, americans);

    assertThat(p)
        .as(
            "5 American battleships + infantry vs 1 enemy destroyer → "
                + "P must be ≥ the survival threshold (%s)",
            ProAmphUtils.DISEMBARK_SURVIVAL_THRESHOLD)
        .isGreaterThanOrEqualTo(ProAmphUtils.DISEMBARK_SURVIVAL_THRESHOLD);
  }

  // ─── threshold flips ─────────────────────────────────────────────────────

  @Test
  void addingEnemyAttackersDropsProbabilityBelowThreshold() {
    // Start with escorts that make P ≥ threshold; add enough enemy attackers to flip P < threshold.
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);

    // 2 American destroyers = moderate defense
    final List<Unit> friendlyEscort = GameDataTestUtil.destroyer(data).create(2, americans);
    data.performChange(ChangeFactory.addUnits(sz6, friendlyEscort));

    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    final List<Unit> stack = new java.util.ArrayList<>();
    stack.add(infantry);
    stack.addAll(friendlyEscort);

    final Territory sz5 = data.getMap().getTerritoryOrThrow("5 Sea Zone");

    // Without enemy: P must be ≥ threshold
    final double pSafe = ProAmphUtils.survivalProbability(stack, sz6, americans);
    assertThat(pSafe)
        .as("With 2 friendly destroyers and no enemy, P must be ≥ threshold")
        .isGreaterThanOrEqualTo(ProAmphUtils.DISEMBARK_SURVIVAL_THRESHOLD);

    // Add overwhelming enemy fleet to adjacent SZ
    final List<Unit> enemyHuge = GameDataTestUtil.battleship(data).create(8, japanese);
    data.performChange(ChangeFactory.addUnits(sz5, enemyHuge));

    final double pUnsafe = ProAmphUtils.survivalProbability(stack, sz6, americans);
    assertThat(pUnsafe)
        .as(
            "Adding 8 enemy battleships to adjacent SZ must drop P below threshold "
                + "(threshold flips)")
        .isLessThan(ProAmphUtils.DISEMBARK_SURVIVAL_THRESHOLD);
  }

  // ─── probability is bounded [0, 1] ────────────────────────��──────────────

  @Test
  void probabilityIsAlwaysBetweenZeroAndOne() {
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);

    // Extreme case: massive enemy attack
    final Territory sz5 = data.getMap().getTerritoryOrThrow("5 Sea Zone");
    final List<Unit> massiveFleet = GameDataTestUtil.battleship(data).create(20, japanese);
    data.performChange(ChangeFactory.addUnits(sz5, massiveFleet));

    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    final double p = ProAmphUtils.survivalProbability(List.of(infantry), sz6, americans);

    assertThat(p).as("P must be ≥ 0.0").isGreaterThanOrEqualTo(0.0);
    assertThat(p).as("P must be ≤ 1.0").isLessThanOrEqualTo(1.0);
  }

  // ─── helpers ─────────────────���────────────────────────��──────────────────

  private void declareWar(final GamePlayer p1, final GamePlayer p2) {
    data.performChange(
        ChangeFactory.relationshipChange(
            p1,
            p2,
            data.getRelationshipTracker().getRelationshipType(p1, p2),
            data.getRelationshipTypeList().getRelationshipType("War")));
  }
}
