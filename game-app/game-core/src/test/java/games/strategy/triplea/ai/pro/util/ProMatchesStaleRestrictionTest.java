package games.strategy.triplea.ai.pro.util;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

/**
 * PR-J (map-room#2755): the stateless sidecar can't fire the XML trigger that clears the US's
 * {@code movementRestrictionTerritories} list on war declaration, so the static list persists and
 * blocks US transport reachability into Atlantic transit zones like SZ 103. This patches the AI's
 * sea-movement predicate to bypass the stale-XML restriction when the player is at war with at
 * least one axis power — mirroring what the live TS engine's {@code getUSNeutralityRestriction}
 * does procedurally.
 */
class ProMatchesStaleRestrictionTest {

  /**
   * Sea zone in the 1940 Global US movement-restriction "disallowed" list — i.e. the AI's predicate
   * rejects this for the US while neutral, even though it's empty water. After war the predicate
   * should accept it.
   */
  private static final String RESTRICTED_ATLANTIC = "103 Sea Zone";

  /**
   * Sea zone NOT in the US restriction list — a control case that should be accepted regardless.
   */
  private static final String UNRESTRICTED_ATLANTIC = "102 Sea Zone";

  private GameData data;
  private GamePlayer americans;
  private GamePlayer germans;

  @BeforeEach
  void setUp() throws Exception {
    ClientSetting.setPreferences(new MemoryPreferences());
    data = TestMapGameData.GLOBAL1940.getGameData();
    americans = data.getPlayerList().getPlayerId("Americans");
    germans = data.getPlayerList().getPlayerId("Germans");
  }

  @Test
  void restrictedAtlanticZone_blockedAtGameStart_whenUSNeutral() {
    final Territory sz103 = data.getMap().getTerritoryOrThrow(RESTRICTED_ATLANTIC);
    final Predicate<Territory> canMoveSea = ProMatches.territoryCanMoveSeaUnits(americans, true);

    assertThat(canMoveSea.test(sz103))
        .as("Baseline: at game start US is neutral with all axis — XML restriction stands")
        .isFalse();
  }

  @Test
  void restrictedAtlanticZone_acceptedAfterUSAtWarWithGermans() {
    setRelationship(americans, germans, "War");
    final Territory sz103 = data.getMap().getTerritoryOrThrow(RESTRICTED_ATLANTIC);
    final Predicate<Territory> canMoveSea = ProMatches.territoryCanMoveSeaUnits(americans, true);

    assertThat(canMoveSea.test(sz103))
        .as(
            "After war with Germans, the AI predicate must accept SZ 103 — the XML trigger that"
                + " clears the restriction can't fire in the sidecar, so PR-J simulates it")
        .isTrue();
  }

  @Test
  void unrestrictedZone_acceptedRegardlessOfWarState() {
    final Territory sz102 = data.getMap().getTerritoryOrThrow(UNRESTRICTED_ATLANTIC);
    final Predicate<Territory> canMoveSea = ProMatches.territoryCanMoveSeaUnits(americans, true);
    assertThat(canMoveSea.test(sz102))
        .as("SZ 102 is NOT in the restriction list — accepted at game start")
        .isTrue();

    setRelationship(americans, germans, "War");
    assertThat(canMoveSea.test(sz102))
        .as("SZ 102 still accepted post-war — PR-J doesn't affect non-restricted zones")
        .isTrue();
  }

  @Test
  void otherPlayer_notAffectedByBypass() {
    // Bypass is keyed off the player's own restriction list + their own war state. A different
    // player asking about the same SZ shouldn't be affected, since they have a different
    // restriction list (or none).
    setRelationship(americans, germans, "War"); // Americans at war, irrelevant to Germans
    final Territory sz103 = data.getMap().getTerritoryOrThrow(RESTRICTED_ATLANTIC);
    final Predicate<Territory> germansCanMove = ProMatches.territoryCanMoveSeaUnits(germans, true);

    // Germans don't have SZ 103 in their restriction list and aren't subject to neutrality
    // act — they pass the base predicate without needing the bypass.
    assertThat(germansCanMove.test(sz103))
        .as("Other players' reachability isn't widened by US's bypass")
        .isTrue();
  }

  @Test
  void impassableTerritory_stillBlockedEvenAtWar() {
    // The bypass only relaxes the restriction-list check. Impassable territories must stay
    // blocked. Lake Baikal (impassable) is the canonical test fixture for this.
    setRelationship(americans, germans, "War");
    final Territory impassable = findFirstImpassable();
    if (impassable == null) {
      return; // No impassable in scenario — skip silently
    }
    final Predicate<Territory> canMoveSea = ProMatches.territoryCanMoveSeaUnits(americans, true);
    assertThat(canMoveSea.test(impassable))
        .as("PR-J must not bypass the impassable check")
        .isFalse();
  }

  // --- helpers ---

  private void setRelationship(final GamePlayer a, final GamePlayer b, final String relName) {
    data.performChange(
        ChangeFactory.relationshipChange(
            a,
            b,
            data.getRelationshipTracker().getRelationshipType(a, b),
            data.getRelationshipTypeList().getRelationshipType(relName)));
  }

  private Territory findFirstImpassable() {
    return data.getMap().getTerritories().stream()
        .filter(t -> games.strategy.triplea.delegate.Matches.territoryIsImpassable().test(t))
        .findFirst()
        .orElse(null);
  }
}
