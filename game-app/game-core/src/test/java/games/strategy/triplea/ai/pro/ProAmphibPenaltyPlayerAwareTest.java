package games.strategy.triplea.ai.pro;

import static games.strategy.triplea.delegate.GameDataTestUtil.destroyer;
import static games.strategy.triplea.delegate.GameDataTestUtil.infantry;
import static games.strategy.triplea.delegate.GameDataTestUtil.transport;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.player.PlayerBridge;
import games.strategy.triplea.ai.pro.logging.ProLogCapture;
import games.strategy.triplea.delegate.Matches;
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
 * Regression test for #2624: ProAi amphib penalty is not player-aware.
 *
 * <p>Root cause: {@code prioritizeAttackOptions} applies {@code (1 - 0.5 * isAmphib)} and hides the
 * {@code isEmptyLand} bonus from amphib targets regardless of whether the player has any overland
 * alternative. For island powers (US, UK, Japan in G40) amphib is the primary attack vector, so the
 * penalty causes the planner to generate 1–4-step combat plans and default to build+reposition
 * indefinitely — the "Americans/British do nothing" pattern.
 *
 * <p>Fix: when ALL available attack options require amphib units the player has no overland
 * alternative this round. The amphib penalty is suppressed ({@code 1.0} multiplier) and undefended
 * amphib targets receive the {@code isEmptyLand} bonus, matching the treatment of equivalent land
 * targets. This is computed directly from the attack-option set rather than from player identity or
 * map geography, so it applies to any player in any game state where only amphib attacks are
 * available.
 */
public class ProAmphibPenaltyPlayerAwareTest {

  private GameData data;
  private GamePlayer americans;
  private GamePlayer germans;
  private GamePlayer russians;

  @BeforeEach
  void setUp() {
    ClientSetting.setPreferences(new MemoryPreferences());
    data = TestMapGameData.GLOBAL1940.getGameData();
    americans = data.getPlayerList().getPlayerId("Americans");
    germans = data.getPlayerList().getPlayerId("Germans");
    russians = data.getPlayerList().getPlayerId("Russians");
  }

