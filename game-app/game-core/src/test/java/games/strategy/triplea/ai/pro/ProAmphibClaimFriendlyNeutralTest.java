package games.strategy.triplea.ai.pro;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.RelationshipType;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.player.PlayerBridge;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

/**
 * Regression tests for {@link ProNonCombatMoveAi#claimFriendlyNeutralTerritories} amphib path
 * (triplea#144).
 *
 * <p>Before the fix the claim step only iterated {@code unitMoveMap} (land-adjacent moves), so
 * sea-isolated Friendly_Neutral territories — Brazil, Liberia, Saudi Arabia, Pacific Pro-Allies —
 * were never claimed even when a transport with loaded capacity was within range.
 *
 * <p>After the fix a parallel loop over {@code transportMapList} assigns one infantry+transport
 * pair to any sea-isolated Friendly_Neutral destination that has no land candidate.
 */
public class ProAmphibClaimFriendlyNeutralTest {

  private GameData data;
  private GamePlayer americans;

  @BeforeEach
  void setUp() {
    ClientSetting.setPreferences(new MemoryPreferences());
    data = TestMapGameData.GLOBAL1940.getGameData();
    americans = data.getPlayerList().getPlayerId("Americans");
  }

  /**
   * With a US transport at SZ 87 and a US infantry at Liberia (adjacent to SZ 87, NOT adjacent to
   * Brazil), the noncombat planner must dispatch an amphib move that ends in Brazil.
   *
   * <p>Setup topology:
   *
   * <pre>
   *   Liberia ──── 87 Sea Zone ──── 86 Sea Zone ──── Brazil
   *                                                (Neutral_Allies)
   * </pre>
   *
   * <p>Brazil has no land-adjacent Allied territory with a player unit, so the land loop in {@link
   * ProNonCombatMoveAi#claimFriendlyNeutralTerritories} leaves it unclaimed. The amphib pass must
   * pick it up (regression: triplea#144).
   */
  @Test
  void claimsSeaIsolatedBrazilViaAmphibMove() {
    final Territory liberia = data.getMap().getTerritoryOrThrow("Liberia");
    final Territory sz87 = data.getMap().getTerritoryOrThrow("87 Sea Zone");
    final Territory sz86 = data.getMap().getTerritoryOrThrow("86 Sea Zone");
    final Territory brazil = data.getMap().getTerritoryOrThrow("Brazil");

    // Preconditions: confirm the topology this test relies on.
    assertThat(data.getMap().getNeighbors(sz87)).as("SZ 87 must border Liberia").contains(liberia);
    assertThat(data.getMap().getNeighbors(sz87)).as("SZ 87 must border SZ 86").contains(sz86);
    assertThat(data.getMap().getNeighbors(sz86)).as("SZ 86 must border Brazil").contains(brazil);
    assertThat(data.getMap().getNeighbors(liberia))
        .as("Liberia must NOT be adjacent to Brazil (so no land path exists)")
        .doesNotContain(brazil);

    // Advance to post-war state: change Americans-Neutral_Allies to Friendly_Neutral.
    // In round 1 the relationship is "Neutrality" — the bug only manifests once the US is at war
    // and Neutral_Allies territories become claimable (Friendly_Neutral has archeType="allied").
    final GamePlayer neutralAllies = data.getPlayerList().getPlayerId("Neutral_Allies");
    final RelationshipType friendlyNeutral =
        data.getRelationshipTypeList().getRelationshipType("Friendly_Neutral");
    data.performChange(
        ChangeFactory.relationshipChange(
            americans,
            neutralAllies,
            data.getRelationshipTracker().getRelationshipType(americans, neutralAllies),
            friendlyNeutral));

    // Set Americans at war with Germans so the US Neutrality Act movement restriction is bypassed.
    // ProMatches.shouldBypassStaleMovementRestriction only lifts the XML
    // movementRestrictionTerritories
    // list when isAtWarWithAnyAxis(player)=true; without this, SZ 86 fails canMoveSeaThrough and
    // Brazil never enters moveMap, so toClaim is empty and the amphib pass never runs.
    final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
    final RelationshipType war = data.getRelationshipTypeList().getRelationshipType("War");
    data.performChange(
        ChangeFactory.relationshipChange(
            americans,
            germans,
            data.getRelationshipTracker().getRelationshipType(americans, germans),
            war));

    // Place a US transport at SZ 87 and a US infantry at Liberia.
    final UnitType infantryType = data.getUnitTypeList().getUnitTypeOrThrow("infantry");
    final UnitType transportType = data.getUnitTypeList().getUnitTypeOrThrow("transport");
    final Unit usInfantry = infantryType.create(1, americans).get(0);
    final Unit usTransport = transportType.create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(liberia, List.of(usInfantry)));
    data.performChange(ChangeFactory.addUnits(sz87, List.of(usTransport)));

