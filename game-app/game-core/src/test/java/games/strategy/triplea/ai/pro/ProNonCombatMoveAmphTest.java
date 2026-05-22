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

  // --- unmoved unit embarks to adjacent staging zone ---

  @Test
  void infantryEmbarksToAdjacentStagingZone() {
    final Territory korea = data.getMap().getTerritoryOrThrow(KOREA);
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);

    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(korea, List.of(infantry)));

    final AmphContext ctx = new AmphContext(true, Set.of(), Set.of());
    final List<MoveDescription> dispatched = runNonCombatMove(americans, ctx);

    final boolean embarkToSz6 =
        dispatched.stream()
            .anyMatch(m -> m.getRoute().getEnd().equals(sz6) && m.getUnits().contains(infantry));

    assertThat(embarkToSz6)
        .as(
            "Infantry in Korea (adjacent to 6 Sea Zone → Japan) must embark to 6 SZ in NCM. "
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

  // --- mech infantry transits to higher-value staging zone ---

  @Test
  void mechInfantryTransitsToHigherValueStagingZone() {
    // Soviet Far East → 5 Sea Zone (embark, hop 1) → 6 Sea Zone (transit, hop 2, adj to Japan).
    // 6 SZ scores higher than 5 SZ because:
    //   - 5 SZ: only Russian land adjacent (Soviet Far East, Amur, Siberia — NOT enemy) → score
    //           comes only from Japan/Korea at seaDistance=1 ≈ 2.75
    //   - 6 SZ: Japan (prod=8) and Korea (prod=3) at seaDistance=0 → score 5.5
    // mech_infantry has movement=2, so the 2-hop route SovietFarEast→5SZ→6SZ is reachable.
    final Territory sovietFarEast = data.getMap().getTerritoryOrThrow(SOVIET_FAR_EAST);
    final Territory sz5 = data.getMap().getTerritoryOrThrow(SEA_ZONE_5);
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);
    // Transfer Soviet Far East to Americans so the AI's unit-move-map includes it and the unit
    // is in a player-accessible territory.
    data.performChange(ChangeFactory.changeOwner(sovietFarEast, americans));

    final Unit mechInf = GameDataTestUtil.mechInfantry(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(sovietFarEast, List.of(mechInf)));

    final AmphContext ctx = new AmphContext(true, Set.of(), Set.of());
    final List<MoveDescription> dispatched = runNonCombatMove(americans, ctx);

    final boolean reachedSz6 =
        dispatched.stream()
            .anyMatch(m -> m.getRoute().getEnd().equals(sz6) && m.getUnits().contains(mechInf));

    final boolean stoppedAtSz5 =
        dispatched.stream()
            .anyMatch(m -> m.getRoute().getEnd().equals(sz5) && m.getUnits().contains(mechInf));

    assertThat(reachedSz6)
        .as(
            "mech_infantry in Soviet Far East (movement=2) must transit SovietFarEast→5 SZ→6 SZ "
                + "(6 SZ adjacent to Japan scores 5.5 vs 5 SZ non-enemy adjacency scores ~2.75). "
                + "Dispatched: "
                + dispatched)
        .isTrue();
    assertThat(stoppedAtSz5)
        .as("mech_infantry must not stop at 5 SZ when 6 SZ is reachable and scores higher")
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
