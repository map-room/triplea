package games.strategy.triplea.ai.pro;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.framework.GameDataManager;
import games.strategy.engine.framework.GameDataUtils;
import games.strategy.engine.player.PlayerBridge;
import games.strategy.triplea.ai.pro.data.ProPurchaseTerritory;
import games.strategy.triplea.ai.pro.util.ProPurchaseUtils;
import games.strategy.triplea.delegate.AbstractMoveDelegate;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.PurchaseDelegate;
import games.strategy.triplea.delegate.battle.BattleTracker;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

/**
 * Regression tests for issue map-room#2368.
 *
 * <p>{@link AbstractProAi#purchase}'s internal simulation loop keeps the original session {@code
 * player} on {@link ProData} so that downstream code (e.g., {@code purchaseDelegate.purchase} on
 * the live session) operates on session references. That means {@code
 * proData.getPlayer().getData()} resolves to the un-simulated session, not the cloned {@code
 * dataCopy} the simulation actually mutated. Predicates that read state via {@code
 * player.getData()} therefore hit the wrong instance:
 *
 * <ul>
 *   <li>{@link games.strategy.triplea.ai.pro.util.ProMatches#territoryIsNotConqueredOwnedLand} —
 *       reads {@code BattleTracker} via {@code
 *       AbstractMoveDelegate.getBattleTracker(player.getData())}. {@code simulateBattles} correctly
 *       marks just-conquered territories on {@code dataCopy.battleTracker.conquered}, but a read
 *       through {@code player.getData()} lands on the session's empty tracker.
 * </ul>
 *
 * <p>map-room#2368 fixed that by passing the {@code dataCopy}-rooted player into {@link
 * ProPurchaseUtils#findPurchaseTerritories}. Upstream #15144 fixed the same read: {@code
 * territoryIsNotConqueredUnlessPlacementCapturedAllowed} uses {@code t.getData()}, so a capture on
 * the simulation copy is excluded even when the session player is passed. The dataCopy-rooted
 * player argument stays, for any remaining {@code player.getData()} reads. {@code
 * proData.getPlayer()} stays the session player — changing that rooting surfaced a {@code
 * TechTracker} NPE in {@code AiGameTest}.
 *
 * <p>Tests:
 *
 * <ul>
 *   <li>{@link #findPurchaseTerritoriesUsesPlayerGetDataAsBattleTrackerSource} — a simulated
 *       capture is excluded for both the session player and the dataCopy player, because #15144
 *       reads the battle tracker from the territory's own data.
 *   <li>{@link #purchaseSimulationCompletesWithoutThrowing} — smoke test that the surgical fix
 *       integrates with the full {@link AbstractProAi#purchase} simulation loop (would catch any
 *       interaction bug like the {@code dataCopy}-rooted-proData NPE described above).
 * </ul>
 */
class ProAiPurchaseSimulationDataRootingTest {

  private static final String CAPTURED_TERRITORY_NAME = "Southern France";

  private GameData gameData;
  private GamePlayer germans;
  private PurchaseDelegate purchaseDelegate;

  @BeforeEach
  void setUp() {
    ClientSetting.setPreferences(new MemoryPreferences());
    gameData = TestMapGameData.GLOBAL1940.getGameData();
    germans = gameData.getPlayerList().getPlayerId("Germans");
    final IDelegateBridge testBridge = newDelegateBridge(germans);
    purchaseDelegate = (PurchaseDelegate) gameData.getDelegate("purchase");
    purchaseDelegate.setDelegateBridgeAndPlayer(testBridge);
  }

