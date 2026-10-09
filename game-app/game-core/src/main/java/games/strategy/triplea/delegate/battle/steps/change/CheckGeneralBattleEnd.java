package games.strategy.triplea.delegate.battle.steps.change;

import static games.strategy.triplea.delegate.battle.BattleState.Side.DEFENSE;
import static games.strategy.triplea.delegate.battle.BattleState.Side.OFFENSE;
import static games.strategy.triplea.delegate.battle.BattleState.UnitBattleFilter.ACTIVE;
import static games.strategy.triplea.delegate.battle.BattleState.UnitBattleFilter.ALIVE;

import com.google.common.collect.Iterables;
import games.strategy.engine.data.Unit;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.Properties;
import games.strategy.triplea.attachments.UnitAttachment;
import games.strategy.triplea.delegate.ExecutionStack;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.battle.BattleActions;
import games.strategy.triplea.delegate.battle.BattleState;
import games.strategy.triplea.delegate.battle.IBattle;
import games.strategy.triplea.delegate.battle.steps.BattleStep;
import games.strategy.triplea.delegate.battle.steps.RetreatChecks;
import games.strategy.triplea.delegate.battle.steps.fire.FiringGroup;
import games.strategy.triplea.delegate.battle.steps.fire.general.FiringGroupSplitterGeneral;
import games.strategy.triplea.delegate.power.calculator.CombatValueBuilder;
import games.strategy.triplea.delegate.power.calculator.PowerStrengthAndRolls;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.triplea.java.collections.CollectionUtils;

public class CheckGeneralBattleEnd implements BattleStep {
  private static final long serialVersionUID = 5172121497955756220L;

  private final BattleState battleState;

  private final BattleActions battleActions;

  // map-room#3698: shared (not static) so that a sibling CheckStalemateBattleEnd constructed for
  // the same round can see this round's cached isStalemate() result instead of recomputing it
  // from scratch. Not final/Lombok-generated so that old saved games deserializing this class
  // without the field default it to null, in which case isStalemate() simply skips caching.
  private final StalemateCache stalemateCache;

  public CheckGeneralBattleEnd(final BattleState battleState, final BattleActions battleActions) {
    this(battleState, battleActions, new StalemateCache());
  }

  CheckGeneralBattleEnd(
      final BattleState battleState,
      final BattleActions battleActions,
      final StalemateCache stalemateCache) {
    this.battleState = battleState;
    this.battleActions = battleActions;
    this.stalemateCache = stalemateCache;
  }

  protected BattleActions getBattleActions() {
    return battleActions;
  }

  protected BattleState getBattleState() {
    return battleState;
  }

  StalemateCache getStalemateCache() {
    return stalemateCache;
  }

  @Override
  public List<StepDetails> getAllStepDetails() {
    return List.of();
  }

  @Override
  public Order getOrder() {
    return Order.GENERAL_BATTLE_END_CHECK;
  }

  @Override
  public void execute(final ExecutionStack stack, final IDelegateBridge bridge) {
    if (hasSideLost(OFFENSE)) {
      battleActions.endBattle(IBattle.WhoWon.DEFENDER, bridge);
    } else if (hasSideLost(DEFENSE)) {
      battleActions.endBattle(IBattle.WhoWon.ATTACKER, bridge);
    } else if (isStalemate() && !canAttackerRetreatInStalemate()) {
      battleActions.endBattle(IBattle.WhoWon.DRAW, bridge);
    }
  }

  protected boolean hasSideLost(final BattleState.Side side) {
    return battleState.filterUnits(ALIVE, side).stream()
        .noneMatch(Matches.unitIsNotInfrastructure());
  }

  private Predicate<Unit> inAnyFiringGroup(Iterable<FiringGroup> firingGroups) {
    return u ->
        StreamSupport.stream(firingGroups.spliterator(), false)
            .anyMatch(fg -> fg.getFiringUnits().contains(u));
  }

  protected boolean isStalemate() {
    // map-room#3698: CheckGeneralBattleEnd and CheckStalemateBattleEnd both call isStalemate()
    // on the same battleState, back-to-back within a round, in the common case where no retreat
    // happens between GENERAL_BATTLE_END_CHECK and STALEMATE_BATTLE_END_CHECK. Since apply()'s
    // only inputs that can vary within a battle are the ALIVE/ACTIVE unit sets for both sides
    // (player, gameData, battleSite, territoryEffects and game Properties are all fixed for the
    // duration of one battle resolution), a snapshot of those four sets is a complete cache key.
    final List<Unit> aliveOffense = List.copyOf(battleState.filterUnits(ALIVE, OFFENSE));
    final List<Unit> activeOffense = List.copyOf(battleState.filterUnits(ACTIVE, OFFENSE));
    final List<Unit> aliveDefense = List.copyOf(battleState.filterUnits(ALIVE, DEFENSE));
    final List<Unit> activeDefense = List.copyOf(battleState.filterUnits(ACTIVE, DEFENSE));

    if (stalemateCache != null
        && stalemateCache.result != null
        && aliveOffense.equals(stalemateCache.aliveOffense)
        && activeOffense.equals(stalemateCache.activeOffense)
        && aliveDefense.equals(stalemateCache.aliveDefense)
        && activeDefense.equals(stalemateCache.activeDefense)) {
      return stalemateCache.result;
    }

    final boolean result = computeIsStalemate();
    if (stalemateCache != null) {
      stalemateCache.aliveOffense = aliveOffense;
      stalemateCache.activeOffense = activeOffense;
      stalemateCache.aliveDefense = aliveDefense;
      stalemateCache.activeDefense = activeDefense;
      stalemateCache.result = result;
    }
    return result;
  }

