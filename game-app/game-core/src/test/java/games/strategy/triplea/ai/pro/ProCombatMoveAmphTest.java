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
 * Tests for Phase 2.4 CM assault generation.
 *
 * <p>Verifies that {@code ProCombatMoveAi.findAmphAssaultOptions} correctly generates (and
 * correctly withholds) 1-hop amphib assault moves based on {@link AmphContext} embarkation state.
 *
 * <p>Test map: G40 Pacific. Americans vs Japanese at war.
 *
 * <ul>
 *   <li>6 Sea Zone — directly adjacent to Japan. Infantry staged there with {@code isEmbarked=true,
 *       isEmbarkedThisTurn=false} must produce an assault move to Japan.
 *   <li>Same setup but {@code isEmbarkedThisTurn=true} — must NOT produce a CM assault.
 *   <li>26 Sea Zone — 3 sea hops from Japan. Infantry staged there is NOT adjacent to Japan, so no
 *       direct CM assault on Japan is produced (only adjacent coasts qualify).
 *   <li>Toggle off ({@link AmphContext#DISABLED}) — the amphib system is not invoked; no assault
 *       from the new pathway, normal transport flow unchanged.
 * </ul>
 */
public class ProCombatMoveAmphTest {

  private static final String SEA_ZONE_6 = "6 Sea Zone";
  private static final String SEA_ZONE_26 = "26 Sea Zone";
  private static final String JAPAN = "Japan";

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

  // --- staged unit proposes adjacent-hostile assault ---

  @Test
  void stagedUnitProposesAssaultOnAdjacentHostileCoast() {
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);
    final Territory japan = data.getMap().getTerritoryOrThrow(JAPAN);

    // One US infantry staged in 6 SZ — embarked and NOT embarked this turn.
    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(sz6, List.of(infantry)));

    final AmphContext ctx =
        new AmphContext(true, Set.of(infantry.getId()), Set.of() /* not embarked this turn */);

    final List<MoveDescription> dispatched = runCombatMove(americans, ctx);

    final boolean assaultToJapan =
        dispatched.stream()
            .anyMatch(
                m ->
                    m.getRoute().getStart().equals(sz6)
                        && m.getRoute().getEnd().equals(japan)
                        && m.getUnits().contains(infantry));

    assertThat(assaultToJapan)
        .as(
            "Infantry staged in 6 Sea Zone (adjacent to Japan) with isEmbarked=true and "
                + "isEmbarkedThisTurn=false must produce a combat assault move to Japan. "
                + "Dispatched: "
                + dispatched)
        .isTrue();
  }

  // --- embarkedThisTurn unit does NOT produce a CM assault ---

  @Test
  void embarkedThisTurnUnitDoesNotProposeCombatAssault() {
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);
    final Territory japan = data.getMap().getTerritoryOrThrow(JAPAN);

    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(sz6, List.of(infantry)));

    // Same infantry but marked as embarked THIS turn — must not assault in CM.
    final AmphContext ctx =
        new AmphContext(
            true, Set.of(infantry.getId()), Set.of(infantry.getId()) /* embarked this turn */);

    final List<MoveDescription> dispatched = runCombatMove(americans, ctx);

    final boolean assaultToJapan =
        dispatched.stream()
            .anyMatch(
                m ->
                    m.getRoute().getStart().equals(sz6)
                        && m.getRoute().getEnd().equals(japan)
                        && m.getUnits().contains(infantry));

    assertThat(assaultToJapan)
        .as(
            "Infantry embarked THIS turn must not produce a CM assault move — "
                + "embarkedThisTurn guard prevents same-turn attack. Dispatched: "
                + dispatched)
        .isFalse();
  }

  // --- no multi-hop CM assault ---

  @Test
  void noMultiHopCombatAssault() {
    // 26 Sea Zone is 3 sea hops from Japan — NOT adjacent. Only a 1-hop assault is valid.
    final Territory sz26 = data.getMap().getTerritoryOrThrow(SEA_ZONE_26);
    final Territory japan = data.getMap().getTerritoryOrThrow(JAPAN);

    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(sz26, List.of(infantry)));

    final AmphContext ctx = new AmphContext(true, Set.of(infantry.getId()), Set.of());

    final List<MoveDescription> dispatched = runCombatMove(americans, ctx);

    final boolean assaultToJapan =
        dispatched.stream()
            .anyMatch(m -> m.getRoute().getEnd().equals(japan) && m.getUnits().contains(infantry));

    assertThat(assaultToJapan)
        .as(
            "Infantry in 26 Sea Zone (3 hops from Japan) must not be dispatched directly to Japan "
                + "— only 1-hop adjacent coasts are valid CM assault targets. Dispatched: "
                + dispatched)
        .isFalse();
  }

  // --- toggle off: amphib system inactive, transport flow unchanged ---

  @Test
  void toggleOffDoesNotProduceAmphAssaultMoves() {
    // With AmphContext.DISABLED the new assault pathway is never invoked.
    // The transport pathway also produces nothing (no transports in game).
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);
    final Territory japan = data.getMap().getTerritoryOrThrow(JAPAN);

    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(sz6, List.of(infantry)));

    // Toggle is OFF — infantry IDs in the context are irrelevant, the pathway is gated.
    final AmphContext ctx = AmphContext.DISABLED;

    final List<MoveDescription> dispatched = runCombatMove(americans, ctx);

    final boolean assaultToJapan =
        dispatched.stream()
            .anyMatch(
                m ->
                    m.getRoute().getStart().equals(sz6)
                        && m.getRoute().getEnd().equals(japan)
                        && m.getUnits().contains(infantry));

    assertThat(assaultToJapan)
        .as(
            "With AmphContext.DISABLED the new amphib assault pathway must not produce moves. "
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

  private List<MoveDescription> runCombatMove(final GamePlayer player, final AmphContext ctx) {
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
      proAi.invokeCombatMoveForSidecar(moveDelegate, data, player);
    }
    return dispatched;
  }
}
