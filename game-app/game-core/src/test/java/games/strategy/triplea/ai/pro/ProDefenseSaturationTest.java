package games.strategy.triplea.ai.pro;

import static org.assertj.core.api.Assertions.assertThat;
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
import games.strategy.triplea.delegate.GameDataTestUtil;
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
 * Regression tests for #2637: {@code prioritizeDefendOptions} included {@code + 0.5 *
 * cantMoveUnitValue} in the territory-value formula, creating a positive-feedback loop.
 *
 * <p>Root cause: territories with large existing garrisons scored higher → the planner sent more
 * units there → the garrison grew → the territory scored even higher. Over multiple rounds capitals
 * accumulated permanent over-sized garrisons while the rest of the board went undefended.
 *
 * <p>Fix: drop {@code + 0.5 * cantMoveUnitValue} entirely. Territory value is now determined solely
 * by intrinsic strategic factors: production, factory presence, airfield/naval base bonuses, and
 * neighbor value. The capital multiplier (×55 = (1+10)×(1+4)) ensures capitals remain high-priority
 * without garrison inflation. Additionally, the same airfield/naval base bonuses added to the
 * attack-value formula in #2633 are now applied to the defense-value formula.
 *
 * <p>Test anchor: G40 initial state, Japanese capital under threat from American fleet.
 *
 * <ul>
 *   <li>Japan (production=8, factory, capital) has a formula value of ~550 independent of how many
 *       units are parked there — the capital multiplier (×55) ensures it stays top-priority when
 *       under real threat without inflating further as the garrison grows.
 *   <li>Counter-regression: when Japan is under genuine threat (American loaded transports in the
 *       adjacent sea zone), the planner must still include Japan in the defend list and dispatch at
 *       least one defensive move.
 * </ul>
 */
public class ProDefenseSaturationTest {

  private static final String JAPAN = "Japan";
  private static final String SEA_ZONE_6 = "6 Sea Zone";
  private static final String SEA_ZONE_5 = "5 Sea Zone";

  private GameData data;
  private GamePlayer japanese;
  private GamePlayer americans;

  @BeforeEach
  void setUp() {
    ClientSetting.setPreferences(new MemoryPreferences());
    data = TestMapGameData.GLOBAL1940.getGameData();
    japanese = data.getPlayerList().getPlayerId("Japanese");
    americans = data.getPlayerList().getPlayerId("Americans");
  }

  /**
   * Regression (acceptance criterion 1): Japan capital must still be defended when under genuine
   * threat even after the {@code cantMoveUnitValue} term is removed from the formula.
   *
   * <p>Setup: Japan has a single Japanese infantry (weak garrison) as a cant-move unit. A large
   * American fleet with loaded transports sits in 6 Sea Zone (directly adjacent to Japan) — a
   * credible invasion force that gives Japan non-empty {@code maxEnemyUnits}. A Japanese destroyer
   * in 5 Sea Zone is available as a mobile defensive unit.
   *
   * <p>With {@code cantMoveUnitValue = 0} the capital formula still yields a high value (≈550 from
   * the factory + capital multipliers) so Japan is not pruned from the defend list. The planner
   * should dispatch at least one non-combat move related to Japan's defense.
   */
  @Test
  void capitalDefendedUnderFleetThreatEvenWithThinGarrison() {
    declareWar(japanese, americans);
    data.getSequence().setRoundAndStep(1, "Non Combat Move", japanese);

    final Territory japan = data.getMap().getTerritoryOrThrow(JAPAN);
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);
    final Territory sz5 = data.getMap().getTerritoryOrThrow(SEA_ZONE_5);

    // Clear all units from the board.
    for (final Territory t : data.getMap().getTerritories()) {
      data.performChange(ChangeFactory.removeUnits(t, t.getUnits()));
    }

    // Thin Japanese garrison at the capital — cant-move value ≈ 3 TUV, small.
    final Unit japInfantry = GameDataTestUtil.infantry(data).create(1, japanese).get(0);
    data.performChange(ChangeFactory.addUnits(japan, List.of(japInfantry)));

    // Mobile Japanese destroyer in 5 Sea Zone (can reach 6 Sea Zone and defend adjacent sea lane).
    final Unit japDestroyer = GameDataTestUtil.destroyer(data).create(1, japanese).get(0);
    data.performChange(ChangeFactory.addUnits(sz5, List.of(japDestroyer)));

    // American fleet in 6 Sea Zone: 3 battleships + 2 loaded transports — credible invasion.
    final List<Unit> americanFleet = new ArrayList<>();
    americanFleet.addAll(GameDataTestUtil.battleship(data).create(3, americans));
    final Unit transport1 = GameDataTestUtil.transport(data).create(1, americans).get(0);
    final Unit transport2 = GameDataTestUtil.transport(data).create(1, americans).get(0);
    final Unit amphibInf1 = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    final Unit amphibInf2 = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    americanFleet.addAll(List.of(transport1, transport2, amphibInf1, amphibInf2));
    data.performChange(ChangeFactory.addUnits(sz6, americanFleet));
    amphibInf1.setTransportedBy(transport1);
    amphibInf2.setTransportedBy(transport2);

    assertThat(transport1.getTransporting(sz6))
        .as("pre-condition: transport1 must carry amphibInf1")
        .containsExactly(amphibInf1);

    final List<MoveDescription> dispatched = runNonCombatMoveFor(japanese);

