package games.strategy.triplea.ai.pro;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.player.PlayerBridge;
import games.strategy.triplea.ai.pro.data.ProPlaceTerritory;
import games.strategy.triplea.ai.pro.data.ProPurchaseTerritory;
import games.strategy.triplea.delegate.PurchaseDelegate;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

/**
 * Verifies that {@link ProPurchaseAi} distributes land-unit allocations across multiple factories
 * rather than concentrating everything at the highest-priority territory (map-room#2638).
 *
 * <p>Three scenarios:
 *
 * <ul>
 *   <li>Large buy (40 PU Germany R1) → ≥2 distinct land factories receive units.
 *   <li>Small buy (3 PU) → ≤2 factories (no scattering of tiny buys).
 *   <li>{@link ProPurchaseAi#PLACEMENT_SPREAD_FACTOR} = 0 via direct call to {@code
 *       redistributeLandPlacementUnits} → all units concentrate at the top-priority factory.
 * </ul>
 */
class ProPurchaseAiPlacementSpreadTest {

  private double savedSpreadFactor;
  private GameData gameData;
  private GamePlayer german;
  private ProAi proAi;
  private PurchaseDelegate purchaseDelegate;

  @BeforeEach
  void setUp() {
    savedSpreadFactor = ProPurchaseAi.PLACEMENT_SPREAD_FACTOR;
    ProPurchaseAi.PLACEMENT_SPREAD_FACTOR = 0.5;
    ClientSetting.setPreferences(new MemoryPreferences());
    gameData = TestMapGameData.GLOBAL1940.getGameData();
    german = gameData.getPlayerList().getPlayerId("Germans");
    proAi = new ProAi("Test German", "German Test Player");
    final IDelegateBridge bridge = newDelegateBridge(german);
    purchaseDelegate = (PurchaseDelegate) gameData.getDelegate("purchase");
    purchaseDelegate.setDelegateBridgeAndPlayer(bridge);
    final PlayerBridge playerBridgeMock = Mockito.mock(PlayerBridge.class);
    proAi.initialize(playerBridgeMock, german);
    when(playerBridgeMock.getGameData()).thenReturn(gameData);
    proAi.getProData().setSeed(42L);
  }

  @AfterEach
  void restoreSpreadFactor() {
    ProPurchaseAi.PLACEMENT_SPREAD_FACTOR = savedSpreadFactor;
  }

  @Test
  void largeBuy_spreadsAcrossMultipleFactories() {
    // Germany R1 has 40 PUs → typically buys 9+ ground units; the redistribution must spread
    // them across at least 2 distinct land factories (regression: before the fix, placements=1
    // every round because all units concentrated at the single top-priority territory).
    proAi.purchase(false, 40, purchaseDelegate, gameData, german);

    final Map<Territory, ProPurchaseTerritory> stored = proAi.getStoredPurchaseTerritories();
    final long distinctLandFactoriesWithUnits =
        stored == null
            ? 0
            : stored.values().stream()
                .flatMap(ppt -> ppt.getCanPlaceTerritories().stream())
                .filter(pt -> !pt.getTerritory().isWater())
                .filter(pt -> !pt.getPlaceUnits().isEmpty())
                .count();

    assertThat(distinctLandFactoriesWithUnits)
        .as("German 40-PU buy must spread across at least 2 land factories")
        .isGreaterThanOrEqualTo(2);
  }

  @Test
  void smallBuy_staysAtTopPriorityTerritory() {
    // 3 PUs → 1 infantry. Single-unit buys must still go to the highest-priority territory;
    // the soft-cap must not scatter a tiny buy across territories.
    proAi.purchase(false, 3, purchaseDelegate, gameData, german);

    final Map<Territory, ProPurchaseTerritory> stored = proAi.getStoredPurchaseTerritories();
    final long distinctLandFactoriesWithUnits =
        stored == null
            ? 0
            : stored.values().stream()
                .flatMap(ppt -> ppt.getCanPlaceTerritories().stream())
                .filter(pt -> !pt.getTerritory().isWater())
                .filter(pt -> !pt.getPlaceUnits().isEmpty())
                .count();

    // 2 is acceptable: purchaseDefenders() may legitimately place a unit at a second factory
    // before purchaseLandUnits() runs; the spread guard is against 9-unit concentration, not
    // this normal defender-allocation pattern.
    assertThat(distinctLandFactoriesWithUnits)
        .as("3-PU buy must not scatter across many factories; ≤2 allows one defender territory")
        .isLessThanOrEqualTo(2);
  }

