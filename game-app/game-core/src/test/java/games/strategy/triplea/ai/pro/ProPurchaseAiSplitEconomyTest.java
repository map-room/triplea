package games.strategy.triplea.ai.pro;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.player.PlayerBridge;
import games.strategy.triplea.ai.pro.data.ProResourceTracker;
import games.strategy.triplea.ai.pro.data.ProSplitResourceTracker;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.PurchaseDelegate;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

/**
 * Integration: ProPurchaseAi running against a British player with the split resource tracker
 * active must plan a purchase that respects per-pool budgets.
 *
 * <p>Uses the Global 1940 test fixture; sets explicit small budgets so the planner is forced into
 * the per-pool constraint. The assertion is that the full purchase invocation completes without
 * exception — ProResourceTracker would throw if a pool went negative.
 */
public class ProPurchaseAiSplitEconomyTest {

  private ProAi proAi;
  private GameData gameData;
  private PurchaseDelegate purchaseDelegate;
  private GamePlayer british;

  @BeforeEach
  void setUp() {
    ClientSetting.setPreferences(new MemoryPreferences());
    gameData = TestMapGameData.GLOBAL1940.getGameData();
    british = gameData.getPlayerList().getPlayerId("British");
    proAi = new ProAi("Test British", "British Test Player");
    final IDelegateBridge bridge = newDelegateBridge(british);
    purchaseDelegate = (PurchaseDelegate) gameData.getDelegate("purchase");
    purchaseDelegate.setDelegateBridgeAndPlayer(bridge);
    final PlayerBridge playerBridgeMock = Mockito.mock(PlayerBridge.class);
    proAi.initialize(playerBridgeMock, british);
    when(playerBridgeMock.getGameData()).thenReturn(gameData);
  }

  @Test
  void splitTrackerPurchaseCompletesWithoutOverDraft() {
    // Budget: Europe 12 PUs, Pacific 6 PUs. Small enough to force per-pool
    // constraints but non-zero in both so the planner has something to spend
    // on each side.
    final Map<Territory, ProSplitResourceTracker.Pool> poolByTerritory = new HashMap<>();
    final Territory uk = gameData.getMap().getTerritoryOrThrow("United Kingdom");
    final Territory india = gameData.getMap().getTerritoryOrThrow("India");
    poolByTerritory.put(uk, ProSplitResourceTracker.Pool.EUROPE);
    poolByTerritory.put(india, ProSplitResourceTracker.Pool.PACIFIC);
    proAi.setBritishEconomySplit(12, 6, poolByTerritory);

    // Purchase completes; if the split tracker went negative on either pool,
    // the underlying IntegerMap operations would misbehave and the purchase
    // delegate would reject the plan. assertDoesNotThrow is the invariant.
    assertDoesNotThrow(() -> proAi.purchase(false, 18, purchaseDelegate, gameData, british));
  }

  /**
   * Regression test for #2608: factory-repair PUs charged to the europe pool must be deducted from
   * britishEuropePus before ProSplitResourceTracker is seeded. Without the fix, the tracker was
   * seeded with the pre-repair balance, causing the planner to over-allocate the europe pool by the
   * repair amount and triggering a soft-reject at dispatch (observed in match WBZMQ5Niegt, round
   * 6).
   *
   * <p>Scenario: europe pool = repairCost (6 PUs); UK factory damaged by 6 PUs. After repair the
   * europe pool is fully consumed; the tracker must be seeded with 0 so the planner does not plan
   * any additional europe buys.
   */
  @Test
  void europeBudgetDeductedByRepairBeforeTrackerSeeded() {
    // G40 has "Damage From Bombing Done To Units Instead Of Territories" = true, so factory-repair
    // is enabled. Damage the UK factory by repairCost PUs to trigger the repair path.
    final int repairCost = 6;
    final Territory uk = gameData.getMap().getTerritoryOrThrow("United Kingdom");
    final Unit ukFactory =
        uk.getUnitCollection().stream()
            .filter(
                u ->
                    u.getOwner().equals(british)
                        && Matches.unitCanProduceUnits().test(u)
                        && Matches.unitIsInfrastructure().test(u))
            .findFirst()
            .orElseThrow(() -> new AssertionError("UK factory unit not found in United Kingdom"));
    ukFactory.setUnitDamage(repairCost);

    // Set europe pool = exactly the repair cost so the deduction should leave it at 0.
    final Territory india = gameData.getMap().getTerritoryOrThrow("India");
    final Map<Territory, ProSplitResourceTracker.Pool> poolByTerritory = new HashMap<>();
    poolByTerritory.put(uk, ProSplitResourceTracker.Pool.EUROPE);
    poolByTerritory.put(india, ProSplitResourceTracker.Pool.PACIFIC);
    proAi.setBritishEconomySplit(repairCost, 16, poolByTerritory);

    // Run the full purchase phase: repair fires first (spending repairCost from the europe pool),
    // then the planner runs against the corrected tracker.
    assertDoesNotThrow(
        () -> proAi.purchase(false, repairCost + 16, purchaseDelegate, gameData, british));

    // After the fix: britishEuropePus was decremented by repairCost before createResourceTracker()
    // was called, so a new tracker seeded from britishEuropePus shows europe=0.
    // Without the fix: britishEuropePus would still equal repairCost and the tracker would show
    // europe=6, meaning the planner incorrectly believed it had 6 more PUs to spend on europe buys.
    final ProResourceTracker tracker = proAi.createResourceTracker(british, gameData);
    assertTrue(
        tracker.toString().contains("europe=0"),
        "Expected europe pool to be 0 after repair deduction, but got: " + tracker);
  }
}
