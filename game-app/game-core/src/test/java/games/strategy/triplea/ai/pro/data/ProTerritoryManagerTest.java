package games.strategy.triplea.ai.pro.data;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.player.PlayerBridge;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

/**
 * Tests for {@link ProTerritoryManager} NCM destination expansion for Friendly_Neutral territories.
 *
 * <p>Related to map-room#2195. The actual fix for that issue was adding {@link
 * games.strategy.triplea.ai.pro.ProNonCombatMoveAi#claimFriendlyNeutralTerritories} so the AI
 * proactively sends a unit to each accessible Friendly_Neutral territory during NCM. This class
 * tests the prerequisite: that {@code findDefendOptions} correctly includes Friendly_Neutral
 * territories (e.g. Bulgaria) in its expansion, so {@code claimFriendlyNeutralTerritories} has
 * those territories available to claim.
 *
 * <p>Note: {@code Friendly_Neutral} relationship types use {@code archeType="allied"} in the XML,
 * so {@code Matches.isTerritoryAllied} already accepts them — no change to that predicate was
 * needed.
 */
class ProTerritoryManagerTest {

  private GameData gameData;
  private GamePlayer germans;
  private ProAi proAi;

  @BeforeEach
  void setUp() {
    ClientSetting.setPreferences(new MemoryPreferences());
    gameData = TestMapGameData.GLOBAL1940.getGameData();
    germans = gameData.getPlayerList().getPlayerId("Germans");

    final PlayerBridge playerBridge = mock(PlayerBridge.class);
    when(playerBridge.getGameData()).thenReturn(gameData);
    newDelegateBridge(germans); // registers the bridge; result unused here

    proAi = new ProAi("test-Germans", "Germans");
    proAi.initialize(playerBridge, germans);
  }

  /**
   * Bulgaria (Friendly_Neutral) must appear in the defend options after {@link
   * ProTerritoryManager#populateDefenseOptions} when a German unit is in an adjacent territory.
   *
   * <p>This tests the prerequisite for {@link
   * games.strategy.triplea.ai.pro.ProNonCombatMoveAi#claimFriendlyNeutralTerritories}: that
   * Bulgaria is reachable from Romania so the claim step can dispatch a unit there. The
   * Friendly_Neutral relationship already uses {@code archeType="allied"}, so {@code
   * isTerritoryAllied(player)} correctly returns {@code true} — Bulgaria is included in defend
   * options without any change to that predicate.
   */
  @Test
  void defendOptionsIncludeFriendlyNeutralBulgariaWhenGermanUnitIsInAdjacentRomania() {
    final Territory romania = gameData.getMap().getTerritoryOrThrow("Romania");
    final Territory bulgaria = gameData.getMap().getTerritoryOrThrow("Bulgaria");

    // Precondition: Romania must be adjacent to Bulgaria on the Global 1940 map.
    assertThat(gameData.getMap().getNeighbors(romania))
        .as("Romania must be adjacent to Bulgaria in Global 1940")
        .contains(bulgaria);

    // Place a German infantry in Romania so ProData sees Romania as a unit territory.
    final UnitType infantry = gameData.getUnitTypeList().getUnitTypeOrThrow("infantry");
    final Unit germanInfantry = infantry.create(1, germans).get(0);
    gameData.performChange(ChangeFactory.addUnits(romania, List.of(germanInfantry)));

    proAi.getProData().initialize(proAi);
    final ProTerritoryManager manager =
        new ProTerritoryManager(proAi.getCalc(), proAi.getProData());
    manager.populateDefenseOptions(new ArrayList<>());

    assertThat(manager.getDefendOptions().getTerritoryMap())
        .as(
            "Bulgaria (Friendly_Neutral) must be a defend-option destination when a German unit"
                + " can reach it from Romania (regression: map-room#2195)")
        .containsKey(bulgaria);
  }
}