  @Test
  void spreadFactorZero_concentratesRedistributionAtTopFactory() {
    // Directly exercise redistributeLandPlacementUnits with SPREAD_FACTOR=0: all units must stay
    // at the highest-priority territory (the original concentrated behaviour is restored when the
    // knob is turned off). Tests the knob in isolation — the full purchase() flow is not used here
    // because purchaseDefenders() places defenders independently of the redistribution logic.
    ProPurchaseAi.PLACEMENT_SPREAD_FACTOR = 0;
    final ProPurchaseAi purchaseAi = proAi.getPurchaseAi();

    final Territory germany = gameData.getMap().getTerritoryOrThrow("Germany");
    final Territory wg = gameData.getMap().getTerritoryOrThrow("Western Germany");
    final Territory romania = gameData.getMap().getTerritoryOrThrow("Romania");

    final ProPurchaseTerritory germanyPpt =
        new ProPurchaseTerritory(germany, gameData, german, Integer.MAX_VALUE);
    final ProPurchaseTerritory wgPpt = new ProPurchaseTerritory(wg, gameData, german, 3);
    final ProPurchaseTerritory romaniaPpt = new ProPurchaseTerritory(romania, gameData, german, 3);

    final Map<Territory, ProPurchaseTerritory> purchaseTerritories = new HashMap<>();
    purchaseTerritories.put(germany, germanyPpt);
    purchaseTerritories.put(wg, wgPpt);
    purchaseTerritories.put(romania, romaniaPpt);

    final ProPlaceTerritory germanyPlace = germanyPpt.getCanPlaceTerritories().get(0);
    final ProPlaceTerritory wgPlace = wgPpt.getCanPlaceTerritories().get(0);
    final ProPlaceTerritory romaniaPlace = romaniaPpt.getCanPlaceTerritories().get(0);

    // Strategic values must be set explicitly so the sort inside redistributeLandPlacementUnits
    // produces a deterministic order (HashMap iteration order is otherwise arbitrary).
    germanyPlace.setStrategicValue(100.0);
    wgPlace.setStrategicValue(50.0);
    romaniaPlace.setStrategicValue(25.0);

    final List<ProPlaceTerritory> prioritized =
        new ArrayList<>(List.of(germanyPlace, wgPlace, romaniaPlace));

    final games.strategy.engine.data.UnitType infantry =
        gameData.getUnitTypeList().getUnitType("infantry").orElseThrow();
    for (int i = 0; i < 9; i++) {
      germanyPlace.getPlaceUnits().add(infantry.createTemp(1, german).get(0));
    }

    purchaseAi.redistributeLandPlacementUnits(purchaseTerritories, prioritized);

    // With SPREAD_FACTOR=0 the adjusted score for each territory is simply (n - index), which
    // never decreases. Germany is at index 0 after sorting by strategic value → base score = 3,
    // WG = 2, Romania = 1. Germany wins every round → all 9 units placed there.
    assertThat(germanyPlace.getPlaceUnits().size())
        .as("All units must concentrate at Germany with SPREAD_FACTOR=0")
        .isEqualTo(9);
    assertThat(wgPlace.getPlaceUnits()).as("Western Germany must be empty").isEmpty();
    assertThat(romaniaPlace.getPlaceUnits()).as("Romania must be empty").isEmpty();
  }

  // ── Unit-level tests for redistributeLandPlacementUnits ──────────────────────────────────────

  @Test
  void redistribute_ninteenUnitsAcrossThreeFactories_spreads() {
    // Directly exercise redistributeLandPlacementUnits with controlled inputs.
    // Three factories: Germany (cap=MAX), Western Germany (cap=3), Romania (cap=3)
    final ProPurchaseAi purchaseAi = proAi.getPurchaseAi();

    final Territory germany = gameData.getMap().getTerritoryOrThrow("Germany");
    final Territory wg = gameData.getMap().getTerritoryOrThrow("Western Germany");
    final Territory romania = gameData.getMap().getTerritoryOrThrow("Romania");

    final ProPurchaseTerritory germanyPpt =
        new ProPurchaseTerritory(germany, gameData, german, Integer.MAX_VALUE);
    final ProPurchaseTerritory wgPpt = new ProPurchaseTerritory(wg, gameData, german, 3);
    final ProPurchaseTerritory romaniaPpt = new ProPurchaseTerritory(romania, gameData, german, 3);

    final Map<Territory, ProPurchaseTerritory> purchaseTerritories = new HashMap<>();
    purchaseTerritories.put(germany, germanyPpt);
    purchaseTerritories.put(wg, wgPpt);
    purchaseTerritories.put(romania, romaniaPpt);

    // Prioritized order: Germany > W.Germany > Romania
    final ProPlaceTerritory germanyPlace = germanyPpt.getCanPlaceTerritories().get(0);
    final ProPlaceTerritory wgPlace = wgPpt.getCanPlaceTerritories().get(0);
    final ProPlaceTerritory romaniaPlace = romaniaPpt.getCanPlaceTerritories().get(0);
    final List<ProPlaceTerritory> prioritized =
        new ArrayList<>(List.of(germanyPlace, wgPlace, romaniaPlace));

    // Seed all 9 units at Germany (simulating the concentration bug)
    final games.strategy.engine.data.UnitType infantry =
        gameData.getUnitTypeList().getUnitType("infantry").orElseThrow();
    for (int i = 0; i < 9; i++) {
      germanyPlace.getPlaceUnits().add(infantry.createTemp(1, german).get(0));
    }

    purchaseAi.redistributeLandPlacementUnits(purchaseTerritories, prioritized);

    final long factories = prioritized.stream().filter(pt -> !pt.getPlaceUnits().isEmpty()).count();
    assertThat(factories)
        .as("9 units across Germany(MAX), W.Germany(3), Romania(3) must spread to ≥3 factories")
        .isGreaterThanOrEqualTo(3);
    final int total = prioritized.stream().mapToInt(pt -> pt.getPlaceUnits().size()).sum();
    assertThat(total).as("Total unit count must be preserved after redistribution").isEqualTo(9);
  }
}
