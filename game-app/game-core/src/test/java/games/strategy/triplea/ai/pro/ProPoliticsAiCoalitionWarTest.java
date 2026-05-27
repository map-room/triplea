package games.strategy.triplea.ai.pro;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.RelationshipTracker;
import games.strategy.triplea.attachments.PoliticalActionAttachment;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

/**
 * Regression for map-room/map-room#2761: ProPoliticsAi declared war on only one coalition member
 * per turn, leaving the rest at peace even after the war globalized.
 *
 * <p>The fix is in {@link ProPoliticsAi#findCoalitionFollowUps}: after a successful war
 * declaration, the helper enumerates other enemy war actions whose targets share an in-game
 * alliance with the primary target, so the caller declares on them too without rerolling.
 */
class ProPoliticsAiCoalitionWarTest {

  private GameData data;
  private RelationshipTracker relationshipTracker;
  private GamePlayer japanese;
  private GamePlayer germans;
  private GamePlayer italians;
  private GamePlayer russians;

  @BeforeEach
  void setUp() {
    ClientSetting.setPreferences(new MemoryPreferences());
    data = TestMapGameData.GLOBAL1940.getGameData();
    relationshipTracker = data.getRelationshipTracker();
    japanese = data.getPlayerList().getPlayerId("Japanese");
    germans = data.getPlayerList().getPlayerId("Germans");
    italians = data.getPlayerList().getPlayerId("Italians");
    russians = data.getPlayerList().getPlayerId("Russians");

    // Sanity: the global-1940 starting state has axis players allied with each other and
    // non-allied with at least one non-axis power. Hard-assert so a future map change
    // surfaces here rather than as misleading helper-output failures.
    assertThat(japanese).as("Japanese player resolved").isNotNull();
    assertThat(germans).as("Germans player resolved").isNotNull();
    assertThat(italians).as("Italians player resolved").isNotNull();
    assertThat(russians).as("Russians player resolved").isNotNull();
    assertThat(relationshipTracker.isAllied(japanese, germans))
        .as("Japanese-Germans starting alliance")
        .isTrue();
    assertThat(relationshipTracker.isAllied(japanese, italians))
        .as("Japanese-Italians starting alliance")
        .isTrue();
    assertThat(relationshipTracker.isAllied(japanese, russians))
        .as("Japanese-Russians starting alliance is false")
        .isFalse();
  }

  @Test
  void primaryActionAgainstJapaneseAddsGermanAndItalianFollowUps() {
    final PoliticalActionAttachment actionJapanese = namedAction("war-on-japanese");
    final PoliticalActionAttachment actionGermans = namedAction("war-on-germans");
    final PoliticalActionAttachment actionItalians = namedAction("war-on-italians");

    final Map<PoliticalActionAttachment, List<GamePlayer>> enemyMap = new LinkedHashMap<>();
    enemyMap.put(actionJapanese, List.of(japanese));
    enemyMap.put(actionGermans, List.of(germans));
    enemyMap.put(actionItalians, List.of(italians));

    final List<PoliticalActionAttachment> followUps =
        ProPoliticsAi.findCoalitionFollowUps(actionJapanese, enemyMap, relationshipTracker);

    assertThat(followUps)
        .as("declaring war on Japan should also declare war on Germany and Italy")
        .containsExactlyInAnyOrder(actionGermans, actionItalians);
  }

  @Test
  void crossCoalitionTargetsAreNotFollowedUp() {
    final PoliticalActionAttachment actionJapanese = namedAction("war-on-japanese");
    final PoliticalActionAttachment actionRussians = namedAction("war-on-russians");

    final Map<PoliticalActionAttachment, List<GamePlayer>> enemyMap = new LinkedHashMap<>();
    enemyMap.put(actionJapanese, List.of(japanese));
    enemyMap.put(actionRussians, List.of(russians));

    final List<PoliticalActionAttachment> followUps =
        ProPoliticsAi.findCoalitionFollowUps(actionJapanese, enemyMap, relationshipTracker);

    assertThat(followUps).as("Russians are not coalition-mates of Japan — no follow-up").isEmpty();
  }

  @Test
  void singleEnemyEnemyMapProducesNoFollowUps() {
    final PoliticalActionAttachment actionJapanese = namedAction("war-on-japanese");

    final Map<PoliticalActionAttachment, List<GamePlayer>> enemyMap = new LinkedHashMap<>();
    enemyMap.put(actionJapanese, List.of(japanese));

    final List<PoliticalActionAttachment> followUps =
        ProPoliticsAi.findCoalitionFollowUps(actionJapanese, enemyMap, relationshipTracker);

    assertThat(followUps).isEmpty();
  }

  @Test
  void actionsWithMultiPlayerTargetsMatchIfAnyTargetIsAllied() {
    // Action A targets [Japanese, Russians] (mixed coalition — rare but legal in custom maps).
    // Action B targets [Germans]. Since Japanese is allied with Germans, A and B share a
    // coalition through the Japanese-Germans link.
    final PoliticalActionAttachment actionMixed = namedAction("war-on-japanese-and-russians");
    final PoliticalActionAttachment actionGermans = namedAction("war-on-germans");

    final Map<PoliticalActionAttachment, List<GamePlayer>> enemyMap = new LinkedHashMap<>();
    enemyMap.put(actionMixed, List.of(japanese, russians));
    enemyMap.put(actionGermans, List.of(germans));

    final List<PoliticalActionAttachment> followUps =
        ProPoliticsAi.findCoalitionFollowUps(actionMixed, enemyMap, relationshipTracker);

    assertThat(followUps).containsExactly(actionGermans);
  }

  private PoliticalActionAttachment namedAction(final String name) {
    // Real instance with distinct name — DefaultAttachment.equals/hashCode are final and
    // compare name/attachedTo fields, so Mockito mocks (all-null fields) collapse into one
    // map key. Attach to japanese as a stand-in; the helper only uses these as map keys.
    return new PoliticalActionAttachment(name, japanese, data);
  }
}
