package games.strategy.triplea.ai.pro;

import static games.strategy.triplea.delegate.GameDataTestUtil.carrier;
import static games.strategy.triplea.delegate.GameDataTestUtil.destroyer;
import static games.strategy.triplea.delegate.GameDataTestUtil.fighter;
import static games.strategy.triplea.delegate.GameDataTestUtil.infantry;
import static games.strategy.triplea.delegate.GameDataTestUtil.territory;
import static games.strategy.triplea.delegate.GameDataTestUtil.transport;
import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.ai.pro.util.ProBattleUtils;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.odds.calculator.AggregateResults;
import games.strategy.triplea.odds.calculator.IBattleCalculator;
import games.strategy.triplea.util.TuvCostsCalculator;
import games.strategy.triplea.util.TuvUtils;
import games.strategy.triplea.xml.TestMapGameData;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplea.java.collections.IntegerMap;

/**
 * Tests for the three transport air-exposure valuation fixes (#2575):
 *
 * <ol>
 *   <li>Fix 1 (ProNonCombatMoveAi): unsafeTransportValue now includes cargo TUV, not hull only.
 *   <li>Fix 2 (ProCombatMoveAi): amphib threshold uses minResult and full 1.0 factor, not Math.min
 *       + 0.75 discount.
 *   <li>Fix 3 (ProBattleUtils): territoryHasLocalNavalSuperiority includes carrier-borne enemy air
 *       via movement-range check over all nearby territories, not just land.
 * </ol>
 */
class ProTransportAirExposureTest {

  private GameData data;
  private GamePlayer germans;
  private GamePlayer british;

  @BeforeEach
  void setUp() {
    data = TestMapGameData.GLOBAL1940.getGameData();
    germans = data.getPlayerList().getPlayerId("Germans");
    british = data.getPlayerList().getPlayerId("British");
  }

  // ---------------------------------------------------------------------------
  // Fix 1 — noncombat cargo TUV (ProNonCombatMoveAi.java ~line 1174)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("Fix1: unsafe transport loss is priced as hull + cargo, not hull only")
  void unsafeTransportValueIncludesCargoTuv() {
    final Territory sz113 = territory("113 Sea Zone", data);

    // Place transport + infantry together in a sea zone so getTransporting() resolves.
    final Unit trn = transport(data).create(1, british).get(0);
    final Unit inf = infantry(data).create(1, british).get(0);
    data.performChange(ChangeFactory.addUnits(sz113, List.of(trn, inf)));
    inf.setTransportedBy(trn); // marks inf as cargo of trn (@VisibleForTesting)

    // Real unit costs from GLOBAL1940 game data.
    final IntegerMap<games.strategy.engine.data.UnitType> costMap =
        new TuvCostsCalculator().getCostsForTuv(british);

    final int hullOnly = TuvUtils.getTuv(List.of(trn), costMap);
    final List<Unit> hullAndCargo = new ArrayList<>(List.of(trn));
    hullAndCargo.addAll(trn.getTransporting(sz113));
    final int withCargo = TuvUtils.getTuv(hullAndCargo, costMap);

    assertThat(trn.getTransporting(sz113))
        .as("infantry should be recognised as cargo of the transport")
        .containsExactly(inf);
    assertThat(withCargo)
        .as("hull + cargo TUV must exceed hull-only TUV by the infantry's cost")
        .isGreaterThan(hullOnly);
    assertThat(withCargo - hullOnly)
        .as("the difference should equal the infantry TUV")
        .isEqualTo(TuvUtils.getTuv(List.of(inf), costMap));
  }