  /**
   * Constructs the simulation state the place-step branch sees: a cloned {@code dataCopy} where
   * Germans simulated capturing Southern France (ownership transferred + entry in {@code
   * BattleTracker.conquered}). Verifies that {@link ProPurchaseUtils#findPurchaseTerritories}
   * excludes Southern France for both the session player and the dataCopy player.
   *
   * <p>Upstream #15144 ({@code territoryIsNotConqueredUnlessPlacementCapturedAllowed}) reads
   * conquests from {@code t.getData()} rather than {@code player.getData()}, so the session player
   * no longer leaks a capture that exists only on the simulation copy. That is the same bug as
   * map-room#2368. {@link ProPurchaseAi#purchase} still passes the dataCopy-rooted player, which
   * keeps any remaining {@code player.getData()} reads on the simulation.
   */
  @Test
  void findPurchaseTerritoriesUsesPlayerGetDataAsBattleTrackerSource() {
    final GameData dataCopy =
        GameDataUtils.cloneGameData(
                gameData, GameDataManager.Options.builder().withDelegates(true).build())
            .orElseThrow();
    final GamePlayer copyGermans = dataCopy.getPlayerList().getPlayerId("Germans");
    final Territory cloneSouthernFrance =
        dataCopy.getMap().getTerritoryOrNull(CAPTURED_TERRITORY_NAME);

    // Simulate the post-combat-and-battle state on dataCopy: Germans now own Southern France
    // (territory + the factory inside it) AND the BattleTracker records it as conquered this
    // turn. ProMatches.territoryHasFactoryAndIsOwnedLand checks for a factory unit owned by the
    // player, so we have to transfer factory ownership too — that's what
    // BattleTracker.captureOrDestroyUnits does in the real simulateBattles path.
    dataCopy.performChange(ChangeFactory.changeOwner(cloneSouthernFrance, copyGermans));
    final java.util.Collection<Unit> factoriesInSouthernFrance =
        cloneSouthernFrance.getMatches(Matches.unitCanProduceUnits());
    if (!factoriesInSouthernFrance.isEmpty()) {
      dataCopy.performChange(
          ChangeFactory.changeOwner(factoriesInSouthernFrance, copyGermans, cloneSouthernFrance));
    }
    final BattleTracker tracker = AbstractMoveDelegate.getBattleTracker(dataCopy);
    tracker.getConquered().add(cloneSouthernFrance);

    // Initialise proData in simulation mode — this is what the simulation loop's place-step
    // branch sets up before calling ProPurchaseAi#purchase.
    final ProAi proAi = new ProAi("test-ai", "Germans");
    final PlayerBridge playerBridgeMock = Mockito.mock(PlayerBridge.class);
    when(playerBridgeMock.getGameData()).thenReturn(gameData);
    proAi.initialize(playerBridgeMock, germans);
    proAi.getProData().initializeSimulation(proAi, dataCopy, germans);

    // Upstream #15144: conquest is read from the territory's GameData. Southern France is a
    // dataCopy territory, so both the session player and the dataCopy player exclude it.
    final Map<Territory, ProPurchaseTerritory> withSessionPlayer =
        ProPurchaseUtils.findPurchaseTerritories(proAi.getProData(), germans);
    assertThat(
        "Upstream #15144 reads wasConquered from the territory's data, so a capture that "
            + "exists only on the simulation copy is excluded even for the session player "
            + "(same bug as map-room#2368)",
        withSessionPlayer,
        not(hasKey(cloneSouthernFrance)));

    // The dataCopy-rooted player, which ProPurchaseAi.purchase still passes, excludes it too.
    final Map<Territory, ProPurchaseTerritory> withDataCopyPlayer =
        ProPurchaseUtils.findPurchaseTerritories(proAi.getProData(), copyGermans);
    assertThat(
        "FIX: with the dataCopy-rooted player, just-captured territories are correctly "
            + "excluded from the purchase plan (#2368)",
        withDataCopyPlayer,
        not(hasKey(cloneSouthernFrance)));
  }

  /**
   * End-to-end smoke test that the surgical fix in {@link ProPurchaseAi#purchase} integrates
   * cleanly with the full {@link AbstractProAi#purchase} simulation loop. Would catch any
   * interaction bug — for example, if a future refactor switched the simulation loop's place-step
   * branch to use {@code playerCopy} on {@link ProData}, that change surfaces a separate latent
   * issue where stale {@code dataCopy} unit references leak across purchase calls (a {@code
   * TechTracker} NPE on the simulated combat path inside subsequent {@code AiGameTest} runs).
   */
  @Test
  void purchaseSimulationCompletesWithoutThrowing() {
    final ProAi proAi = new ProAi("test-ai", "Germans");
    final PlayerBridge playerBridgeMock = Mockito.mock(PlayerBridge.class);
    when(playerBridgeMock.getGameData()).thenReturn(gameData);
    proAi.initialize(playerBridgeMock, germans);
    assertDoesNotThrow(() -> proAi.purchase(false, 20, purchaseDelegate, gameData, germans));
  }
}
