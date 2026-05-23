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
 * Integration tests for the unified embarked-movement model (design §6.2) in {@link
 * ProNonCombatMoveAi#calculateAmphEmbarkMoves}.
 *
 * <p>Resolves #2717 (already-embarked units stranded by {@code !isEmbarked} filter) and validates
 * the survival-probability gate (§6.6 / §5.2-1). Tests are ordered from lowest-level behavioural
 * regression to highest-level end-to-end scenarios.
 *
 * <p>Test map: G40. Americans vs Japanese at war. All units cleared in {@link #setUp}; each test
 * places exactly the units it needs.
 */
public class ProNonCombatMoveUnifiedAmphTest {

  private static final String KOREA = "Korea";
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

    for (final Territory t : data.getMap().getTerritories()) {
      data.performChange(ChangeFactory.removeUnits(t, t.getUnits()));
    }
  }

  // ─── §7.3 (#2717a) — already-embarked unit is not filtered out ──────────────

  /**
   * Regression for #2717a: an infantry unit that is already embarked in a sea zone (from a prior
   * turn) must be included in NCM embark-move candidates.
   *
   * <p>Before the fix, {@code calculateAmphEmbarkMoves} iterated only over non-water territories
   * and filtered {@code !ctx.isEmbarked(u)} — so an embarked unit sitting in 6 Sea Zone was
   * completely invisible to the NCM pass; it received no move.
   *
   * <p>After the fix the code also iterates over sea zones, finds the embarked infantry, and
   * dispatches it to an adjacent friendly coast (Korea, transferred to Americans so there is a
   * disembark target with production value).
   *
   * <p><b>Why Korea must be American:</b> branch (B) of {@code getAmphReachableTerritories} only
   * adds adjacent <em>friendly</em> coasts as disembark targets. An enemy Korea would not be a
   * valid disembark destination, so the only remaining destinations are further sea zones — and
   * with no enemy land adjacent to those zones, {@code findAmphReachableLandValue} would return 0
   * for all of them, leaving the AI with no positive-scoring move. Transferring Korea to Americans
   * gives the unit a productive landing zone (Korea production=3).
   */
  @Test
  void alreadyEmbarkedInfantryGetsNcmMove_issue2717a() {
    final Territory korea = data.getMap().getTerritoryOrThrow(KOREA);
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);

    // Transfer Korea to Americans — friendly disembark target with production value.
    data.performChange(ChangeFactory.changeOwner(korea, americans));

    // Place enemy infantry in Japan so 6 SZ has staging value
    // (Japan is adjacent to 6 SZ — gives findAmphReachableLandValue > 0 for sea zones adj to it).
    final Territory japan = data.getMap().getTerritoryOrThrow("Japan");
    final List<Unit> japaneseInfantry = GameDataTestUtil.infantry(data).create(1, japanese);
    data.performChange(ChangeFactory.addUnits(japan, japaneseInfantry));

    // Infantry is ALREADY IN 6 Sea Zone and EMBARKED (not fresh — prior-turn embark).
    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(sz6, List.of(infantry)));

    // AmphContext: infantry is embarked but NOT embarkedThisTurn.
    final AmphContext ctx =
        new AmphContext(true, Set.of(infantry.getId()), Set.of(/* not fresh */ ));

    final List<MoveDescription> dispatched = runNonCombatMove(americans, ctx);

    final boolean infantryMoved =
        dispatched.stream().anyMatch(m -> m.getUnits().contains(infantry));

    assertThat(infantryMoved)
        .as(
            "An already-embarked infantry in 6 Sea Zone must receive an NCM move "
                + "(disembark to Korea or transit to an adjacent SZ). "
                + "Before the fix the !isEmbarked filter silently excluded it. "
                + "Dispatched: "
                + dispatched)
        .isTrue();
  }

  // ─── §7.10 — freshly-embarked unit must NOT disembark same turn ─────────────

  /**
   * A unit that embarked THIS turn ({@code isEmbarkedThisTurn=true}) must not receive a disembark
   * (or further NCM) move in the same NCM pass. Engine rule: {@code embarkedThisTurn} blocks
   * disembark (§5.2-1 design ref, engine source {@code canEmbarkedUnitDisembark:284}).
   *
   * <p>This test is primarily a regression guard: the current code never even finds a freshly-
   * embarked unit (it sits in a sea zone and the old loop skips water territories). After the loop
   * is extended to cover sea zones, the {@code isEmbarkedThisTurn} guard inside {@link
   * games.strategy.triplea.ai.pro.util.ProAmphUtils#getAmphReachableTerritories} must prevent a
   * disembark move.
   */
  @Test
  void freshlyEmbarkedInfantryDoesNotDisembark() {
    final Territory korea = data.getMap().getTerritoryOrThrow(KOREA);
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);

    // Friendly Korea — would be a valid disembark target if the guard were absent.
    data.performChange(ChangeFactory.changeOwner(korea, americans));

    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(sz6, List.of(infantry)));

    // BOTH embarked AND embarkedThisTurn → same-turn guard must fire.
    final AmphContext ctx =
        new AmphContext(true, Set.of(infantry.getId()), Set.of(infantry.getId()) /* fresh */);

    final List<MoveDescription> dispatched = runNonCombatMove(americans, ctx);

    final boolean disembarkedToKorea =
        dispatched.stream()
            .anyMatch(m -> m.getUnits().contains(infantry) && m.getRoute().getEnd().equals(korea));

    assertThat(disembarkedToKorea)
        .as(
            "A freshly-embarked infantry (embarkedThisTurn=true) must NOT disembark to Korea "
                + "in the same NCM pass. Dispatched: "
                + dispatched)
        .isFalse();
  }

  // ─── §7.7 — survival gate blocks embark to threatened zone ──────────────────

  /**
   * Regression for §5.2-1 (design): when enemy strength makes {@code P(stagingZone) < 0.70}, the
   * NCM pass must NOT embark the unit into that zone.
   *
   * <p>Before the fix there is no survival-probability gate: the AI always embarks toward the
   * highest-valued staging zone regardless of enemy presence. Adding 5 Japanese battleships to 5
   * Sea Zone (adjacent to 6 Sea Zone) makes the expected-survival probability of 6 SZ well below
   * the 0.70 threshold, so the fix must suppress the embark.
   *
   * <p>The test is RED before the fix (unit embarks into the threatened 6 SZ) and GREEN after (the
   * gate rejects 6 SZ as too risky; with no other safe sea zone adjacent to Korea, no embark is
   * generated).
   */
  @Test
  void survivalGateBlocksEmbarkToThreatenedZone() {
    final Territory korea = data.getMap().getTerritoryOrThrow(KOREA);
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);
    final Territory sz5 = data.getMap().getTerritoryOrThrow(SEA_ZONE_5);

    // Infantry in Korea (non-water, non-embarked) — standard embark candidate.
    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(korea, List.of(infantry)));

    // Overwhelming enemy fleet in 5 SZ (adjacent to 6 SZ) — 5 battleships.
    // survivalProbability([infantry], 6 SZ, Americans) with 5 adjacent enemy battleships
    // and no friendly escort must come out well below DISEMBARK_SURVIVAL_THRESHOLD (0.70).
    final List<Unit> enemyFleet = GameDataTestUtil.battleship(data).create(5, japanese);
    data.performChange(ChangeFactory.addUnits(sz5, enemyFleet));

    final AmphContext ctx = new AmphContext(true, Set.of(), Set.of());
    final List<MoveDescription> dispatched = runNonCombatMove(americans, ctx);

    final boolean embarkToSz6 =
        dispatched.stream()
            .anyMatch(m -> m.getUnits().contains(infantry) && m.getRoute().getEnd().equals(sz6));

    assertThat(embarkToSz6)
        .as(
            "5 Japanese battleships in 5 SZ make P(6 SZ) < 0.70 — "
                + "the survival gate must block the embark. "
                + "Before the fix (no gate): unit embarks unconditionally. "
                + "Dispatched: "
                + dispatched)
        .isFalse();
  }

  // ─── §7.7 (positive) — survival gate allows embark when escort is sufficient ─

  /**
   * Regression guard (§5.2-1 positive): when friendly escort in the staging zone makes {@code
   * P(stagingZone) ≥ 0.70}, the NCM pass MUST embark the infantry.
   *
   * <p>This test is GREEN before the fix (no gate ⇒ always embark) and must remain GREEN after
   * (gate passes because of the escort). It guards against the gate over-firing and blocking safe
   * embark routes.
   */
  @Test
  void survivalGateAllowsEmbarkWithSufficientEscort() {
    final Territory korea = data.getMap().getTerritoryOrThrow(KOREA);
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);
    final Territory sz5 = data.getMap().getTerritoryOrThrow(SEA_ZONE_5);

    // Infantry in Korea — standard embark candidate.
    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(korea, List.of(infantry)));

    // 5 American battleships in 6 SZ — strong escort.
    final List<Unit> escort = GameDataTestUtil.battleship(data).create(5, americans);
    data.performChange(ChangeFactory.addUnits(sz6, escort));

    // Weak enemy: 1 Japanese destroyer in 5 SZ — minor threat, easily overmatched by escort.
    final List<Unit> weakEnemy = GameDataTestUtil.destroyer(data).create(1, japanese);
    data.performChange(ChangeFactory.addUnits(sz5, weakEnemy));

    // Japan is enemy territory (gives 6 SZ a positive staging score).
    // Japan is already owned by Japanese and adjacent to 6 SZ — no extra setup needed.

    final AmphContext ctx = new AmphContext(true, Set.of(), Set.of());
    final List<MoveDescription> dispatched = runNonCombatMove(americans, ctx);

    // The infantry must be dispatched to SOME sea zone (gate allowed).
    // We do not constrain the specific destination: the AI picks the highest-value staging zone,
    // which may be 6 SZ or a further zone (e.g. 19 SZ) depending on adjacency scoring.
    final boolean embarkToAnySz =
        dispatched.stream()
            .anyMatch(m -> m.getUnits().contains(infantry) && m.getRoute().getEnd().isWater());

    assertThat(embarkToAnySz)
        .as(
            "5 American battleships in 6 SZ vs 1 Japanese destroyer in 5 SZ → "
                + "P must be ≥ 0.70. Survival gate must NOT block this embark to any sea zone. "
                + "Dispatched: "
                + dispatched)
        .isTrue();
  }

  // ─── §7.8 — retreat trigger: already-embarked unit is dispatched when SZ is threatened ────────

  /**
   * Regression for §5.2-1 / §7.8 (design): an already-embarked infantry in a staging zone must
   * receive an NCM move when the staging zone is under threat.
   *
   * <p>Before the fix, the unit is invisible to NCM (in a sea zone, skipped by the {@code
   * !isWater()} loop) — dispatched is empty. After the fix the unit is found and scored: Korea
   * (American-owned, adjacent to 6 SZ, production=3) is a valid disembark target, and several
   * adjacent sea zones may also be reachable. The specific destination (Korea vs a sea zone)
   * depends on per-SZ scoring and is not constrained here — the key regression is that the unit is
   * found and gets SOME move, as opposed to the pre-fix silent exclusion.
   *
   * <p>The survival-gate mechanics (sea-zone destinations gated by {@code survivalProbability ≥
   * DISEMBARK_SURVIVAL_THRESHOLD}) are validated at the level of the non-embarked path by {@link
   * #survivalGateBlocksEmbarkToThreatenedZone} and {@link
   * #survivalGateAllowsEmbarkWithSufficientEscort}.
   */
  @Test
  void retreatTrigger_alreadyEmbarkedUnitIsDispatchedWhenSzUnderThreat() {
    final Territory korea = data.getMap().getTerritoryOrThrow(KOREA);
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);
    final Territory sz5 = data.getMap().getTerritoryOrThrow(SEA_ZONE_5);

    // Transfer Korea to Americans — gives the unit a friendly disembark target.
    data.performChange(ChangeFactory.changeOwner(korea, americans));

    // Infantry already in 6 SZ (embarked from a prior turn).
    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(sz6, List.of(infantry)));

    // Threatening enemy fleet in 5 SZ (adjacent to 6 SZ).
    final List<Unit> enemyFleet = GameDataTestUtil.battleship(data).create(5, japanese);
    data.performChange(ChangeFactory.addUnits(sz5, enemyFleet));

    // AmphContext: infantry embarked (not fresh — prior turn).
    final AmphContext ctx =
        new AmphContext(true, Set.of(infantry.getId()), Set.of(/* not embarkedThisTurn */ ));

    final List<MoveDescription> dispatched = runNonCombatMove(americans, ctx);

    final boolean infantryDispatched =
        dispatched.stream().anyMatch(m -> m.getUnits().contains(infantry));

    assertThat(infantryDispatched)
        .as(
            "Already-embarked infantry in 6 SZ (prior-turn embark) must receive an NCM move "
                + "— retreat to Korea or transit to an adjacent SZ. "
                + "Before the fix the !isWater() loop skipped the unit entirely. "
                + "Dispatched: "
                + dispatched)
        .isTrue();
  }

  // ─── helpers ─────────────────────────────────────────────────────────────────

  private void declareWar(final GamePlayer p1, final GamePlayer p2) {
    data.performChange(
        ChangeFactory.relationshipChange(
            p1,
            p2,
            data.getRelationshipTracker().getRelationshipType(p1, p2),
            data.getRelationshipTypeList().getRelationshipType("War")));
  }

  private List<MoveDescription> runNonCombatMove(final GamePlayer p, final AmphContext ctx) {
    advanceToStep("americansNonCombatMove");

    final ProAi proAi = new ProAi("Test " + p.getName(), p.getName() + " AI");
    final PlayerBridge bridge = mock(PlayerBridge.class);
    proAi.initialize(bridge, p);
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
      proAi.invokeNonCombatMoveForSidecar(moveDelegate, data, p);
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
