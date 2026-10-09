package games.strategy.triplea.ai.pro;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.ai.pro.util.ProTerritoryValueUtils;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.xml.TestMapGameData;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression for map-room#2736: a reachable Pacific island with production 0 (Marshall Islands)
 * scored territoryValue 0, so ProAi never kept the amphibious landing. The production floor is
 * {@code production * 0.5}, which is 0. The zero-IPC island floor must be strictly positive, and
 * must not apply to True Neutrals (#2745) or friendly islands (#2747).
 */
public class ProZeroIpcIslandValueTest {

  private GameData data;
  private GamePlayer americans;
  private ProData proData;

  @BeforeEach
  void setUp() throws Exception {
    data = TestMapGameData.GLOBAL1940.getGameData();
    americans = data.getPlayerList().getPlayerId("Americans");
    proData = proDataFor(data);
  }

  /**
   * Marshall Islands is Japanese, production 0, and adjacent to a sea zone, so a US transport can
   * reach it. At war, and marked can't-hold the way a round-3 Japanese counterattack marks it, the
   * strategic value is the zero-IPC island floor rather than 0.
   */
  @Test
  void marshallIslandsScoresTheZeroIpcFloorWhenItCannotBeHeld() {
    declareWarOnJapan();
    final Territory marshall = data.getMap().getTerritoryOrThrow("Marshall Islands");
    assertThat(TerritoryAttachment.getProduction(marshall)).isEqualTo(0);
    assertThat(data.getMap().getNeighbors(marshall))
        .as("Marshall Islands must be a reachable amphib target (adjacent to water)")
        .anyMatch(Territory::isWater);

    final Map<Territory, Double> values =
        ProTerritoryValueUtils.findTerritoryValues(
            proData, americans, List.of(marshall), List.of(), Set.of(marshall));

    assertThat(values.get(marshall))
        .as("can't-hold 0-IPC Marshall Islands must score the island raid floor, not 0")
        .isEqualTo(ProTerritoryValueUtils.ZERO_IPC_ISLAND_FLOOR);
    assertThat(ProTerritoryValueUtils.ZERO_IPC_ISLAND_FLOOR).isGreaterThan(0.0);
  }

  /** Same island when the AI believes it can hold the landing still must not score 0. */
  @Test
  void marshallIslandsScoresNonZeroWhenItCanBeHeld() {
    declareWarOnJapan();
    final Territory marshall = data.getMap().getTerritoryOrThrow("Marshall Islands");
    final Set<Territory> toCheck = new HashSet<>();
    toCheck.add(marshall);

    final Map<Territory, Double> values =
        ProTerritoryValueUtils.findTerritoryValues(
            proData, americans, List.of(), List.of(), toCheck);

    assertThat(values.get(marshall))
        .as(
            "enemy 0-IPC Marshall Islands must stay above 0 even when it is not in the can't-hold set")
        .isGreaterThanOrEqualTo(ProTerritoryValueUtils.ZERO_IPC_ISLAND_FLOOR);
  }

  private void declareWarOnJapan() {
    final GamePlayer japanese = data.getPlayerList().getPlayerId("Japanese");
    data.performChange(
        ChangeFactory.relationshipChange(
            americans,
            japanese,
            data.getRelationshipTracker().getRelationshipType(americans, japanese),
            data.getRelationshipTypeList().getRelationshipType("War")));
  }

  private static ProData proDataFor(final GameData gameData) throws Exception {
    final ProData proData = new ProData();
    final Field f = ProData.class.getDeclaredField("data");
    f.setAccessible(true);
    f.set(proData, gameData);
    return proData;
  }
}
