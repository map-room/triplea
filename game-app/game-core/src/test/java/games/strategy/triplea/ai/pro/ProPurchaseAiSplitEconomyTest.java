package games.strategy.triplea.ai.pro;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.player.PlayerBridge;
import games.strategy.triplea.ai.pro.data.ProSplitResourceTracker;
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
}
