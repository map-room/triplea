package org.triplea.ai.sidecar.exec;

import games.strategy.engine.data.Change;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.delegate.battle.IBattle;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Minimal read-only {@link IBattle} stub registered in the session's {@code BattleTracker} for the
 * duration of a single ProAi decision call.
 *
 * <p>{@code AbstractProAi.selectCasualties} (and its sibling decision entry points) fetches the
 * pending battle out of {@code BattleTracker} by {@link UUID} and reads {@code getAttacker}, {@code
 * getAttackingUnits}, {@code getDefendingUnits} off it — nothing else. This stub therefore returns
 * the exact collections the sidecar passed in on the wire and throws {@link
 * UnsupportedOperationException} for every mutating / fight-step entry point, so an accidental call
 * into unsupported behaviour fails loudly rather than silently corrupting game state.
 *
 * <p>Not serialisable in any meaningful sense — the {@code serialVersionUID} exists only so the
 * compiler is happy about {@code implements Serializable} from {@link IBattle}; this class is never
 * persisted.
 */
final class SyntheticBattle implements IBattle {
  private static final long serialVersionUID = 1L;

  private final UUID battleId;
  private final Territory territory;
  private final GamePlayer attacker;
  private final GamePlayer defender;
  private final List<Unit> attackingUnits;
  private final List<Unit> defendingUnits;
  private final boolean amphibious;

  SyntheticBattle(
      final UUID battleId,
      final Territory territory,
      final GamePlayer attacker,
      final GamePlayer defender,
      final Collection<Unit> attackingUnits,
      final Collection<Unit> defendingUnits,
      final boolean amphibious) {
    this.battleId = battleId;
    this.territory = territory;
    this.attacker = attacker;
    this.defender = defender;
    this.attackingUnits = List.copyOf(attackingUnits);
    this.defendingUnits = List.copyOf(defendingUnits);
    this.amphibious = amphibious;
  }

  @Override
  public UUID getBattleId() {
    return battleId;
  }

  @Override
  public Territory getTerritory() {
    return territory;
  }

  @Override
  public GamePlayer getAttacker() {
    return attacker;
  }

  @Override
  public GamePlayer getDefender() {
    return defender;
  }

  @Override
  public Collection<Unit> getAttackingUnits() {
    return Collections.unmodifiableCollection(attackingUnits);
  }

  @Override
  public Collection<Unit> getDefendingUnits() {
    return Collections.unmodifiableCollection(defendingUnits);
  }

  @Override
  public Collection<Unit> getRemainingAttackingUnits() {
    return Collections.unmodifiableCollection(attackingUnits);
  }

  @Override
  public Collection<Unit> getRemainingDefendingUnits() {
    return Collections.unmodifiableCollection(defendingUnits);
  }

  @Override
  public boolean isAmphibious() {
    return amphibious;
  }

  @Override
  public BattleType getBattleType() {
    return BattleType.NORMAL;
  }

  @Override
  public WhoWon getWhoWon() {
    return WhoWon.NOT_FINISHED;
  }

  @Override
  public int getBattleRound() {
    return 1;
  }

  @Override
  public boolean isOver() {
    return false;
  }

  @Override
  public boolean isEmpty() {
    return attackingUnits.isEmpty() && defendingUnits.isEmpty();
  }

  @Override
  public Collection<Unit> getDependentUnits(final Collection<Unit> units) {
    return List.of();
  }

  @Override
  public Collection<Unit> getBombardingUnits() {
    return List.of();
  }

  @Override
  public void addBombardingUnit(final Unit u) {
    throw new UnsupportedOperationException("SyntheticBattle is read-only");
  }

  @Override
  public Change addAttackChange(
      final Route route, final Collection<Unit> units, final Map<Unit, Set<Unit>> targets) {
    throw new UnsupportedOperationException(
        "SyntheticBattle does not support mutating method addAttackChange"
            + " — defensive paths should not reach this method");
  }

  @Override
  public Change removeAttack(final Route route, final Collection<Unit> units) {
    throw new UnsupportedOperationException(
        "SyntheticBattle does not support mutating method removeAttack"
            + " — defensive paths should not reach this method");
  }

  @Override
  public void fight(final IDelegateBridge bridge) {
    throw new UnsupportedOperationException("SyntheticBattle cannot fight");
  }

  @Override
  public void cancelBattle(final IDelegateBridge bridge) {
    // no-op
  }

  @Override
  public void unitsLostInPrecedingBattle(
      final Collection<Unit> units, final IDelegateBridge bridge, final boolean withdrawn) {
    // no-op
  }

  @Override
  public void fixUpNullPlayer(final GamePlayer nullPlayer) {
    // no-op
  }
}
