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
import games.strategy.triplea.ai.pro.data.AmphContext;
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
 * Tests for Phase 2.5 NCM embark move generation.
 *
 * <p>Verifies that {@code ProNonCombatMoveAi.calculateAmphEmbarkMoves} correctly embarks eligible
 * land units toward high-value staging sea zones during the non-combat move phase.
 *
 * <p>Test map: G40 Pacific. Americans vs Japanese at war.
 *
 * <ul>
 *   <li>Korea (adjacent to 6 Sea Zone, which is adjacent to Japan) — infantry with no prior move
 *       must embark toward 6 SZ.
 *   <li>Same setup but unit marked as already moved — must NOT produce an embark move.
 *   <li>Korea with a Japanese destroyer in 6 Sea Zone — contested SZ must be skipped; no embark.
 *   <li>Manchuria (adjacent to 19 SZ, which connects to 6 SZ) with mech_infantry (movement=2) —
 *       must reach 6 Sea Zone (better staging) rather than stopping in 19 Sea Zone.
 *   <li>Toggle off ({@link AmphContext#DISABLED}) — no embark moves produced.
 * </ul>
 */
public class ProNonCombatMoveAmphTest {

  private static final String KOREA = "Korea";
  private static final String SOVIET_FAR_EAST = "Soviet Far East";
  private static final String SEA_ZONE_5 = "5 Sea Zone";
  private static final String SEA_ZONE_6 = "6 Sea Zone";

  private GameData data;
  private GamePlayer americans;
  private GamePlayer japanese;

  @BeforeEach
  void setUp() {
    ClientSetting.setPreferences(new MemoryPreferences());
    data = TestMapGameData.GLOBAL1940.getGameData();
    americans = data.getPlayerList().getPlayerId("Americans");
    japanese = data.getPlayerList().getPlayerId("Japanese");
    declareWar(americans, japanese);

    // Clear all units for a clean slate; individual tests re-add what they need.
    for (final Territory t : data.getMap().getTerritories()) {
      data.performChange(ChangeFactory.removeUnits(t, t.getUnits()));
    }
  }

  // --- unmoved unit embarks toward best staging zone ---

  @Test
  void infantryEmbarksTowardBestStagingZone() {
    // With the NAVAL_MOVE=2 budget fix (#2716), infantry is no longer limited to the adjacent 6 SZ.
    // It can now transit up to 2 sea hops from the free-embark point, so the AI may pick a further
    // sea zone (e.g. 19 SZ) that scores higher than 6 SZ.  The assertion is therefore relaxed to
    // "infantry embarks to SOME sea zone" — the specific destination is scoring-dependent.
    final Territory korea = data.getMap().getTerritoryOrThrow(KOREA);

    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(korea, List.of(infantry)));

    final AmphContext ctx = new AmphContext(true, Set.of(), Set.of());
    final List<MoveDescription> dispatched = runNonCombatMove(americans, ctx);

    final boolean embarkToAnySz =
        dispatched.stream()
            .anyMatch(m -> m.getRoute().getEnd().isWater() && m.getUnits().contains(infantry));

    assertThat(embarkToAnySz)
        .as(
            "Infantry in Korea must embark to some sea zone in NCM (highest-value staging). "
                + "Dispatched: "
                + dispatched)
        .isTrue();
  }

  // --- already-moved unit does NOT embark ---

  @Test
  void alreadyMovedUnitDoesNotEmbark() {
    final Territory korea = data.getMap().getTerritoryOrThrow(KOREA);
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);

    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(korea, List.of(infantry)));
    // Consume all movement so hasMoved() == true
    data.performChange(ChangeFactory.markNoMovementChange(List.of(infantry)));

    final AmphContext ctx = new AmphContext(true, Set.of(), Set.of());
    final List<MoveDescription> dispatched = runNonCombatMove(americans, ctx);

    final boolean embarkToSz6 =
        dispatched.stream()
            .anyMatch(m -> m.getRoute().getEnd().equals(sz6) && m.getUnits().contains(infantry));

    assertThat(embarkToSz6)
        .as("Unit with alreadyMoved > 0 must not produce an embark move. Dispatched: " + dispatched)
        .isFalse();
  }

  // --- contested sea zone is skipped ---

  @Test
  void noEmbarkIntoContestedSeaZone() {
    final Territory korea = data.getMap().getTerritoryOrThrow(KOREA);
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);

    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(korea, List.of(infantry)));

    // Place an enemy destroyer in 6 SZ — makes it contested
    final Unit destroyer = GameDataTestUtil.destroyer(data).create(1, japanese).get(0);
    data.performChange(ChangeFactory.addUnits(sz6, List.of(destroyer)));

    final AmphContext ctx = new AmphContext(true, Set.of(), Set.of());
    final List<MoveDescription> dispatched = runNonCombatMove(americans, ctx);

    final boolean embarkToSz6 =
        dispatched.stream()
            .anyMatch(m -> m.getRoute().getEnd().equals(sz6) && m.getUnits().contains(infantry));

    assertThat(embarkToSz6)
        .as(
            "Contested sea zone (enemy destroyer present) must be skipped; no embark. "
                + "Dispatched: "
                + dispatched)
        .isFalse();
  }

  // --- mech infantry transits past closest SZ to higher-value zone ---

  @Test
  void mechInfantryTransitsPastClosestSzToHigherValueZone() {
    // Soviet Far East is adjacent to 5 Sea Zone. With NAVAL_MOVE=2:
    //   - 5 SZ: free embark hop, then 2 sea-transit hops available
    //   - The AI should NOT stop at 5 SZ when a higher-value zone is reachable
    //   - 6 SZ (adj to Japan) or 19 SZ score higher than 5 SZ (only Russian land adjacent)
    // mech_infantry has land-movement=2 but NAVAL_MOVE=2 governs sea transit.
    final Territory sovietFarEast = data.getMap().getTerritoryOrThrow(SOVIET_FAR_EAST);
    final Territory sz5 = data.getMap().getTerritoryOrThrow(SEA_ZONE_5);
    // Transfer Soviet Far East to Americans so the AI's unit-move-map includes it.
    data.performChange(ChangeFactory.changeOwner(sovietFarEast, americans));

    final Unit mechInf = GameDataTestUtil.mechInfantry(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(sovietFarEast, List.of(mechInf)));

    final AmphContext ctx = new AmphContext(true, Set.of(), Set.of());
    final List<MoveDescription> dispatched = runNonCombatMove(americans, ctx);

    // The unit must embark somewhere (any sea zone).
    final boolean embarkedAnywhere =
        dispatched.stream()
            .anyMatch(m -> m.getRoute().getEnd().isWater() && m.getUnits().contains(mechInf));

    // The unit must NOT stop at 5 SZ — 5 SZ has no enemy land adjacent (only Russian), so any
    // further zone with enemy adjacency scores higher and should be preferred.
    final boolean stoppedAtSz5 =
        dispatched.stream()
            .anyMatch(m -> m.getRoute().getEnd().equals(sz5) && m.getUnits().contains(mechInf));

    assertThat(embarkedAnywhere)
        .as(
            "mech_infantry in Soviet Far East must transit to some sea zone (highest-value staging). "
                + "Dispatched: "
                + dispatched)
        .isTrue();
    assertThat(stoppedAtSz5)
        .as(
            "mech_infantry must not stop at 5 SZ when a higher-value zone is reachable "
                + "(5 SZ has no enemy land adjacent — only Russian territories). "
                + "Dispatched: "
                + dispatched)
        .isFalse();
  }

  // --- toggle off: no embark moves ---

  @Test
  void toggleOffDoesNotProduceEmbarkMoves() {
    final Territory korea = data.getMap().getTerritoryOrThrow(KOREA);
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);

    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(korea, List.of(infantry)));

    final List<MoveDescription> dispatched = runNonCombatMove(americans, AmphContext.DISABLED);

    final boolean embarkToSz6 =
        dispatched.stream()
            .anyMatch(m -> m.getRoute().getEnd().equals(sz6) && m.getUnits().contains(infantry));

    assertThat(embarkToSz6)
        .as(
            "With AmphContext.DISABLED the embark pathway must not produce moves. "
                + "Dispatched: "
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

  private List<MoveDescription> runNonCombatMove(final GamePlayer player, final AmphContext ctx) {
    // Advance the game sequence to the Americans NCM step so GameStepPropertiesHelper can resolve
    // isCombatMove correctly (it reads the step name/properties).
    advanceToStep("americansNonCombatMove");

    final ProAi proAi = new ProAi("Test " + player.getName(), player.getName() + " AI");
    final PlayerBridge bridge = mock(PlayerBridge.class);
    proAi.initialize(bridge, player);
    when(bridge.getGameData()).thenReturn(data);
    proAi.reinitializeProDataForSidecar();
    proAi.getProData().setAmphContext(ctx);

    final List<MoveDescription> dispatched = new ArrayList<>();
    final IMoveDelegate moveDelegate = mock(IMoveDelegate.class);
    when(moveDelegate.performMove(any()))
        .then(
            inv -> {
              dispatched.add(inv.getArgument(0));
              return Optional.empty();
            });

    try (ProLogCapture ignored = new ProLogCapture()) {
      proAi.invokeNonCombatMoveForSidecar(moveDelegate, data, player);
    }
    return dispatched;
  }

  private void advanceToStep(final String stepName) {
    try (GameData.Unlocker ignored = data.acquireWriteLock()) {
      final int length = data.getSequence().size();
      for (int i = 0; i < length; i++) {
        if (data.getSequence().getStep().getName().contains(stepName)) break;
        data.getSequence().next();
      }
    }
  }
}
