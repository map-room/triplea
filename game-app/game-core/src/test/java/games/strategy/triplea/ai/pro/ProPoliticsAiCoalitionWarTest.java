package games.strategy.triplea.ai.pro;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.RelationshipTracker;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.attachments.PoliticalActionAttachment;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

/**
 * Regression for map-room/map-room#2761: {@link ProPoliticsAi} declared war on only one coalition
 * member per turn, leaving the rest at peace even after the war globalized — including the case
 * (chase's review on triplea#137) where the war started on another player's turn and the
 * primary-target action is no longer in {@code enemyMap}.
 *
 * <p>The fix is the static helper {@link ProPoliticsAi#findCoalitionFollowUps}: given a list of
 * anchor players, it returns enemy actions whose targets share an in-game alliance with any anchor.
 * The caller uses it twice per politics phase:
 *
 * <ul>
 *   <li><b>Pre-roll</b> with anchors = the current player's existing enemies, so coalition partners
 *       of a war that globalized on someone else's turn get declared on without waiting for a
 *       successful per-player roll.
 *   <li><b>Post-roll</b> with anchors = the rolled action's target players, propagating the
 *       declaration across that target's coalition.
 * </ul>
 */
class ProPoliticsAiCoalitionWarTest {

  private GameData data;
  private RelationshipTracker relationshipTracker;
  private GamePlayer americans;
  private GamePlayer japanese;
  private GamePlayer germans;
  private GamePlayer italians;
  private GamePlayer russians;

