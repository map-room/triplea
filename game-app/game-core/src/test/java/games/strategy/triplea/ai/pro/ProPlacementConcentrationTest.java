package games.strategy.triplea.ai.pro;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.RelationshipType;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.player.PlayerBridge;
import games.strategy.triplea.delegate.PlaceDelegate;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

/**
 * Regression tests for map-room#2638: {@link ProPurchaseAi#placeUnits} must spread buys across
 * available factories using diminishing-returns scoring instead of concentrating all units at the
 * top-priority territory.
 *
 * <p>Before the fix, {@code placeUnits} filled the first territory in its priority list to capacity
 * before moving to the next, so Germans with 9 infantry and two factories (Germany + Western
 * Germany) would stack all 9 at whichever factory appeared first. After the fix a
 * diminishing-returns priority score (adjusted by units already placed this turn) guarantees at
 * least two factories receive units when two are available.
 */
public class ProPlacementConcentrationTest {

  private GameData data;
  private GamePlayer germans;
  private GamePlayer russians;

  @BeforeEach
  void setUp() {
    ClientSetting.setPreferences(new MemoryPreferences());
    data = TestMapGameData.GLOBAL1940.getGameData();
    germans = data.getPlayerList().getPlayerId("Germans");
    russians = data.getPlayerList().getPlayerId("Russians");
  }

  /**
   * Regression (map-room#2638): with Germany factory_major (production=10) and Western Germany
   * factory_minor (production=3) available, placing 9 infantry must spread across at least 2
   * factories.
   *
   * <p>Setup: Germans at war with Russians (so proximity scoring puts both factories in the
   * prioritized list). 9 infantry in player's unit pool (the bought-but-unplaced state). No prior
   * {@code purchase()} call so {@code storedPurchaseTerritories} is null, forcing the fallback
   * {@code placeUnits} path during {@code place()}.
   *
   * <p>Counter-regression: all 9 infantry must actually be placed (no units dropped).
   */
  @Test
  void infantrySpreadAcrossGermanyAndWesternGermany() {
    final Territory germany = data.getMap().getTerritoryOrThrow("Germany");
    final Territory westernGermany = data.getMap().getTerritoryOrThrow("Western Germany");

    assertThat(germany.getOwner()).as("Germany must be German-owned").isEqualTo(germans);
    assertThat(westernGermany.getOwner())
        .as("Western Germany must be German-owned")
        .isEqualTo(germans);

    // Set Germans at war with Russians so proximity scoring puts both factories in the
    // prioritized list (enemy territories within 9 hexes → both qualify for placement).
    final RelationshipType war = data.getRelationshipTypeList().getRelationshipType("War");
    data.performChange(
        ChangeFactory.relationshipChange(
            germans,
            russians,
            data.getRelationshipTracker().getRelationshipType(germans, russians),
            war));

    // Add 9 infantry to Germans' unit pool (simulates a R1 purchase of 27 IPCs).
    final UnitType infantryType = data.getUnitTypeList().getUnitTypeOrThrow("infantry");
    final List<Unit> infantry = infantryType.create(9, germans);
    data.performChange(ChangeFactory.addUnits(germans, infantry));

    // Record unit counts BEFORE placement to detect newly placed units.
    final int germanyBefore =
        (int) germany.getUnits().stream().filter(u -> u.getOwner().equals(germans)).count();
    final int westernGermanyBefore =
        (int) westernGermany.getUnits().stream().filter(u -> u.getOwner().equals(germans)).count();

    // Set up ProAi and PlaceDelegate.
    final IDelegateBridge bridge = newDelegateBridge(germans);
    final PlaceDelegate placeDelegate = (PlaceDelegate) data.getDelegate("place");
    placeDelegate.setDelegateBridgeAndPlayer(bridge);
    placeDelegate.start();

    final PlayerBridge playerBridgeMock = Mockito.mock(PlayerBridge.class);
    when(playerBridgeMock.getGameData()).thenReturn(data);
    final ProAi proAi = new ProAi("test-Germans", "Germans");
    proAi.initialize(playerBridgeMock, germans);

    data.getSequence().setRoundAndStep(1, "Place Units", germans);

    // Call place() with storedPurchaseTerritories=null → exercises the fallback placeUnits path.
    proAi.place(false, placeDelegate, data, germans);

    // Assert: all 9 infantry must have been placed (counter-regression: no units dropped).
    assertThat(germans.getUnits())
        .as("All 9 infantry must be placed; none should remain in the player pool")
        .isEmpty();

    // Assert: at least 2 factories received units (primary regression: no concentration).
    final int germanyAfter =
        (int) germany.getUnits().stream().filter(u -> u.getOwner().equals(germans)).count();
    final int westernGermanyAfter =
        (int) westernGermany.getUnits().stream().filter(u -> u.getOwner().equals(germans)).count();

    final boolean germanyGotUnits = germanyAfter > germanyBefore;
    final boolean westernGermanyGotUnits = westernGermanyAfter > westernGermanyBefore;

    assertThat(germanyGotUnits && westernGermanyGotUnits)
        .as(
            "ProAi must spread 9 infantry across Germany AND Western Germany when both factories"
                + " are available (regression: map-room#2638). Before: Germany=%d,"
                + " WesternGermany=%d. After: Germany=%d, WesternGermany=%d."
                    .formatted(
                        germanyBefore, westernGermanyBefore, germanyAfter, westernGermanyAfter))
        .isTrue();
  }
}
