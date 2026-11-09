package games.strategy.triplea.ai.pro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.Constants;
import games.strategy.triplea.ai.pro.util.MovementRestrictionLifter;
import games.strategy.triplea.ai.pro.util.ProMatches;
import games.strategy.triplea.attachments.RulesAttachment;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

/**
 * Japan's movementRestrictionTerritories (8 Pacific sea zones near the US mainland) are cleared by
 * triggerAttachment_Japanese_Unrestricted_Movement once Japan is at war with the US. The stateless
 * sidecar never fires triggers, so {@link MovementRestrictionLifter} fires it (map-room#4709),
 * after the wire state is applied and after the purchase simulation's politics (map-room#4733).
 *
 * <p>map-room#2766 added a hardcoded bypass in ProMatches for this war. It let the planner see the
 * zones as open while MoveValidator, which reads the list, still refused them, whenever a war was
 * declared without the lift (map-room#4733). The bypass is gone: the planner and the validator read
 * the same list, and the lifter is the only thing that clears it.
 */
public class ProJapanMovementRestrictionTest {

  private GameData data;
  private GamePlayer japanese;
  private GamePlayer americans;
  private List<Territory> japanRestrictedZones;

  @BeforeEach
  void setUp() {
    ClientSetting.setPreferences(new MemoryPreferences());
    data = TestMapGameData.GLOBAL1940.getGameData();
    japanese = data.getPlayerList().getPlayerId("Japanese");
    americans = data.getPlayerList().getPlayerId("Americans");

    final RulesAttachment ra = RulesAttachment.get(japanese, Constants.RULES_ATTACHMENT_NAME);
    assumeTrue(ra != null, "Japanese player must have a RulesAttachment in G40");
    assumeTrue(
        ra.isMovementRestrictionTypeDisallowed(),
        "Japanese RulesAttachment must have movementRestrictionType=disallowed in G40");
    final String[] restricted = ra.getMovementRestrictionTerritories();
    assumeTrue(
        restricted != null && restricted.length > 0,
        "Japanese RulesAttachment must have movementRestrictionTerritories in G40");

    japanRestrictedZones =
        Arrays.stream(restricted)
            .map(name -> data.getMap().getTerritoryOrNull(name))
            .filter(t -> t != null && t.isWater())
            .toList();
    assumeTrue(
        !japanRestrictedZones.isEmpty(),
        "At least one restricted territory must be a sea zone in G40");
  }

  /**
   * Regression guard: while neutral with the US, Japan's movementRestrictionTerritories must remain
   * blocked by the predicate. This was already correct behaviour before #2766; this test ensures
   * the fix does not regress it.
   */
  @Test
  void seaUnitPredicateBlocksRestrictedZonesWhileNeutral() {
    assumeFalse(
        japanese.isAtWar(americans), "This test only applies when Japan is neutral with the US");

    final Predicate<Territory> canMoveSea = ProMatches.territoryCanMoveSeaUnits(japanese, true);
    for (final Territory sz : japanRestrictedZones) {
      assertThat(canMoveSea.test(sz))
          .as(
              "territoryCanMoveSeaUnits must block restricted zone '%s' while Japan is neutral"
                  + " with the US (movementRestrictionType=disallowed should apply)",
              sz.getName())
          .isFalse();
    }
  }

  /**
   * Once Japan is at war with the US and the lifting trigger has been fired, Japan's restricted
   * zones are reachable: Pearl Harbor approach, West Coast attacks, Central Pacific operations.
   */
  @Test
  void seaUnitPredicateAllowsRestrictedZonesWhenJapanAtWarWithUsAndTheTriggerFired() {
    declareWarOnUs();
    MovementRestrictionLifter.apply(data);

    final Predicate<Territory> canMoveSea = ProMatches.territoryCanMoveSeaUnits(japanese, true);
    for (final Territory sz : japanRestrictedZones) {
      assertThat(canMoveSea.test(sz))
          .as(
              "territoryCanMoveSeaUnits must allow restricted zone '%s' once Japan is at war with"
                  + " the US and the lifting trigger has fired",
              sz.getName())
          .isTrue();
    }
  }

  /**
   * map-room#4733: the planner must not be ahead of the validator. A war with the US that has not
   * been through the lifting trigger leaves the list binding in MoveValidator, so the predicate
   * must keep refusing the zones; otherwise ProAI plans a move the engine refuses.
   */
  @Test
  void seaUnitPredicateStillBlocksRestrictedZonesWhenTheWarHasNotLiftedTheList() {
    declareWarOnUs();

    final Predicate<Territory> canMoveSea = ProMatches.territoryCanMoveSeaUnits(japanese, true);
    for (final Territory sz : japanRestrictedZones) {
      assertThat(canMoveSea.test(sz))
          .as(
              "territoryCanMoveSeaUnits must agree with MoveValidator on '%s' while the list still"
                  + " binds",
              sz.getName())
          .isFalse();
    }
  }

  private void declareWarOnUs() {
    data.performChange(
        ChangeFactory.relationshipChange(
            japanese,
            americans,
            data.getRelationshipTracker().getRelationshipType(japanese, americans),
            data.getRelationshipTypeList().getRelationshipType("War")));
    assertThat(japanese.isAtWar(americans))
        .as("Precondition: Japan must be at war with Americans after change")
        .isTrue();
  }

  /**
   * Non-regression: the US–Japan neutrality check for Americans (map-room#2619 / commit c21271a2)
   * must not be affected by the Japan fix. Americans neutral with Japan must still be blocked from
   * sea zones adjacent to Japanese-controlled territories.
   */
  @Test
  void usNeutralityRuleNotAffectedByJapanFix() {
    assumeFalse(
        americans.isAtWar(japanese), "This test only applies when Americans is neutral with Japan");

    final List<Territory> japanAdjacentSeaZones =
        data.getMap().getTerritories().stream()
            .filter(t -> !t.isWater() && t.isOwnedBy(japanese))
            .flatMap(t -> data.getMap().getNeighbors(t).stream())
            .filter(Territory::isWater)
            .distinct()
            .toList();
    assumeTrue(!japanAdjacentSeaZones.isEmpty(), "G40 must have sea zones adjacent to Japan");

    final Predicate<Territory> canMoveSeaUs = ProMatches.territoryCanMoveSeaUnits(americans, false);
    for (final Territory sz : japanAdjacentSeaZones) {
      assertThat(canMoveSeaUs.test(sz))
          .as(
              "US neutrality Rule 1 must still block Americans from Japan-adjacent zone '%s'"
                  + " (Japan fix must not affect the US-side predicate)",
              sz.getName())
          .isFalse();
    }
  }
}