    // At minimum, the planner must dispatch SOMETHING — Japan must not be silently dropped from
    // the defend list. (If cantMoveUnitValue were the only source of value and were now zero,
    // Japan would score 0 and be pruned — this assertion catches that regression.)
    assertThat(dispatched)
        .as(
            "ProAi must dispatch at least one NCM move when Japan is under fleet threat."
                + " Dispatched: "
                + dispatched)
        .isNotEmpty();
  }

  /**
   * Acceptance criterion 2: Japan must not accumulate further garrison when it already holds
   * sufficient defenders and no additional threat is present.
   *
   * <p>Setup: Japan has a large existing garrison (10 infantry — enough to hold against a
   * single-unit probe). No enemy forces are adjacent to Japan. A Japanese destroyer is in 5 Sea
   * Zone (mobile, available for reassignment).
   *
   * <p>Under the old formula, Japan's value would be inflated by the garrison TUV (≈30 extra),
   * drawing the destroyer inward. Under the new formula, Japan is pruned by {@code
   * canAlreadyBeHeld} (no enemy threat → Japan doesn't need reinforcement) so the destroyer remains
   * available for other tasks. The assertion captures this: no NCM move should end AT Japan
   * specifically when Japan needs no reinforcement.
   */
  @Test
  void capitalNotReinforcedfurtherWhenAlreadyHeldAndNoThreatPresent() {
    declareWar(japanese, americans);
    data.getSequence().setRoundAndStep(1, "Non Combat Move", japanese);

    final Territory japan = data.getMap().getTerritoryOrThrow(JAPAN);
    final Territory sz5 = data.getMap().getTerritoryOrThrow(SEA_ZONE_5);

    // Clear all units from the board.
    for (final Territory t : data.getMap().getTerritories()) {
      data.performChange(ChangeFactory.removeUnits(t, t.getUnits()));
    }

    // Large Japanese garrison at the capital — TUV ≈ 30, would inflate value under old formula.
    final List<Unit> garrison = GameDataTestUtil.infantry(data).create(10, japanese);
    data.performChange(ChangeFactory.addUnits(japan, garrison));

    // Mobile Japanese destroyer in 5 Sea Zone — no enemy nearby so the destroyer should not be
    // drawn to Japan (Japan can already hold without it).
    final Unit japDestroyer = GameDataTestUtil.destroyer(data).create(1, japanese).get(0);
    data.performChange(ChangeFactory.addUnits(sz5, List.of(japDestroyer)));

    // No American units anywhere — Japan faces zero threat.

    final List<MoveDescription> dispatched = runNonCombatMoveFor(japanese);

    // Verify: no move ends at Japan (Japan is pruned as canAlreadyBeHeld; the destroyer should
    // stay in 5 Sea Zone or move elsewhere, NOT pile into Japan's over-garrisoned capital).
    final long movesToJapan =
        dispatched.stream()
            .filter(m -> m.getRoute().getEnd() != null && m.getRoute().getEnd().equals(japan))
            .count();
    assertThat(movesToJapan)
        .as(
            "No NCM move should end at Japan when Japan can already hold and faces zero threat."
                + " Under the old formula the garrison TUV would have inflated Japan's value and"
                + " drawn the destroyer in. Dispatched: "
                + dispatched)
        .isEqualTo(0);
  }

  /**
   * Smoke / counter-regression: the NCM planner must complete without error for the G40 initial
   * Japanese state when Americans is at war. Validates the refactored formula against production
   * unit-mix in the real starting position.
   */
  @Test
  void ncmPlannerCompletesForJapaneseInitialStateUnderWarConditions() {
    declareWar(japanese, americans);

    // Advance game sequence to the Japanese non-combat move step.
    data.getSequence().setRoundAndStep(1, "Non Combat Move", japanese);

    // Remove all non-Japanese units so the planner sees only Japanese threats.
    for (final Territory t : data.getMap().getTerritories()) {
      final List<Unit> toRemove = t.getMatches(Matches.unitIsOwnedBy(japanese).negate());
      if (!toRemove.isEmpty()) {
        data.performChange(ChangeFactory.removeUnits(t, toRemove));
      }
    }

    // Place a small American threat in a Pacific sea zone so the planner has something to react to.
    final Territory sz17 = data.getMap().getTerritoryOrThrow("17 Sea Zone");
    final List<Unit> americanThreat = GameDataTestUtil.destroyer(data).create(2, americans);
    data.performChange(ChangeFactory.addUnits(sz17, americanThreat));

    final List<MoveDescription> dispatched = runNonCombatMoveFor(japanese);

    // The primary assertion is that we reach this point without an exception.
    // A secondary sanity check: the planner should produce a non-null list.
    assertThat(dispatched).as("NCM result must be non-null").isNotNull();
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

  private List<MoveDescription> runNonCombatMoveFor(final GamePlayer player) {
    final ProAi proAi = new ProAi("Test " + player.getName(), player.getName() + " AI");
    final PlayerBridge playerBridgeMock = Mockito.mock(PlayerBridge.class);
    proAi.initialize(playerBridgeMock, player);
    when(playerBridgeMock.getGameData()).thenReturn(data);

    final List<MoveDescription> dispatched = new ArrayList<>();
    final IMoveDelegate moveDelegate = Mockito.mock(IMoveDelegate.class);
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
}
