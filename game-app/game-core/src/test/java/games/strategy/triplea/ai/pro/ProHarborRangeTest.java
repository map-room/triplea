package games.strategy.triplea.ai.pro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.player.PlayerBridge;
import games.strategy.triplea.ai.pro.logging.ProLogCapture;
import games.strategy.triplea.delegate.GameDataTestUtil;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

/**
 * Regression tests for #2643: {@code getUnitRange} in {@code ProTerritoryManager} applied the
 * harbor +1 movement bonus only when {@code isCheckingEnemyAttacks=true}, not for own-unit movement
 * planning ({@code isCheckingEnemyAttacks=false}).
 *
 * <p>Root cause: the method's {@code isCheckingEnemyAttacks=false} branch returned {@code
 * unit.getMovementLeft()} without checking harbor bonuses. This silently under-counted own-unit
 * reach by 1 sea zone whenever a harbor applied — which is why ProAI never included Japan, Iwo
 * Jima, Korea, or Morocco in its amphib candidate set despite the #2624 formula fixes.
 *
 * <p>Fix: apply {@code Matches.unitCanBeGivenBonusMovementByFacilitiesInItsTerritory} regardless of
 * the {@code isCheckingEnemyAttacks} flag — the harbor bonus is a property of the territory, not of
 * the attack direction.
 *
 * <p>Test anchor: G40 Pacific, US transport in 26 Sea Zone (adjacent to Hawaiian Islands harbor).
 *
 * <ul>
 *   <li>26 Sea Zone path to Japan: 26→25→16→6 Sea Zone (3 hops) → unload at Japan. With standard
 *       transport movement=2 this is out of reach; with harbor bonus movement=3 it is reachable in
 *       one combat-move turn.
 *   <li>26 Sea Zone path to Iwo Jima: 26→25→16→17 Sea Zone (3 hops) → unload at Iwo Jima. Same
 *       dependency on harbor bonus.
 *   <li>Counter-test: same 26 Sea Zone start but without the Hawaiian Islands harbour unit uses
 *       movement=2 → Japan and Iwo Jima remain unreachable, confirming the fix does not over-count.
 * </ul>
 */
public class ProHarborRangeTest {

  private static final String SEA_ZONE_26 = "26 Sea Zone";
  private static final String HAWAIIAN_ISLANDS = "Hawaiian Islands";
  private static final String JAPAN = "Japan";
  private static final String IWO_JIMA = "Iwo Jima";

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
   * Acceptance criterion: US transport in 26 Sea Zone (harbor via Hawaiian Islands) must be able to
   * plan an amphib attack on Japan or Iwo Jima in a single combat-move turn.
   *
   * <p>Path requires movement=3 (harbor bonus): 26→25→16→6 Sea Zone → unload at Japan.
   *
   * <p>Before fix: movement=2 → max reach = 16 Sea Zone, Japan unreachable. After fix: movement=3 →
   * max reach = 6/17 Sea Zone, Japan and Iwo Jima reachable.
   */
  @Test
  void transportInHarborSzCanReachJapanOrIwoJimaInOneTurn() {
    declareWar(americans, japanese);

    final Territory sz26 = data.getMap().getTerritoryOrThrow(SEA_ZONE_26);
    final Territory hawaiianIslands = data.getMap().getTerritoryOrThrow(HAWAIIAN_ISLANDS);
    final Territory japan = data.getMap().getTerritoryOrThrow(JAPAN);
    final Territory iwoJima = data.getMap().getTerritoryOrThrow(IWO_JIMA);

    // Clear all units so the planner has a clean slate with only our scenario.
    for (final Territory t : data.getMap().getTerritories()) {
      data.performChange(ChangeFactory.removeUnits(t, t.getUnits()));
    }

    // Restore the Hawaiian Islands harbour.  The clearing loop above removed it along with
    // everything else; without it the +1 movement bonus cannot fire.
    final Unit harbour =
        data.getUnitTypeList().getUnitType("harbour").orElseThrow().create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(hawaiianIslands, List.of(harbour)));

