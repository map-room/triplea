package games.strategy.triplea.ai.pro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.Constants;
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
 * Regression test for map-room#2766: Japan's movementRestrictionTerritories (8 Pacific sea zones
 * near the US mainland) are never cleared by triggerAttachment_Japanese_Unrestricted_Movement
 * because the sidecar never fires triggers. ProMatches.territoryCanMoveSeaUnits must bypass the
 * stale restriction list when Japan is at war with the US, mirroring engine movement-validator.ts
 * JAPAN_US_RESTRICTED_SEA_ZONES which is lifted by the areAtWar check.
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
   * Core fix: once Japan is at war with the US, Japan's movementRestrictionTerritories must become
   * reachable. The trigger that would clear movementRestrictionType on war entry
   * (triggerAttachment_Japanese_Unrestricted_Movement) never fires on the sidecar.
   *
   * <p>Before the fix: the predicate returns {@code false} for all restricted zones even at war —
   * Japan AI cannot plan Pearl Harbor approach, West Coast attacks, or Central Pacific operations.
   * After the fix: the predicate returns {@code true} for those zones.
   */
  @Test
  void seaUnitPredicateAllowsRestrictedZonesWhenJapanAtWarWithUs() {
    // Establish war between Japan and the US.
    data.performChange(
        ChangeFactory.relationshipChange(
            japanese,
            americans,
            data.getRelationshipTracker().getRelationshipType(japanese, americans),
            data.getRelationshipTypeList().getRelationshipType("War")));
    assertThat(japanese.isAtWar(americans))
        .as("Precondition: Japan must be at war with Americans after change")
        .isTrue();

    final Predicate<Territory> canMoveSea = ProMatches.territoryCanMoveSeaUnits(japanese, true);
    for (final Territory sz : japanRestrictedZones) {
      assertThat(canMoveSea.test(sz))
          .as(
              "territoryCanMoveSeaUnits must allow restricted zone '%s' once Japan is at war with"
                  + " the US (movementRestrictionType bypass for war entry)",
              sz.getName())
          .isTrue();
    }
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
