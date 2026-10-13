package games.strategy.triplea.delegate.battle;

import static games.strategy.triplea.delegate.GameDataTestUtil.germans;
import static games.strategy.triplea.delegate.GameDataTestUtil.infantry;
import static games.strategy.triplea.delegate.GameDataTestUtil.russians;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static games.strategy.triplea.delegate.battle.BattleState.Side.DEFENSE;
import static games.strategy.triplea.delegate.battle.BattleState.Side.OFFENSE;
import static games.strategy.triplea.delegate.battle.BattleState.UnitBattleFilter.ALIVE;
import static games.strategy.triplea.delegate.battle.BattleState.UnitBattleFilter.CASUALTY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.delegate.TerritoryEffectHelper;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * map-room#3698: {@code MustFightBattle.filterUnits}'s round-scoped cache. Real {@link
 * MustFightBattle}, constructed the same way {@code BattleCalculator} does in production
 * (the-real-battle-calc discipline this step's design followed) — not a mock.
 */
class MustFightBattleFilterUnitsCacheTest extends AbstractClientSettingTestCase {

  private static MustFightBattle battle(final GameData gameData) {
    final Territory germany = gameData.getMap().getTerritoryOrNull("Germany");
    final GamePlayer russians = russians(gameData);
    final GamePlayer germans = germans(gameData);
    final List<Unit> attacking = infantry(gameData).create(8, russians);
    final List<Unit> defending = infantry(gameData).create(6, germans);
    final MustFightBattle battle =
        new MustFightBattle(germany, russians, gameData, new BattleTracker());
    battle.setHeadless(true);
    battle.setUnits(
        defending, attacking, List.of(), germans, TerritoryEffectHelper.getEffects(germany));
    return battle;
  }

  @Test
  void repeatedCallsWithNoMutationHitTheCache() {
    final MustFightBattle battle = battle(TestMapGameData.GLOBAL1940.getGameData());

    final Collection<Unit> first = battle.filterUnits(ALIVE, OFFENSE);
    final Collection<Unit> second = battle.filterUnits(ALIVE, OFFENSE);
    final Collection<Unit> third = battle.filterUnits(ALIVE, OFFENSE);

    assertEquals(8, first.size());
    assertEquals(first, second);
    assertEquals(first, third);
    // The real proof, not an inference from content equality: the expensive recompute path
    // ran exactly once for three identical, unmutated-between calls — a call-count reduction
    // proof in the same style as #173's 52->32 FiringGroupSplitterGeneral.apply() count.
    assertEquals(1, battle.computeFilterUnitsCallCount);
  }

  @Test
  void aDifferentFilterOrSideIsNotServedFromTheSameCacheEntry() {
    final MustFightBattle battle = battle(TestMapGameData.GLOBAL1940.getGameData());

    battle.filterUnits(ALIVE, OFFENSE);
    battle.filterUnits(ALIVE, DEFENSE);
    battle.filterUnits(CASUALTY, OFFENSE);

    assertEquals(3, battle.computeFilterUnitsCallCount);
  }

  @Test
  void markCasualtiesInvalidatesTheCache_aCasualtyRemoved() {
    final MustFightBattle battle = battle(TestMapGameData.GLOBAL1940.getGameData());

    final Collection<Unit> before = battle.filterUnits(ALIVE, OFFENSE);
    assertEquals(8, before.size());
    final Unit casualty = before.iterator().next();

    battle.markCasualties(List.of(casualty), OFFENSE);

    final Collection<Unit> after = battle.filterUnits(ALIVE, OFFENSE);
    assertEquals(7, after.size());
    assertFalse(
        after.contains(casualty),
        "a cached ALIVE/OFFENSE list must not still name a unit just marked a casualty");
    // Confirms the second filterUnits() call actually recomputed (real invalidation), not that
    // the pre-existing cached object happened to still read correctly by coincidence.
    assertEquals(2, battle.computeFilterUnitsCallCount);
  }

  @Test
  void clearWaitingToDieInvalidatesTheCache_midRoundRetreatAdjacentCleanup() {
    final MustFightBattle battle = battle(TestMapGameData.GLOBAL1940.getGameData());
    final Collection<Unit> alive = battle.filterUnits(ALIVE, OFFENSE);
    final Unit casualty = alive.iterator().next();
    battle.markCasualties(List.of(casualty), OFFENSE);

    final Collection<Unit> waitingBefore = battle.filterUnits(CASUALTY, OFFENSE);
    assertTrue(
        waitingBefore.contains(casualty),
        "markCasualties should have staged it as a CASUALTY, waiting to die");

    battle.clearWaitingToDieAndDamagedChangesInto(
        newDelegateBridge(russians(battle.getGameData())), OFFENSE);

    final Collection<Unit> waitingAfter = battle.filterUnits(CASUALTY, OFFENSE);
    assertTrue(
        waitingAfter.isEmpty(),
        "a cached CASUALTY/OFFENSE list must reflect clearWaitingToDieAndDamagedChangesInto's clear(), not the stale pre-clear snapshot");
  }
}