    // Pre-loaded US transport in 26 Sea Zone. Hawaiian Islands (adjacent) now has a harbour unit,
    // granting the transport movement=3 via the bonus mechanic.
    final Unit transport = GameDataTestUtil.transport(data).create(1, americans).get(0);
    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(sz26, List.of(transport, infantry)));
    infantry.setTransportedBy(transport);

    assertThat(transport.getTransporting(sz26))
        .as("pre-condition: transport must carry the infantry")
        .containsExactly(infantry);

    final List<MoveDescription> dispatched = runCombatMoveFor(americans);

    // Japan and Iwo Jima are the 3-hop amphib targets gated behind the harbor bonus.
    final Set<Territory> distantTargets = Set.of(japan, iwoJima);
    final boolean anyDistantAmphibUnload =
        dispatched.stream()
            .anyMatch(
                m ->
                    m.getRoute().getStart().isWater()
                        && !m.getRoute().getEnd().isWater()
                        && distantTargets.contains(m.getRoute().getEnd()));

    assertThat(anyDistantAmphibUnload)
        .as(
            "ProAi must dispatch an amphib unload at Japan or Iwo Jima when starting from 26 Sea"
                + " Zone with Hawaiian Islands harbor (+1 movement = 3 total). Before the fix the"
                + " transport was limited to movement=2 (raw movementLeft) and neither target was"
                + " reachable. Dispatched: "
                + dispatched)
        .isTrue();
  }

  /**
   * Counter-regression: same 26 Sea Zone start but WITHOUT the Hawaiian Islands harbour must NOT
   * reach Japan or Iwo Jima — movement stays at 2 and neither target is within 2 hops.
   *
   * <p>26 Sea Zone → Japan requires 3 sea hops (26→25→16→6→Japan). Without the +1 harbour bonus the
   * transport's reach is capped at 2 hops, stopping at 16 Sea Zone.
   *
   * <p>Verifies the fix does not spuriously inflate transport reach when no harbour unit is
   * present.
   */
  @Test
  void transportWithoutHarborCannotReachJapanOrIwoJima() {
    declareWar(americans, japanese);

    final Territory sz26 = data.getMap().getTerritoryOrThrow(SEA_ZONE_26);
    final Territory japan = data.getMap().getTerritoryOrThrow(JAPAN);
    final Territory iwoJima = data.getMap().getTerritoryOrThrow(IWO_JIMA);

    // Clear ALL units — intentionally including the Hawaiian Islands harbour so that no +1 bonus
    // applies.  Same starting sea zone as the harbour test; only the harbour is absent.
    for (final Territory t : data.getMap().getTerritories()) {
      data.performChange(ChangeFactory.removeUnits(t, t.getUnits()));
    }

    final Unit transport = GameDataTestUtil.transport(data).create(1, americans).get(0);
    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(sz26, List.of(transport, infantry)));
    infantry.setTransportedBy(transport);

    final List<MoveDescription> dispatched = runCombatMoveFor(americans);

    // With movement=2 the transport cannot cover 3 sea hops; Japan and Iwo Jima are out of reach.
    final Set<Territory> distantTargets = Set.of(japan, iwoJima);
    final boolean anyDistantAmphibUnload =
        dispatched.stream()
            .anyMatch(
                m ->
                    m.getRoute().getStart().isWater()
                        && !m.getRoute().getEnd().isWater()
                        && distantTargets.contains(m.getRoute().getEnd()));

    assertThat(anyDistantAmphibUnload)
        .as(
            "ProAi must NOT plan an amphib attack on Japan or Iwo Jima from 26 Sea Zone when no"
                + " harbour is present — movement=2 does not cover the 3-hop path. Dispatched: "
                + dispatched)
        .isFalse();
  }

  // ---- helpers ----

  private void declareWar(final GamePlayer p1, final GamePlayer p2) {
    data.performChange(
        ChangeFactory.relationshipChange(
            p1,
            p2,
            data.getRelationshipTracker().getRelationshipType(p1, p2),
            data.getRelationshipTypeList().getRelationshipType("War")));
  }

  private List<MoveDescription> runCombatMoveFor(final GamePlayer player) {
    final ProAi proAi = new ProAi("Test " + player.getName(), player.getName() + " AI");
    final PlayerBridge playerBridgeMock = mock(PlayerBridge.class);
    proAi.initialize(playerBridgeMock, player);
    when(playerBridgeMock.getGameData()).thenReturn(data);

    final List<MoveDescription> dispatched = new ArrayList<>();
    final IMoveDelegate moveDelegate = mock(IMoveDelegate.class);
    when(moveDelegate.performMove(any()))
        .then(
            inv -> {
              dispatched.add(inv.getArgument(0));
              return Optional.empty();
            });

    try (ProLogCapture ignored = new ProLogCapture()) {
      proAi.invokeCombatMoveForSidecar(moveDelegate, data, player);
    }
    return dispatched;
  }
}
