package games.strategy.triplea.ai.pro;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.xml.TestMapGameData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the §20 defensive filter in {@link ProPurchaseAi#isNotOwnedByPlayerAtStartOfTurn}.
 *
 * <p>§20 (Global 1940): a new factory may only be placed on territory the player controlled since
 * the start of their turn. The filter guards against mid-turn ownership drift in long-running
 * sessions where live {@code GameData} can diverge from the turn-start snapshot.
 */
class ProPurchaseAiSection20Test {

  private GameData startOfTurnData;
  private GameData liveData;
  private GamePlayer japanese;

  @BeforeEach
  void setUp() {
    // Load two independent copies of G40: one serves as the frozen turn-start snapshot, the other
    // as the "live" GameData that may have drifted.
    startOfTurnData = TestMapGameData.GLOBAL1940.getGameData();
    liveData = TestMapGameData.GLOBAL1940.getGameData();

    japanese = liveData.getPlayerList().getPlayerId("Japanese");
  }

  /**
   * Scenario: Kwangtung is UK_Pacific-owned at game start in both snapshots. Japanese player
   * legitimately owns Japan (a Japanese-owned land territory). The filter must pass Japan and block
   * Kwangtung.
   */
  @Test
  void japaneseOwnedTerritoryIsNotFiltered() {
    final Territory japan = liveData.getMap().getTerritoryOrNull("Japan");
    assert japan != null : "Japan territory not found in G40 game data";

    assertFalse(
        ProPurchaseAi.isNotOwnedByPlayerAtStartOfTurn(startOfTurnData, japanese, japan),
        "Japan is Japanese-owned at turn start — should NOT be filtered out");
  }

  /**
   * Scenario: Live GameData incorrectly shows Kwangtung as Japanese-owned (simulating state drift),
   * but startOfTurnData correctly shows UK_Pacific ownership. The filter must exclude Kwangtung
   * regardless of what live GameData says.
   */
  @Test
  void ukTerritoryIsFilteredEvenWhenLiveDataShowsJapaneseOwnership() {
    final Territory kwangtungLive = liveData.getMap().getTerritoryOrNull("Kwangtung");
    assert kwangtungLive != null : "Kwangtung territory not found in G40 game data";

    // Simulate mid-turn drift: live data now shows Japan as the owner.
    kwangtungLive.setOwner(japanese);

    // Confirm the drift: live data says Japanese, but startOfTurnData still says UK_Pacific.
    assertTrue(
        japanese.equals(kwangtungLive.getOwner()),
        "Precondition: live data should show Kwangtung as Japanese-owned after the simulated drift");

    // The §20 filter checks startOfTurnData, not liveData — it should still filter Kwangtung out.
    assertTrue(
        ProPurchaseAi.isNotOwnedByPlayerAtStartOfTurn(startOfTurnData, japanese, kwangtungLive),
        "Kwangtung was UK-owned at turn start — must be filtered out despite live drift");
  }

  /**
   * Scenario: Territory name doesn't exist in startOfTurnData (hypothetical missing territory).
   * Filter must treat it as blocked (return true) — null-safe.
   */
  @Test
  void missingTerritoryInStartOfTurnDataIsFiltered() {
    // Create a mock territory whose name doesn't exist in the G40 map.
    final Territory phantom = new Territory("Phantom Island", liveData);

    assertTrue(
        ProPurchaseAi.isNotOwnedByPlayerAtStartOfTurn(startOfTurnData, japanese, phantom),
        "A territory absent from startOfTurnData must be filtered out");
  }
}
