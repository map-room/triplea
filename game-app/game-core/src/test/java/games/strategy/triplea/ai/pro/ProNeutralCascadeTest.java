package games.strategy.triplea.ai.pro;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.player.PlayerBridge;
import games.strategy.triplea.ai.pro.logging.ProLogCapture;
import games.strategy.triplea.ai.pro.util.ProUtils;
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
 * Regression test for #2631: ProAi attacks True Neutrals without accounting for the activation
 * cascade — gifts opponent free-infantry stacks block-wide.
 *
 * <p>Root cause: {@code prioritizeAttackOptions} applies local penalties ({@code * 0.1} neutral
 * factor) but never subtracts the cascade cost: when any True Neutral is attacked, ALL other True
 * Neutrals flip their {@code captureUnitOnEnteringBy} to the opposing coalition, gifting them those
 * territories' existing neutral infantry as free units.
 *
 * <p>Fix: subtract {@code ProUtils.computeTrueNeutralCascadeCost} from the attack value for any
 * True Neutral target. The cost is zero when the cascade pool is already empty (all other True
 * Neutrals have no capturable units), allowing the planner to still attack in those states.
 *
 * <p>Test scenario: Germany adjacent to Switzerland (Western Germany borders Switzerland in G40).
 *
 * <ul>
 *   <li>Acceptance criterion 1: with a full cascade pool (11 other True Neutrals each with ≥2
 *       infantry), the planner must NOT dispatch an attack on Switzerland — cascade cost exceeds
 *       the 90%-penalised attack value of a 2-production territory.
 *   <li>Acceptance criterion 2: when the cascade pool is empty (all other True Neutral territories
 *       have no units), the planner MUST dispatch an attack on Switzerland — no cascade, positive
 *       attack value.
 * </ul>
 */
public class ProNeutralCascadeTest {

  private static final String TRUE_NEUTRAL_PLAYER = "Neutral_True";
  private static final String WAR_RELATIONSHIP = "War";
  private static final String SWITZERLAND = "Switzerland";
  private static final String WESTERN_GERMANY = "Western Germany";

  // True Neutral territories per the G40 XML trigger: Chile, Argentina, Venezuela, Sweden,
  // Switzerland, Spain, Portugal, Turkey, Saudi Arabia, Afghanistan, Mozambique, Angola.
  private static final List<String> TRUE_NEUTRAL_TERRITORIES =
      List.of(
          "Chile",
          "Argentina",
          "Venezuela",
          "Sweden",
          "Switzerland",
          "Spain",
          "Portugal",
          "Turkey",
          "Saudi Arabia",
          "Afghanistan",
          "Mozambique",
          "Angola");

  private GameData data;
  private GamePlayer germans;
  private GamePlayer neutralTrue;

  @BeforeEach
  void setUp() {
    ClientSetting.setPreferences(new MemoryPreferences());
    data = TestMapGameData.GLOBAL1940.getGameData();
    germans = data.getPlayerList().getPlayerId("Germans");
    neutralTrue = data.getPlayerList().getPlayerId(TRUE_NEUTRAL_PLAYER);
  }