    // Initialize ProAi for Americans.
    newDelegateBridge(americans);
    final PlayerBridge playerBridgeMock = Mockito.mock(PlayerBridge.class);
    when(playerBridgeMock.getGameData()).thenReturn(data);
    final ProAi proAi = new ProAi("test-Americans", "Americans");
    proAi.initialize(playerBridgeMock, americans);

    // Capture every MoveDescription dispatched by the noncombat planner.
    final List<MoveDescription> dispatched = new ArrayList<>();
    final IMoveDelegate moveDelegate = Mockito.mock(IMoveDelegate.class);
    when(moveDelegate.performMove(any()))
        .then(
            inv -> {
              dispatched.add(inv.getArgument(0));
              return Optional.empty();
            });

    data.getSequence().setRoundAndStep(1, "Non Combat Move", americans);
    proAi.invokeNonCombatMoveForSidecar(moveDelegate, data, americans);

    // The planner must have dispatched at least one move that ends in Brazil.
    final boolean hasBrazilMove =
        dispatched.stream().anyMatch(m -> m.getRoute().getEnd().equals(brazil));
    assertThat(hasBrazilMove)
        .as(
            "ProAi NCM must dispatch an amphib move targeting Brazil when a US transport at "
                + "SZ 87 + infantry at Liberia are available (regression: triplea#144). "
                + "All dispatched moves: "
                + dispatched)
        .isTrue();
  }

  /**
   * Regression guard for the land-path case: German infantry in Romania must still claim Bulgaria
   * (land-adjacent Friendly_Neutral) via the existing land loop. The amphib pass must not
   * interfere.
   *
   * <p>This is a re-assertion of the map-room#2195 regression test at a different level.
   */
  @Test
  void landAdjacentFriendlyNeutralBulgariaStillClaimedByGermans() {
    final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
    final Territory romania = data.getMap().getTerritoryOrThrow("Romania");
    final Territory bulgaria = data.getMap().getTerritoryOrThrow("Bulgaria");

    assertThat(data.getMap().getNeighbors(romania))
        .as("Romania must be adjacent to Bulgaria")
        .contains(bulgaria);

    final UnitType infantryType = data.getUnitTypeList().getUnitTypeOrThrow("infantry");
    final Unit germanInfantry = infantryType.create(1, germans).get(0);
    data.performChange(ChangeFactory.addUnits(romania, List.of(germanInfantry)));

    newDelegateBridge(germans);
    final PlayerBridge playerBridgeMock = Mockito.mock(PlayerBridge.class);
    when(playerBridgeMock.getGameData()).thenReturn(data);
    final ProAi proAi = new ProAi("test-Germans", "Germans");
    proAi.initialize(playerBridgeMock, germans);

    final List<MoveDescription> dispatched = new ArrayList<>();
    final IMoveDelegate moveDelegate = Mockito.mock(IMoveDelegate.class);
    when(moveDelegate.performMove(any()))
        .then(
            inv -> {
              dispatched.add(inv.getArgument(0));
              return Optional.empty();
            });

    data.getSequence().setRoundAndStep(1, "Non Combat Move", germans);
    proAi.invokeNonCombatMoveForSidecar(moveDelegate, data, germans);

    final boolean hasBulgariaMove =
        dispatched.stream().anyMatch(m -> m.getRoute().getEnd().equals(bulgaria));
    assertThat(hasBulgariaMove)
        .as(
            "ProAi NCM must dispatch a land move targeting Bulgaria when a German infantry is in "
                + "Romania (regression: map-room#2195). All dispatched: "
                + dispatched)
        .isTrue();
  }
}
