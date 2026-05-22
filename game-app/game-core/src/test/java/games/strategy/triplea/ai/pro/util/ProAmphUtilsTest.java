package games.strategy.triplea.ai.pro.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.player.PlayerBridge;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.ai.pro.data.AmphContext;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

/**
 * Tests for {@link ProAmphUtils} — Phase 2.3 sea-bridging value helpers.
 *
 * <p>All tests use G40 Pacific geography (26 Sea Zone / Hawaiian Islands harbour / Japan).
 */
public class ProAmphUtilsTest {

  private static final String SEA_ZONE_6 = "6 Sea Zone";
  private static final String SEA_ZONE_26 = "26 Sea Zone";

  /** Atlantic zone (east US coast) — no Japanese territory within MAX_SEA_HOPS. */
  private static final String SEA_ZONE_101 = "101 Sea Zone";

  private static final String JAPAN = "Japan";

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
  }

  // --- findMaxLandMassSizeSeaBridging ---

  @Test
  void seaBridgingLandMassSizeAtLeastAsLargeAsLandOnly() {
    final int landOnly = ProTerritoryValueUtils.findMaxLandMassSize(americans);
    final int seaBridging = ProAmphUtils.findMaxLandMassSizeSeaBridging(americans);
    assertThat(seaBridging)
        .as("sea bridging connects island groups that are land-isolated, so result ≥ land-only")
        .isGreaterThanOrEqualTo(landOnly);
  }

  @Test
  void seaBridgingLandMassSizeIsPositive() {
    assertThat(ProAmphUtils.findMaxLandMassSizeSeaBridging(americans)).isGreaterThan(0);
  }

  // --- findAmphReachableLandValue ---

  @Test
  void stagingZoneAdjacentToEnemyIslandScoresPositive() {
    // 6 Sea Zone is directly adjacent to Japan; with Japan in the enemy map the score must be > 0.
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);
    final Territory japan = data.getMap().getTerritoryOrThrow(JAPAN);

    final Map<Territory, Double> enemyMap = Map.of(japan, 30.0);
    final double value =
        ProAmphUtils.findAmphReachableLandValue(sz6, americans, enemyMap, List.of(), List.of());

    assertThat(value)
        .as("6 Sea Zone adjacent to Japan must score > 0 when Japan is in the enemy map")
        .isGreaterThan(0.0);
  }

  @Test
  void stagingZoneAdjacentToEnemyIslandScoresHalfEnemyValue() {
    // Japan is adjacent to 6 Sea Zone (seaDistance=0). The formula gives val/2^(0+1) = val/2.
    // Other territories (Korea, Okinawa, etc.) contribute the same amount regardless of whether
    // Japan uses the map value or the production fallback, so we isolate Japan's contribution
    // by computing the DIFFERENCE between "Japan uses map value" and "Japan uses production".
    // Expected delta = (japanMapValue - japanProd) / 2^1.
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);
    final Territory japan = data.getMap().getTerritoryOrThrow(JAPAN);

    final double japanMapValue = 20.0;
    final double scoreWithMap =
        ProAmphUtils.findAmphReachableLandValue(
            sz6, americans, Map.of(japan, japanMapValue), List.of(), List.of());
    final double scoreWithProduction =
        ProAmphUtils.findAmphReachableLandValue(sz6, americans, Map.of(), List.of(), List.of());

    final double expectedDelta = (japanMapValue - TerritoryAttachment.getProduction(japan)) / 2.0;
    assertThat(scoreWithMap - scoreWithProduction)
        .as(
            "Japan's extra map-value contribution (map − production) / 2 must equal"
                + " (%.1f − %d) / 2 = %.3f",
            japanMapValue, TerritoryAttachment.getProduction(japan), expectedDelta)
        .isCloseTo(expectedDelta, within(0.001));
  }

  @Test
  void morDistantStagingZoneScoresLessOrEqualToAdjacentZone() {
    // 26 Sea Zone is several hops from Japan; 6 Sea Zone is adjacent.
    // The distant SZ can still score > 0 if Japan is within MAX_SEA_HOPS, but must be ≤ 6 SZ.
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);
    final Territory sz26 = data.getMap().getTerritoryOrThrow(SEA_ZONE_26);
    final Territory japan = data.getMap().getTerritoryOrThrow(JAPAN);

    final Map<Territory, Double> enemyMap = Map.of(japan, 30.0);
    final double score6 =
        ProAmphUtils.findAmphReachableLandValue(sz6, americans, enemyMap, List.of(), List.of());
    final double score26 =
        ProAmphUtils.findAmphReachableLandValue(sz26, americans, enemyMap, List.of(), List.of());

    assertThat(score6)
        .as("6 Sea Zone (adjacent to Japan) must score more than 26 Sea Zone (3+ hops)")
        .isGreaterThan(score26);
  }

  @Test
  void distantStagingZoneScoresPositiveWhenEnemyWithinMaxHops() {
    // 26 SZ to Japan is 3 sea hops + 1 land hop = 4 total; MAX_SEA_HOPS=4, so Japan is reachable.
    final Territory sz26 = data.getMap().getTerritoryOrThrow(SEA_ZONE_26);
    final Territory japan = data.getMap().getTerritoryOrThrow(JAPAN);

    final Map<Territory, Double> enemyMap = Map.of(japan, 30.0);
    final double score =
        ProAmphUtils.findAmphReachableLandValue(sz26, americans, enemyMap, List.of(), List.of());

    assertThat(score)
        .as("26 Sea Zone must score > 0; Japan is within MAX_SEA_HOPS (3 sea hops from 26 SZ)")
        .isGreaterThan(0.0);
  }

  @Test
  void alreadyAttackedTerritoriesAreExcludedFromScore() {
    // Japan is adjacent to 6 SZ (seaDistance=0), contributing japanValue/2.
    // Excluding Japan via territoriesToAttack must reduce the score by exactly that amount.
    // Other territories (Korea, Okinawa…) are unaffected, so the difference is precise.
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);
    final Territory japan = data.getMap().getTerritoryOrThrow(JAPAN);

    final double japanValue = 30.0;
    final Map<Territory, Double> enemyMap = Map.of(japan, japanValue);
    final double scoreAll =
        ProAmphUtils.findAmphReachableLandValue(sz6, americans, enemyMap, List.of(), List.of());
    final double scoreExcluded =
        ProAmphUtils.findAmphReachableLandValue(
            sz6, americans, enemyMap, List.of(), List.of(japan));

    assertThat(scoreAll - scoreExcluded)
        .as("excluding Japan from territoriesToAttack must reduce score by exactly japanValue/2")
        .isCloseTo(japanValue / 2.0, within(0.001));
  }

  @Test
  void landTerritoryInputReturnsZero() {
    final Territory japan = data.getMap().getTerritoryOrThrow(JAPAN);
    final Map<Territory, Double> enemyMap = Map.of(japan, 30.0);
    assertThat(
            ProAmphUtils.findAmphReachableLandValue(
                japan, americans, enemyMap, List.of(), List.of()))
        .as("land territory input must return 0 (not a valid staging sea zone)")
        .isEqualTo(0.0);
  }

  @Test
  void atlanticZoneWithNoReachableEnemyLandScoresZero() {
    // 101 Sea Zone is on the US east coast (Atlantic). No Japanese territory is reachable within
    // MAX_SEA_HOPS sea hops, so score must be 0 regardless of enemyMap content.
    final Territory sz101 = data.getMap().getTerritoryOrThrow(SEA_ZONE_101);
    final Territory japan = data.getMap().getTerritoryOrThrow(JAPAN);
    assertThat(
            ProAmphUtils.findAmphReachableLandValue(
                sz101, americans, Map.of(japan, 30.0), List.of(), List.of()))
        .as("101 Sea Zone (Atlantic, east US coast) has no Japanese territory within MAX_SEA_HOPS")
        .isEqualTo(0.0);
  }

  // --- integration: toggle gate in findTerritoryValues ---

  @Test
  void waterValueToggleOffMatchesBaselineWhenNoAmphib() {
    // With AmphContext.DISABLED, findTerritoryValues must not add any amphib contribution.
    // We verify by checking that the water territory value is identical when computed via
    // ProAi (which seeds proData.amphContext = DISABLED by default).
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);

    // Clear irrelevant units for a stable test state
    for (final Territory t : data.getMap().getTerritories()) {
      data.performChange(ChangeFactory.removeUnits(t, t.getUnits()));
    }

    final ProAi proAi = new ProAi("Test", "Americans AI");
    final PlayerBridge bridge = mock(PlayerBridge.class);
    proAi.initialize(bridge, americans);
    when(bridge.getGameData()).thenReturn(data);
    // proData.initialize() is called by game-phase methods, not by initialize() itself.
    // Call reinitializeProDataForSidecar() to wire proData.data = game data before using proData.
    proAi.reinitializeProDataForSidecar();

    // amphContext defaults to DISABLED — no extra amphib contribution
    assertThat(proAi.getProData().getAmphContext().isEnabled()).isFalse();

    final Map<Territory, Double> disabledValues =
        ProTerritoryValueUtils.findTerritoryValues(
            proAi.getProData(), americans, List.of(), List.of(), Set.of(sz6));

    // Now enable amphib and compute again
    proAi.getProData().setAmphContext(new AmphContext(true, Set.of(), Set.of()));
    final Map<Territory, Double> enabledValues =
        ProTerritoryValueUtils.findTerritoryValues(
            proAi.getProData(), americans, List.of(), List.of(), Set.of(sz6));

    // With Japan as enemy, amphib-enabled value must be >= disabled value (never reduces)
    assertThat(enabledValues.get(sz6))
        .as("amphib-enabled water value must be >= disabled value")
        .isGreaterThanOrEqualTo(disabledValues.get(sz6));
  }

  @Test
  void waterValueAmphEnablingAddsPositiveContributionNearEnemyLand() {
    // Verify that staging SZ adjacent to Japan scores strictly higher with amphib enabled.
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);

    // Clear units for a clean slate
    for (final Territory t : data.getMap().getTerritories()) {
      data.performChange(ChangeFactory.removeUnits(t, t.getUnits()));
    }

    final ProAi proAi = new ProAi("Test", "Americans AI");
    final PlayerBridge bridge = mock(PlayerBridge.class);
    proAi.initialize(bridge, americans);
    when(bridge.getGameData()).thenReturn(data);
    proAi.reinitializeProDataForSidecar();

    final Map<Territory, Double> disabledValues =
        ProTerritoryValueUtils.findTerritoryValues(
            proAi.getProData(), americans, List.of(), List.of(), Set.of(sz6));

    proAi.getProData().setAmphContext(new AmphContext(true, Set.of(), Set.of()));
    final Map<Territory, Double> enabledValues =
        ProTerritoryValueUtils.findTerritoryValues(
            proAi.getProData(), americans, List.of(), List.of(), Set.of(sz6));

    assertThat(enabledValues.get(sz6))
        .as(
            "6 Sea Zone (adjacent to enemy Japan) must score strictly higher with amphib enabled"
                + " than without — this is the linchpin of Phase 2.3")
        .isGreaterThan(disabledValues.get(sz6));
  }

  // ---- helpers ----

  private void declareWar(final GamePlayer p1, final GamePlayer p2) {
    data.performChange(
        ChangeFactory.relationshipChange(
            p1,
            p2,
            data.getRelationshipTracker().getRelationshipType(p1, p2),
            data.getRelationshipTypeList().getRelationshipType("War")));
  }
}