  /**
   * Acceptance criterion 1: cascade pool is full — planner must NOT attack Switzerland.
   *
   * <p>Setup: Germany at war with Neutral_True, German forces in Western Germany, Switzerland has 2
   * Neutral_True infantry, all 11 other True Neutral territories retain their starting infantry.
   * The cascade cost (≈4–10 depending on adjacency) must exceed Switzerland's post-penalty attack
   * value (≈0.6) so the planner removes Switzerland from its attack list.
   */
  @Test
  void plannerDeclinesSwitzerlandWhenCascadePoolIsLarge() {
    declareWar(germans, neutralTrue);

    // Remove all non-German units from every territory so the planner has no other distractions,
    // but keep ALL True Neutral territories with their starting units (the cascade pool).
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater()
          && !TRUE_NEUTRAL_TERRITORIES.contains(t.getName())
          && !t.getName().equals(WESTERN_GERMANY)) {
        data.performChange(ChangeFactory.removeUnits(t, t.getUnits()));
      }
      if (t.isWater()) {
        data.performChange(ChangeFactory.removeUnits(t, t.getUnits()));
      }
    }

    // Cascade cost sanity check: must be greater than zero.
    final Territory switzerland = data.getMap().getTerritoryOrThrow(SWITZERLAND);
    final double cascadeCost = ProUtils.computeTrueNeutralCascadeCost(data, germans, switzerland);
    assertThat(cascadeCost)
        .as("cascade cost must be positive when 11 other True Neutrals still have units")
        .isGreaterThan(0.0);

    final List<MoveDescription> dispatched = runCombatMoveFor(germans);

    // Assert: Switzerland (a True Neutral) must NOT be attacked.
    final long switzerlandAttacks =
        dispatched.stream()
            .filter(
                m ->
                    m.getRoute().getEnd() != null
                        && m.getRoute().getEnd().getName().equals(SWITZERLAND))
            .count();
    assertThat(switzerlandAttacks)
        .as(
            "ProAi must NOT attack Switzerland when cascade pool is large (cost exceeds 90%-penalised"
                + " attack value). Dispatched: "
                + dispatched)
        .isEqualTo(0);
  }

  /**
   * Acceptance criterion 2 (counter-regression): when cascade pool is empty (all other True
   * Neutrals have no units), the planner CAN attack Switzerland.
   *
   * <p>Verifies that the fix does not suppress neutral attacks when the cascade penalty is zero — a
   * state that arises once both sides have fully occupied the neutral territories.
   */
  @Test
  void plannerAttacksSwitzerlandWhenCascadePoolIsEmpty() {
    declareWar(germans, neutralTrue);

    // Remove all non-German and non-Switzerland units so planner has a clean slate.
    // Also clear units from all OTHER True Neutrals (cascade pool = empty).
    for (final Territory t : data.getMap().getTerritories()) {
      if (t.getName().equals(WESTERN_GERMANY)) {
        continue; // keep German forces to conduct the attack
      }
      if (t.getName().equals(SWITZERLAND)) {
        continue; // keep 2 Swiss infantry as defenders
      }
      data.performChange(ChangeFactory.removeUnits(t, t.getUnits()));
    }

    // Cascade cost must be zero when all other True Neutral territories have no units.
    final Territory switzerland = data.getMap().getTerritoryOrThrow(SWITZERLAND);
    final double cascadeCost = ProUtils.computeTrueNeutralCascadeCost(data, germans, switzerland);
    assertThat(cascadeCost)
        .as("cascade cost must be zero when all other True Neutral territories are empty")
        .isEqualTo(0.0);

    final List<MoveDescription> dispatched = runCombatMoveFor(germans);

    // Assert: at least one move targets Switzerland.
    final long switzerlandAttacks =
        dispatched.stream()
            .filter(
                m ->
                    m.getRoute().getEnd() != null
                        && m.getRoute().getEnd().getName().equals(SWITZERLAND))
            .count();
    assertThat(switzerlandAttacks)
        .as(
            "ProAi must attack Switzerland when the cascade pool is empty (no other True Neutrals"
                + " have units). Dispatched: "
                + dispatched)
        .isGreaterThan(0);
  }

  // ---- helpers ----

  private void declareWar(final GamePlayer player1, final GamePlayer player2) {
    data.performChange(
        ChangeFactory.relationshipChange(
            player1,
            player2,
            data.getRelationshipTracker().getRelationshipType(player1, player2),
            data.getRelationshipTypeList().getRelationshipType(WAR_RELATIONSHIP)));
  }

  private List<MoveDescription> runCombatMoveFor(final GamePlayer player) {
    final ProAi proAi = new ProAi("Test " + player.getName(), player.getName() + " AI");
    final PlayerBridge playerBridgeMock = Mockito.mock(PlayerBridge.class);
    proAi.initialize(playerBridgeMock, player);
    Mockito.when(playerBridgeMock.getGameData()).thenReturn(data);

    final List<MoveDescription> dispatched = new ArrayList<>();
    final IMoveDelegate moveDelegate = Mockito.mock(IMoveDelegate.class);
    Mockito.when(moveDelegate.performMove(Mockito.any()))
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