  /**
   * Integration regression (acceptance criterion 1): the ProAi combat-move planner for Americans
   * (at war with Germany, no land route to Germany) must emit at least one amphibious unload move
   * when a pre-loaded transport is staged adjacent to a German-held coastline.
   *
   * <p>Scenario: Americans declared war on Germans. One US transport + destroyer sit in a sea zone
   * adjacent to a German-owned land territory (found dynamically — avoids map-topology coupling).
   * All enemy naval forces in adjacent sea zones are removed so the transport is not exposed. All
   * land units are removed from every German land territory adjacent to the staging sea zone so the
   * attack is trivially winnable.
   *
   * <p>Before the fix: the amphib penalty ({@code * 0.5}) combined with the hidden {@code
   * isEmptyLand} bonus meant the territory's attack value was depressed well below equivalent land
   * targets, causing the planner to skip the attack. After the fix, the island-power detection
   * lifts both penalties and the planner emits an amphib chain.
   */
  @Test
  void americansAmphibCombatPlanEmittedAgainstGermanCoast() {
    // Declare war: Americans vs Germans.
    data.performChange(
        ChangeFactory.relationshipChange(
            americans,
            germans,
            data.getRelationshipTracker().getRelationshipType(americans, germans),
            data.getRelationshipTypeList().getRelationshipType("War")));

    // Find any German-owned land territory that has an adjacent sea zone to use as the
    // staging point for the US naval group. Avoids hardcoding G40 map topology.
    Territory germanCoastalTerritory = null;
    Territory stagingSeaZone = null;
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater() && t.isOwnedBy(germans)) {
        for (final Territory neighbor : data.getMap().getNeighbors(t)) {
          if (neighbor.isWater()) {
            germanCoastalTerritory = t;
            stagingSeaZone = neighbor;
            break;
          }
        }
        if (stagingSeaZone != null) {
          break;
        }
      }
    }
    assumeTrue(
        stagingSeaZone != null,
        "G40 must have at least one German land territory adjacent to water");

    // Clear the target territory of all defenders so the attack is trivially winnable.
    data.performChange(
        ChangeFactory.removeUnits(germanCoastalTerritory, germanCoastalTerritory.getUnits()));

    // Remove all non-American units from every territory (land and sea) so
    // removeTerritoriesWhereTransportsAreExposed doesn't cancel the attack.
    // The planner treats all non-allied players as potential enemies (#2625), including neutral
    // British. Clearing only declared-war enemies is insufficient: British air units on land have
    // the range to reach the staging sea zone and count as threat units in the exposure check.
    // Clearing all non-American units everywhere keeps enemyTUVSwing=0 at the staging sea zone
    // while preserving German territory ownership (the actual attack target).
    for (final Territory t : data.getMap().getTerritories()) {
      data.performChange(
          ChangeFactory.removeUnits(t, t.getMatches(Matches.unitIsOwnedBy(americans).negate())));
    }

    // Place a US destroyer (naval support) and a pre-loaded transport in the staging sea zone.
    final Unit trn = transport(data).create(1, americans).get(0);
    final Unit inf = infantry(data).create(1, americans).get(0);
    final Unit dest = destroyer(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(stagingSeaZone, List.of(dest, trn, inf)));
    inf.setTransportedBy(trn);

    assertThat(trn.getTransporting(stagingSeaZone))
        .as("pre-condition: transport must carry the infantry")
        .containsExactly(inf);

    final ProAi proAi = new ProAi("Test Americans", "Americans AI");
    final PlayerBridge playerBridgeMock = Mockito.mock(PlayerBridge.class);
    proAi.initialize(playerBridgeMock, americans);
    when(playerBridgeMock.getGameData()).thenReturn(data);

    final List<MoveDescription> dispatched = new ArrayList<>();
    final IMoveDelegate moveDelegate = Mockito.mock(IMoveDelegate.class);
    when(moveDelegate.performMove(any()))
        .then(
            inv -> {
              dispatched.add(inv.getArgument(0));
              return Optional.empty();
            });

    try (ProLogCapture log = new ProLogCapture()) {
      proAi.invokeCombatMoveForSidecar(moveDelegate, data, americans);

      // The planner must enter island-power mode (all options are amphib → penalty suppressed).
      assertThat(log.getLines())
          .as("planner must detect island-power mode (all attack options are amphib)")
          .anyMatch(l -> l.contains("island-power mode") && l.contains("true"));
    }

    // Assert: at least one sea→land (amphib unload) move dispatched.
    // Before fix: 0 (penalty halved territory value, isEmptyLand bonus hidden).
    // After fix: ≥1 (island-power mode lifts both penalties when all options are amphib).
    final long amphibUnloads =
        dispatched.stream()
            .filter(m -> m.getRoute().getStart().isWater() && !m.getRoute().getEnd().isWater())
            .count();
    assertThat(amphibUnloads)
        .as(
            "ProAi must dispatch ≥1 amphib unload for Americans at war with Germany when a loaded"
                + " transport is staged adjacent to an empty German coastline."
                + " All dispatched moves: "
                + dispatched)
        .isGreaterThan(0);
  }

  /**
   * Counter-regression (acceptance criterion 2): once Germany has land attack options (attack
   * options are NOT all amphib), the amphib penalty applies normally. Germany attacks Russia via
   * land — the formula must not change for the non-amphib attacks.
   *
   * <p>Verification: explicitly declare Germany-Russia war so Germany has land attack options
   * against Soviet territories bordering German-controlled territory. At least one land attack
   * (sea→non-water route) must be dispatched. The isIslandPower flag would be {@code false} because
   * not all attack options are amphib, and the formula is unchanged.
   */
  @Test
  void germanyGeneratesLandAttacksWithPenaltyIntact() {
    // Declare Germany-Russia war so Germany has land attack targets.
    data.performChange(
        ChangeFactory.relationshipChange(
            germans,
            russians,
            data.getRelationshipTracker().getRelationshipType(germans, russians),
            data.getRelationshipTypeList().getRelationshipType("War")));

    final ProAi proAi = new ProAi("Test Germans", "Germans AI");
    final PlayerBridge playerBridgeMock = Mockito.mock(PlayerBridge.class);
    proAi.initialize(playerBridgeMock, germans);
    when(playerBridgeMock.getGameData()).thenReturn(data);

    final List<MoveDescription> dispatched = new ArrayList<>();
    final IMoveDelegate moveDelegate = Mockito.mock(IMoveDelegate.class);
    when(moveDelegate.performMove(any()))
        .then(
            inv -> {
              dispatched.add(inv.getArgument(0));
              return Optional.empty();
            });

    try (ProLogCapture log = new ProLogCapture()) {
      proAi.invokeCombatMoveForSidecar(moveDelegate, data, germans);

      // Germany has land options → must NOT enter island-power mode.
      assertThat(log.getLines())
          .as("German planner must NOT enter island-power mode (has land attack options)")
          .anyMatch(l -> l.contains("island-power mode") && l.contains("false"));
    }

    // Germany must produce land-to-land attacks against Soviet territory.
    // If isIslandPower were wrongly set to true for Germany (regression), the planner's
    // formula would over-value amphib targets — but land attacks would still be generated
    // since Germany has abundant land forces adjacent to Russia.
    final long landAttacks =
        dispatched.stream()
            .filter(m -> !m.getRoute().getStart().isWater() && !m.getRoute().getEnd().isWater())
            .count();
    assertThat(landAttacks)
        .as(
            "German planner must produce ≥1 land attack when at war with Russia (not all-amphib,"
                + " so isIslandPower=false, penalty applies normally)."
                + " All dispatched: "
                + dispatched)
        .isGreaterThan(0);
  }
}