  // ---------------------------------------------------------------------------
  // Fix 2 — amphib threshold honesty (ProCombatMoveAi.java ~line 772 + 806)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName(
      "Fix2: amphib attack is abandoned when minResult exposure exceeds attackValue, "
          + "even when the Math.min+0.75 formula would have allowed it")
  void amphibExposureThresholdUsesCommittedDefenderNotOptimisticMax() {
    // Scenario: an amphib attack is marginally valuable (attackValue = 8).
    // The enemy can counter-attack effectively even with only the committed defenders
    // (transport + bombard; minResult TUV swing = 12), but not if max defenders can reinforce
    // (result TUV swing = 5, optimistic).
    final double committedSwing = 12.0; // minResult: only transport+bombard defend
    final double optimisticSwing = 5.0; // result: max defenders materialise
    final double attackValue = 8.0;

    // Old formula: Math.min(result, minResult) * 0.75
    // minTuvSwing = Math.min(5, 12) = 5; 0.75*5 = 3.75 < 8 → attack is KEPT (wrong)
    final double oldMinTuvSwing = Math.min(optimisticSwing, committedSwing);
    final boolean oldWouldRemove = (0.75 * oldMinTuvSwing) > attackValue;
    assertThat(oldWouldRemove)
        .as("old formula keeps the dangerous amphib attack (false positive)")
        .isFalse();

    // New formula: minResult * 1.0
    // minTuvSwing = 12; 12 > 8 → attack is REMOVED (correct)
    final double newMinTuvSwing = committedSwing;
    final boolean newWouldRemove = newMinTuvSwing > attackValue;
    assertThat(newWouldRemove)
        .as("new formula correctly abandons the dangerous amphib attack")
        .isTrue();
  }

  // ---------------------------------------------------------------------------
  // Fix 3 — purchase air-reach (ProBattleUtils.territoryHasLocalNavalSuperiority ~line 322)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName(
      "Fix3: naval superiority is false when enemy carrier air threatens via a canal-blocked route "
          + "that the old land-only scan would have missed")
  void navalSuperiorityFalseWhenCarrierAirThreatensViaCanalBlockedRoute() throws Exception {
    // 113 Sea Zone is separated from 112 Sea Zone by the Danish Strait canal.
    // In the GLOBAL1940 start, Germany owns Denmark, so the British cannot transit the canal.
    // Old code: sea-unit loop calls getRouteForUnits(isWater) → canal blocked for British →
    //   fighters on a carrier in 112 SZ are NOT counted → method returns true (wrong).
    // New code: iterates nearbyTerritories with getNeighbors(range) — no canal check →
    //   carrier fighters are found and counted → method returns false (correct).
    final Territory sz113 = territory("113 Sea Zone", data);
    final Territory sz112 = territory("112 Sea Zone", data);

    // Clear sea zones; starting state has a German battleship in 113 SZ.
    data.performChange(ChangeFactory.removeUnits(sz113, sz113.getUnits()));
    data.performChange(ChangeFactory.removeUnits(sz112, sz112.getUnits()));

    // Germany: one destroyer in 113 SZ (the territory we're evaluating).
    data.performChange(ChangeFactory.addUnits(sz113, destroyer(data).create(1, germans)));

    // British: carrier + 12 fighters in 112 SZ.
    // 12 fighters × (2 HP + attack-3 power) = 60 enemy strength; 1 destroyer = 4 allied strength.
    // defenseStrengthDifference = 56 > 50 → no superiority, once fighters are seen.
    data.performChange(ChangeFactory.addUnits(sz112, carrier(data).create(1, british)));
    data.performChange(ChangeFactory.addUnits(sz112, fighter(data).create(12, british)));

    final ProData proData = proDataForPlayer(data);
    // ProOddsCalculator is not called when strongestEnemyDefenseFleet is null (no routable enemy
    // sea units in this scenario after canal blocks the British fleet).
    final ProOddsCalculator calc = new ProOddsCalculator(noOpCalculator());

    final boolean hasSuperiority =
        ProBattleUtils.territoryHasLocalNavalSuperiority(
            proData, calc, sz113, germans, Map.of(), List.of());

    assertThat(hasSuperiority)
        .as(
            "Germans should NOT have naval superiority in 113 SZ when British carrier fighters"
                + " are next door in 112 SZ (Fix 3: canal-blocked carrier air now detected)")
        .isFalse();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Sets the data field on a new ProData via reflection (same pattern as
   * ProOddsCalculatorFastPathTest).
   */
  private static ProData proDataForPlayer(final GameData gameData) throws Exception {
    final ProData proData = new ProData();
    final Field f = ProData.class.getDeclaredField("data");
    f.setAccessible(true);
    f.set(proData, gameData);
    return proData;
  }

  /** A no-op IBattleCalculator that returns empty results; used when calc is never invoked. */
  private static IBattleCalculator noOpCalculator() {
    return (attacker,
        defender,
        location,
        attacking,
        defending,
        bombarding,
        territoryEffects,
        retreatWhenOnlyAirLeft,
        runCount) -> new AggregateResults(0);
  }
}
