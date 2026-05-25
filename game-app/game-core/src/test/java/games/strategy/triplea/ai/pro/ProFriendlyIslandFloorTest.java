package games.strategy.triplea.ai.pro;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.ai.pro.util.ProTerritoryValueUtils;
import games.strategy.triplea.xml.TestMapGameData;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression test for #2747: the island production floor introduced in #2736 was over-scoped to all
 * island territories regardless of ownership. Friendly islands like West Indies (UK-owned,
 * production=1) were getting a floor of 0.5, which made them the highest-value amphib unload
 * destination for US East Coast transports when no real enemy target was within range.
 *
 * <p>Fix: gate the floor on {@code territoryIsEnemyOrCantBeHeld(player, territoriesThatCantBeHeld)}
 * so friendly islands fall back to whatever the proximity math produces (usually 0 unless adjacent
 * to enemies). Enemy islands retain the floor from #2736.
 */
public class ProFriendlyIslandFloorTest {

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
   * Friendly islands score 0.0 — the floor should not lift them above the proximity-math result.
   * West Indies is the regression case: UK-owned, production=1, far from any Axis adjacency,
   * accumulated 0.5 via the unconditional floor pre-fix.
   */
  @Test
  void westIndiesScoresZeroInTerritoryValueMap() {
    final Territory westIndies = data.getMap().getTerritoryOrThrow("West Indies");
    final Set<Territory> toCheck = new HashSet<>();
    toCheck.add(westIndies);

    final Map<Territory, Double> values =
        ProTerritoryValueUtils.findTerritoryValues(
            proData, americans, List.of(), List.of(), toCheck);

    assertThat(values.get(westIndies))
        .as(
            "West Indies (UK-allied, production=1) must score 0.0 from Americans' perspective."
                + " The island floor introduced in #2736 should only apply to enemy attack"
                + " candidates, not friendly territory.")
        .isEqualTo(0.0);
  }

  /**
   * Enemy islands still receive the floor — verifies the #2736 fix is preserved. We pick a known
   * Japanese-owned island that should always be present in G40.
   */
  @Test
  void enemyIslandsStillReceiveTheFloor() {
    // Declare war on Japan so Japanese territories satisfy isEnemyOrCantBeHeld and the floor
    // gate fires. Iwo Jima is Japanese-owned, production=1, an island in G40 — the canonical
    // #2736 regression case.
    final GamePlayer japanese = data.getPlayerList().getPlayerId("Japanese");
    data.performChange(
        ChangeFactory.relationshipChange(
            americans,
            japanese,
            data.getRelationshipTracker().getRelationshipType(americans, japanese),
            data.getRelationshipTypeList().getRelationshipType("War")));

    final Territory iwoJima = data.getMap().getTerritoryOrThrow("Iwo Jima");
    final Set<Territory> toCheck = new HashSet<>();
    toCheck.add(iwoJima);

    final Map<Territory, Double> values =
        ProTerritoryValueUtils.findTerritoryValues(
            proData, americans, List.of(), List.of(), toCheck);

    assertThat(values.get(iwoJima))
        .as(
            "Iwo Jima (Japanese, production=1) must retain the island floor from #2736 —"
                + " scoping the floor to enemy candidates must not regress that fix.")
        .isGreaterThanOrEqualTo(0.5);
  }

  private static ProData proDataFor(final GameData gameData) throws Exception {
    final ProData proData = new ProData();
    final Field f = ProData.class.getDeclaredField("data");
    f.setAccessible(true);
    f.set(proData, gameData);
    return proData;
  }
}
