package games.strategy.triplea.ai.pro.util;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

/**
 * Verifies that ProMatches bypasses the stale {@code movementRestrictionTerritories} XML list for
 * sea and air movement predicates. The sidecar never fires TriggerAttachments, so the list (which
 * the trigger would clear on war declaration) persists and incorrectly blocks AI planning.
 *
 * <p>Covers both code paths that read {@code movementRestrictionTerritories} in ProMatches:
 *
 * <ul>
 *   <li>{@code territoryCanMoveSeaUnits} — via {@code
 *       territoryIsPassableAndNotRestrictedAndOkByRelationships}
 *   <li>{@code territoryCanMoveAirUnits} — same predicate, separate call site
 *   <li>{@code territoryCanPotentiallyMoveAirUnits} — via {@code
 *       territoryIsPassableAndNotRestricted}
 * </ul>
 *
 * <p>See map-room#2755 (PR-J bypass) and map-room#2767 (air-path gap follow-up).
 */
class ProMatchesStaleRestrictionTest {

  /** Atlantic sea zone in the US disallowed list — blocked while neutral. */
  private static final String RESTRICTED_ATLANTIC = "103 Sea Zone";

  /**
   * Atlantic sea zone NOT in the stale restriction list AND adjacent to Eastern United States —
   * control case. SZ 101 satisfies both conditions: it is absent from the XML {@code
   * movementRestrictionTerritories} list, AND Rule 2 ({@code getUSNeutralityRestriction}) allows it
   * because it borders US-controlled land. SZ 102 was the former control but is no longer suitable:
   * it has no land connections at all, so Rule 2 now correctly blocks it during neutrality.
   */
  private static final String UNRESTRICTED_ATLANTIC = "101 Sea Zone";

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

  private void setAtWarWithGermans() {
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.relationshipChange(
            americans,
            germans,
            data.getRelationshipTracker().getRelationshipType(americans, germans),
            data.getRelationshipTypeList().getRelationshipType("War")));
  }

  // ── Sea unit tests (restores map-room#2755 / triplea#143 coverage) ──────────

  @Nested
  class SeaUnits {

    @Test
    void restrictedZoneBlockedAtGameStart() {
      final Territory sz103 = data.getMap().getTerritoryOrThrow(RESTRICTED_ATLANTIC);
      final Predicate<Territory> canMoveSea = ProMatches.territoryCanMoveSeaUnits(americans, true);

      assertThat(canMoveSea.test(sz103))
          .as("Baseline: US neutral, XML restriction blocks SZ 103")
          .isFalse();
    }

    @Test
    void restrictedZoneAcceptedAfterWarWithGermans() {
      setAtWarWithGermans();
      final Territory sz103 = data.getMap().getTerritoryOrThrow(RESTRICTED_ATLANTIC);
      final Predicate<Territory> canMoveSea = ProMatches.territoryCanMoveSeaUnits(americans, true);

      assertThat(canMoveSea.test(sz103))
          .as("After war with Germans, AI bypass must accept SZ 103 for sea units")
          .isTrue();
    }

    @Test
    void unrestrictedZoneAcceptedRegardlessOfWarState() {
      final Territory sz102 = data.getMap().getTerritoryOrThrow(UNRESTRICTED_ATLANTIC);
      final Predicate<Territory> canMoveSea = ProMatches.territoryCanMoveSeaUnits(americans, true);

      assertThat(canMoveSea.test(sz102))
          .as("SZ 102 not in restriction list — accepted neutral")
          .isTrue();

      setAtWarWithGermans();
      assertThat(canMoveSea.test(sz102)).as("SZ 102 still accepted post-war").isTrue();
    }
  }

  // ── Air unit tests (map-room#2767 — gap not covered by triplea#143) ─────────

  @Nested
  class AirUnits {

    @Test
    void restrictedZoneBlockedAtGameStart() {
      final Territory sz103 = data.getMap().getTerritoryOrThrow(RESTRICTED_ATLANTIC);
      final Predicate<Territory> canMoveAir =
          ProMatches.territoryCanMoveAirUnits(data, americans, true);

      assertThat(canMoveAir.test(sz103))
          .as("Baseline: US neutral, XML restriction blocks SZ 103 for air units")
          .isFalse();
    }

    @Test
    void restrictedZoneAcceptedAfterWarWithGermans() {
      setAtWarWithGermans();
      final Territory sz103 = data.getMap().getTerritoryOrThrow(RESTRICTED_ATLANTIC);
      final Predicate<Territory> canMoveAir =
          ProMatches.territoryCanMoveAirUnits(data, americans, true);

      assertThat(canMoveAir.test(sz103))
          .as("After war with Germans, AI bypass must accept SZ 103 for air units")
          .isTrue();
    }

    @Test
    void unrestrictedZoneAcceptedRegardlessOfWarState() {
      final Territory sz102 = data.getMap().getTerritoryOrThrow(UNRESTRICTED_ATLANTIC);
      final Predicate<Territory> canMoveAir =
          ProMatches.territoryCanMoveAirUnits(data, americans, true);

      assertThat(canMoveAir.test(sz102)).as("SZ 102 not in list — accepted neutral").isTrue();

      setAtWarWithGermans();
      assertThat(canMoveAir.test(sz102)).as("SZ 102 still accepted post-war").isTrue();
    }
  }

  // ── Potential air unit tests ─────────────────────────────────────────────────

  @Nested
  class PotentialAirUnits {

    @Test
    void restrictedZoneBlockedAtGameStart() {
      final Territory sz103 = data.getMap().getTerritoryOrThrow(RESTRICTED_ATLANTIC);
      final Predicate<Territory> canPotentiallyMoveAir =
          ProMatches.territoryCanPotentiallyMoveAirUnits(americans);

      assertThat(canPotentiallyMoveAir.test(sz103))
          .as("Baseline: potential air check blocks SZ 103 while US neutral")
          .isFalse();
    }

    @Test
    void restrictedZoneAcceptedAfterWarWithGermans() {
      setAtWarWithGermans();
      final Territory sz103 = data.getMap().getTerritoryOrThrow(RESTRICTED_ATLANTIC);
      final Predicate<Territory> canPotentiallyMoveAir =
          ProMatches.territoryCanPotentiallyMoveAirUnits(americans);

      assertThat(canPotentiallyMoveAir.test(sz103))
          .as("After war, potential air check must accept SZ 103 so path-planning is not blocked")
          .isTrue();
    }
  }
}