  private boolean computeIsStalemate() {
    if (battleState.getStatus().isLastRound()) {
      return true;
    }
    final Iterable<FiringGroup> attackerFiringGroups = getAllFiringGroups(OFFENSE);
    // Filter attackers to only units that are in firing groups, to eliminate units
    // that can technically roll dice in abstract, but not against any current enemies.
    final Collection<Unit> attackers =
        CollectionUtils.getMatches(
            battleState.filterUnits(ALIVE, OFFENSE), inAnyFiringGroup(attackerFiringGroups));
    final Iterable<FiringGroup> defendersFiringGroups = getAllFiringGroups(DEFENSE);
    // Filter defenders to only units that are in firing groups, to eliminate units
    // that can technically roll dice in abstract, but not against any current enemies.
    final Collection<Unit> defenders =
        CollectionUtils.getMatches(
            battleState.filterUnits(ALIVE, DEFENSE), inAnyFiringGroup(defendersFiringGroups));
    return battleState.getStatus().isLastRound()
        || (hasNoStrengthOrRolls(OFFENSE, attackers, defenders)
            && hasNoStrengthOrRolls(DEFENSE, defenders, attackers))
        || (hasNoTargets(attackerFiringGroups) && hasNoTargets(defendersFiringGroups));
  }

  /** Mutable holder so a sibling step can share this round's memoized {@link #isStalemate()}. */
  static final class StalemateCache implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    private List<Unit> aliveOffense;
    private List<Unit> activeOffense;
    private List<Unit> aliveDefense;
    private List<Unit> activeDefense;
    private Boolean result;
  }

  private boolean hasNoStrengthOrRolls(
      final BattleState.Side side,
      final Collection<Unit> myUnits,
      final Collection<Unit> enemyUnits) {
    return !PowerStrengthAndRolls.buildWithPreSortedUnits(
            myUnits,
            CombatValueBuilder.mainCombatValue()
                .enemyUnits(enemyUnits)
                .friendlyUnits(myUnits)
                .side(side)
                .gameSequence(battleState.getGameData().getSequence())
                .supportAttachments(battleState.getGameData().getUnitTypeList().getSupportRules())
                .lhtrHeavyBombers(
                    Properties.getLhtrHeavyBombers(battleState.getGameData().getProperties()))
                .gameDiceSides(battleState.getGameData().getDiceSides())
                .territoryEffects(battleState.getTerritoryEffects())
                .build())
        .hasStrengthOrRolls();
  }

  private Iterable<FiringGroup> getAllFiringGroups(final BattleState.Side side) {
    return Iterables.concat(
        getFiringGroup(side, FiringGroupSplitterGeneral.Type.NORMAL),
        getFiringGroup(side, FiringGroupSplitterGeneral.Type.FIRST_STRIKE));
  }

  private List<FiringGroup> getFiringGroup(
      final BattleState.Side side, final FiringGroupSplitterGeneral.Type type) {
    return FiringGroupSplitterGeneral.of(side, type, "stalemate").apply(battleState);
  }

  private boolean hasNoTargets(Iterable<FiringGroup> firingGroups) {
    return Iterables.isEmpty(firingGroups);
  }

  protected boolean canAttackerRetreatInStalemate() {
    // First check if any units have an explicit "can retreat on stalemate"
    // property. If none do (all are null), then we will use a fallback algorithm.
    // If any unit has "can retreat on stalemate" set, then we will return true
    // only if all units either have the property set to null or true, if any
    // are set to false then we will return false.

    // Otherwise, if we do not have an explicit property, then we fallback
    // to enforcing the V3 transport vs transport rule that allows retreat in
    // that situation. Ideally all maps would explicitly use the "can retreat
    // on stalemate property", but not all do so we need to account for the
    // V3 transport vs transport rule as a fallback algorithm without it.

    // First, collect all of the non-null 'can retreat on stalemate' option values.
    final Set<Boolean> canRetreatOptions =
        battleState.filterUnits(ALIVE, OFFENSE).stream()
            .map(Unit::getUnitAttachment)
            .map(UnitAttachment::getCanRetreatOnStalemate)
            .flatMap(Optional::stream)
            .collect(Collectors.toSet());

    final boolean propertyIsSetAtLeastOnce = !canRetreatOptions.isEmpty();

    // next, check if all of the non-null properties are set to true.
    final boolean allowRetreatFromProperty = canRetreatOptions.stream().allMatch(b -> b);

    return (propertyIsSetAtLeastOnce && allowRetreatFromProperty)
        || (!propertyIsSetAtLeastOnce && transportsVsTransports());
  }

  private boolean transportsVsTransports() {
    // Check if both sides have only V3 (non-combat) transports remaining.
    // See: https://github.com/triplea-game/triplea/issues/2367
    // Rule: "In a sea battle, if both sides have only transports remaining, the
    // attacker’s transports can remain in the contested sea zone or retreat.
    return RetreatChecks.onlyDefenselessTransportsLeft(
            battleState.filterUnits(ALIVE, OFFENSE), battleState.getGameData())
        && RetreatChecks.onlyDefenselessTransportsLeft(
            battleState.filterUnits(ALIVE, DEFENSE), battleState.getGameData());
  }
}
