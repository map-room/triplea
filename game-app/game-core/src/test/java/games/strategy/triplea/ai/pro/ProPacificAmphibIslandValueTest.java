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
import games.strategy.triplea.attachments.TerritoryAttachment;
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
 * Regression test for #2736: ProAi amphib targets scoring territoryValue=0 prevents US/UK Pacific
 * transport-based attacks.
 *
 * <p>The bug surfaced in a recorded ai-vs-ai log where Americans loaded and unloaded transports
 * dozens of times across rounds 1-5 but never prioritized a single Pacific enemy target — every
 * "Prioritized territories" pass was empty or contained only American capitals. Three compounding
 * formula bugs collapsed every Pacific island's score to zero: the {@code findLandValue}
 * short-circuit for {@code territoriesThatCantBeHeld} bypassed the island production floor, the
 * prioritization formula multiplies on raw production (so 0-IPC islands like Guam/Wake/Midway
 * collapse), and the {@code isIslandPower} predicate was easily falsified by any friendly land
 * route in {@code attackOptions}.
 *
 * <p>This test reproduces a Pacific-themed scenario: Americans declares war on Japan; one
 * pre-loaded US transport + destroyer sit in a sea zone adjacent to a Japanese coastal territory
 * that's been engineered into a "can't hold" state via a heavy Japanese stack. Before the fixes the
 * planner emits no amphib unload because the island's territoryValue stays at 0. After the fixes
 * the production floor on the can't-hold branch (Fix A), the additive empty-island contribution
 * (Fix B), and the enemy-only {@code isIslandPower} filter (Fix C) combine to give the target a
 * non-zero value that clears the prioritization threshold.
 */
public class ProPacificAmphibIslandValueTest {

  private GameData data;
  private GamePlayer americans;
  private GamePlayer japanese;

  @BeforeEach
  void setUp() {
    ClientSetting.setPreferences(new MemoryPreferences());
    data = TestMapGameData.GLOBAL1940.getGameData();
    americans = data.getPlayerList().getPlayerId("Americans");
    japanese = data.getPlayerList().getPlayerId("Japanese");
  }

  /**
   * Acceptance: with a pre-loaded US transport adjacent to a Japanese coastal target that has zero
   * defenders (so {@code isEmptyLand=1} on the prioritization branch), the planner emits at least
   * one amphib unload.
   *
   * <p>"Zero defenders" simulates the case where the AI has already cleared the island's garrison
   * earlier in the turn — the residual amphib chip-on-the-island should still be prioritized as an
   * empty-land seize. Before the fix the multiplicative {@code production} term in the territory
   * value formula kills the score for low-IPC Pacific islands; the additive empty-island bonus (Fix
   * B) gives it a small but non-zero base.
   */
  @Test
  void americansAmphibEmittedAgainstEmptyZeroIpcJapaneseIsland() {
    // Declare war: Americans vs Japan.
    data.performChange(
        ChangeFactory.relationshipChange(
            americans,
            japanese,
            data.getRelationshipTracker().getRelationshipType(americans, japanese),
            data.getRelationshipTypeList().getRelationshipType("War")));

    // Find any Japanese-owned land territory with an adjacent sea zone, preferring zero-production
    // ("island") territories — these are exactly the targets the multiplicative formula zeros out.
    Territory targetIsland = null;
    Territory stagingSeaZone = null;
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater() && t.isOwnedBy(japanese) && TerritoryAttachment.getProduction(t) == 0) {
        for (final Territory neighbor : data.getMap().getNeighbors(t)) {
          if (neighbor.isWater()) {
            targetIsland = t;
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
        "G40 must have at least one Japanese-owned zero-IPC land territory adjacent to water");

    // Engineer the can't-hold/empty-island case: remove all defenders from the target island
    // (the planner will mark it isEmptyLand=1 on the prioritization branch).
    data.performChange(ChangeFactory.removeUnits(targetIsland, targetIsland.getUnits()));

    // Strip all non-American units everywhere so removeTerritoriesWhereTransportsAreExposed does
    // not cancel the attack. The planner treats all non-allied players as potential enemies
    // (#2625), so leaving any neutral air range over the staging sea zone fails the exposure check.
    for (final Territory t : data.getMap().getTerritories()) {
      data.performChange(
          ChangeFactory.removeUnits(t, t.getMatches(Matches.unitIsOwnedBy(americans).negate())));
    }

    // Place a US destroyer + pre-loaded transport in the staging sea zone.
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

      // Island-power mode must engage — the enemy-only filter in isIslandPower (Fix C) keeps
      // this true even when friendly land territories happen to appear in attackOptions.
      assertThat(log.getLines())
          .as("planner must detect island-power mode for zero-IPC Pacific amphib")
          .anyMatch(l -> l.contains("island-power mode") && l.contains("true"));
    }

    // Before Fix B: territoryValue collapses to 0 because production=0 for the island, so the
    // attack is never prioritized. After Fix B: the additive isAmphibStrategicIsland bonus puts
    // the target above the priority threshold and the unload dispatches.
    final long amphibUnloads =
        dispatched.stream()
            .filter(m -> m.getRoute().getStart().isWater() && !m.getRoute().getEnd().isWater())
            .count();
    assertThat(amphibUnloads)
        .as(
            "ProAi must dispatch ≥1 amphib unload onto an empty zero-IPC Japanese island."
                + " All dispatched moves: "
                + dispatched)
        .isGreaterThan(0);
  }
}