  @BeforeEach
  void setUp() {
    ClientSetting.setPreferences(new MemoryPreferences());
    data = TestMapGameData.GLOBAL1940.getGameData();
    relationshipTracker = data.getRelationshipTracker();
    americans = data.getPlayerList().getPlayerId("Americans");
    japanese = data.getPlayerList().getPlayerId("Japanese");
    germans = data.getPlayerList().getPlayerId("Germans");
    italians = data.getPlayerList().getPlayerId("Italians");
    russians = data.getPlayerList().getPlayerId("Russians");

    // Sanity: the global-1940 starting state has axis players allied with each other and
    // non-allied with the relevant allied power(s). Hard-assert so a future map change
    // surfaces here rather than as misleading helper-output failures.
    assertThat(americans).as("Americans player resolved").isNotNull();
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
  void anchorAgainstJapaneseFindsGermanAndItalianFollowUps() {
    // Post-roll-style call: anchor = [japanese] (the rolled target). enemyMap contains the
    // remaining axis actions. Helper returns Germans + Italians (both allied with Japanese).
    final PoliticalActionAttachment actionGermans = namedAction("war-on-germans");
    final PoliticalActionAttachment actionItalians = namedAction("war-on-italians");

    final Map<PoliticalActionAttachment, List<GamePlayer>> enemyMap = new LinkedHashMap<>();
    enemyMap.put(actionGermans, List.of(germans));
    enemyMap.put(actionItalians, List.of(italians));

    final List<PoliticalActionAttachment> followUps =
        ProPoliticsAi.findCoalitionFollowUps(List.of(japanese), enemyMap, relationshipTracker);

    assertThat(followUps)
        .as("declaring war on Japan should also declare war on Germany and Italy")
        .containsExactlyInAnyOrder(actionGermans, actionItalians);
  }

  @Test
  void crossCoalitionTargetIsNotFollowedUp() {
    final PoliticalActionAttachment actionRussians = namedAction("war-on-russians");

    final Map<PoliticalActionAttachment, List<GamePlayer>> enemyMap = new LinkedHashMap<>();
    enemyMap.put(actionRussians, List.of(russians));

    final List<PoliticalActionAttachment> followUps =
        ProPoliticsAi.findCoalitionFollowUps(List.of(japanese), enemyMap, relationshipTracker);

    assertThat(followUps).as("Russians are not coalition-mates of Japan — no follow-up").isEmpty();
  }

  @Test
  void emptyEnemyMapProducesNoFollowUps() {
    final Map<PoliticalActionAttachment, List<GamePlayer>> enemyMap = new LinkedHashMap<>();

    final List<PoliticalActionAttachment> followUps =
        ProPoliticsAi.findCoalitionFollowUps(List.of(japanese), enemyMap, relationshipTracker);

    assertThat(followUps).isEmpty();
  }

  @Test
  void emptyAnchorListProducesNoFollowUps() {
    // Pre-roll edge case: a player with no current enemies has no anchors. The helper must
    // return empty rather than scooping up unrelated actions.
    final PoliticalActionAttachment actionGermans = namedAction("war-on-germans");
    final Map<PoliticalActionAttachment, List<GamePlayer>> enemyMap = new LinkedHashMap<>();
    enemyMap.put(actionGermans, List.of(germans));

    final List<PoliticalActionAttachment> followUps =
        ProPoliticsAi.findCoalitionFollowUps(List.of(), enemyMap, relationshipTracker);

    assertThat(followUps).isEmpty();
  }

  @Test
  void multiAnchorAnyMatchTriggersFollowUp() {
    // Anchor mixes coalitions ([japanese, russians]). Helper should add an action targeting
    // Germans because the japanese member shares an alliance — even though russians does not.
    final PoliticalActionAttachment actionGermans = namedAction("war-on-germans");

    final Map<PoliticalActionAttachment, List<GamePlayer>> enemyMap = new LinkedHashMap<>();
    enemyMap.put(actionGermans, List.of(germans));

    final List<PoliticalActionAttachment> followUps =
        ProPoliticsAi.findCoalitionFollowUps(
            List.of(japanese, russians), enemyMap, relationshipTracker);

    assertThat(followUps).containsExactly(actionGermans);
  }

  @Test
  void usAlreadyAtWarWithJapanFindsAxisCoalitionPreRoll() {
    // chase's scenario (review on triplea#137): Japan declared war on US during Japan's turn,
    // so by the time the US politics phase runs, "declare war on Japan" is no longer a valid
    // war action — it's already war. The remaining axis (Germany, Italy) is in enemyMap with
    // ~0 attackPercentage, so the existing roll loop never picks them up.
    //
    // The pre-roll pass derives the US's current enemies (Japan) from the relationship
    // tracker and uses them as anchors. The helper then surfaces Germany + Italy from
    // enemyMap as coalition follow-ups.
    data.performChange(
        ChangeFactory.relationshipChange(
            americans,
            japanese,
            relationshipTracker.getRelationshipType(americans, japanese),
            data.getRelationshipTypeList().getRelationshipType("War")));
    assertThat(relationshipTracker.isAtWar(americans, japanese))
        .as("Setup: US-Japan must be at war for this scenario")
        .isTrue();

    final List<GamePlayer> existingEnemies = currentEnemiesOf(americans);
    assertThat(existingEnemies).as("US's existing enemies after Japan attacks").contains(japanese);

    final PoliticalActionAttachment actionGermans = namedAction("us-war-on-germans");
    final PoliticalActionAttachment actionItalians = namedAction("us-war-on-italians");
    final PoliticalActionAttachment actionRussians = namedAction("us-war-on-russians");

    final Map<PoliticalActionAttachment, List<GamePlayer>> enemyMap = new LinkedHashMap<>();
    enemyMap.put(actionGermans, List.of(germans));
    enemyMap.put(actionItalians, List.of(italians));
    enemyMap.put(actionRussians, List.of(russians));

    final List<PoliticalActionAttachment> preRoll =
        ProPoliticsAi.findCoalitionFollowUps(existingEnemies, enemyMap, relationshipTracker);

    assertThat(preRoll)
        .as(
            "US at war with Japan → declare on Japan's axis coalition (Germany + Italy), not Russia")
        .containsExactlyInAnyOrder(actionGermans, actionItalians);
  }

  private List<GamePlayer> currentEnemiesOf(final GamePlayer player) {
    final List<GamePlayer> enemies = new ArrayList<>();
    for (final GamePlayer other : data.getPlayerList().getPlayers()) {
      if (!other.equals(player) && relationshipTracker.isAtWar(player, other)) {
        enemies.add(other);
      }
    }
    return enemies;
  }

  private PoliticalActionAttachment namedAction(final String name) {
    // Real instance with distinct name — DefaultAttachment.equals/hashCode are final and
    // compare name/attachedTo fields, so Mockito mocks (all-null fields) collapse into one
    // map key. Attach to japanese as a stand-in; the helper only uses these as map keys.
    return new PoliticalActionAttachment(name, japanese, data);
  }
}
