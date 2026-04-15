package org.triplea.ai.sidecar.exec;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.AbstractProAi;
import games.strategy.triplea.delegate.battle.BattleTracker;
import games.strategy.triplea.delegate.battle.IBattle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.triplea.ai.sidecar.dto.RetreatPlan;
import org.triplea.ai.sidecar.dto.RetreatQueryRequest;
import org.triplea.ai.sidecar.session.Session;
import org.triplea.ai.sidecar.wire.WireStateApplier;

/**
 * Executes a {@code retreat-or-press} decision by invoking {@link
 * AbstractProAi#retreatQuery(UUID, boolean, Territory, Collection, String)}.
 *
 * <p>Symmetric with {@link SelectCasualtiesExecutor}: applies the embedded {@code WireState},
 * synthesises a pending {@link IBattle} in the session {@link BattleTracker}, dispatches into
 * ProAi, and maps the {@code Optional<Territory>} result back to a wire-shaped {@link
 * RetreatPlan} carrying the territory name (or {@code null} if ProAi decides to press).
 *
 * <p>The request DTO intentionally does <b>not</b> carry attacker/defender/unit collections —
 * per the Phase 2 detector contract ({@code detectRetreatQuery}) the retreating player is
 * always the session nation playing the attacker role. This executor therefore derives:
 *
 * <ul>
 *   <li>{@code attacker}  = the session's {@link GamePlayer}
 *   <li>{@code attackingUnits} = units on {@code battleTerritory} owned by the attacker
 *   <li>{@code defender}  = first non-allied unit owner on {@code battleTerritory}, or the
 *       session-nation enemy list's head if the territory is currently unit-less (e.g. fully
 *       amphibious stack not yet resolved)
 *   <li>{@code defendingUnits} = all other units on {@code battleTerritory}
 *   <li>{@code isAmphibious} = always {@code false} (if the detector fires, it is a standard
 *       retreat decision; amphibious retreats short-circuit to {@code Optional.empty()} in
 *       ProAi anyway)
 * </ul>
 *
 * <p>If {@code possibleRetreatTerritories} is empty the executor returns a {@link RetreatPlan}
 * with {@code retreatTo = null} without even calling ProAi — ProRetreatAi's retreat loop over
 * an empty collection would return {@code Optional.empty()} anyway, but short-circuiting here
 * keeps the no-op fast and avoids needing a valid synthesised battle for the degenerate case.
 */
public final class RetreatQueryExecutor
    implements DecisionExecutor<RetreatQueryRequest, RetreatPlan> {

  @Override
  public RetreatPlan execute(final Session session, final RetreatQueryRequest request) {
    final GameData data = session.gameData();

    WireStateApplier.apply(data, request.state(), session.unitIdMap());

    final RetreatQueryRequest.RetreatQueryBattle b = request.battle();
    if (b.possibleRetreatTerritories().isEmpty()) {
      return new RetreatPlan(null);
    }

    final Territory battleSite = data.getMap().getTerritoryOrNull(b.battleTerritory());
    if (battleSite == null) {
      throw new IllegalArgumentException("Unknown battle territory: " + b.battleTerritory());
    }

    final GamePlayer attacker = requirePlayer(data, session.key().nation());

    // Resolve possible retreat territories to live Territory instances; skip any unknown
    // names rather than throwing — ProRetreatAi iterates them and picks the strongest, so
    // a stray bad name would just be silently ignored, but a live map lookup will NPE
    // downstream on equality checks. Fail loudly on empty-after-resolve.
    final List<Territory> possibleTerritories =
        new ArrayList<>(b.possibleRetreatTerritories().size());
    for (final String name : b.possibleRetreatTerritories()) {
      final Territory t = data.getMap().getTerritoryOrNull(name);
      if (t == null) {
        throw new IllegalArgumentException(
            "RetreatQueryRequest references unknown territory: " + name);
      }
      possibleTerritories.add(t);
    }

    final List<Unit> attackingUnits = new ArrayList<>();
    final List<Unit> defendingUnits = new ArrayList<>();
    GamePlayer defender = null;
    for (final Unit u : battleSite.getUnits()) {
      if (attacker.equals(u.getOwner())) {
        attackingUnits.add(u);
      } else {
        defendingUnits.add(u);
        if (defender == null) {
          defender = u.getOwner();
        }
      }
    }
    if (defender == null) {
      // Territory currently has no enemy units — pick any other player deterministically so
      // battle.getDefender() is non-null. ProRetreatAi only reads getAttackingUnits /
      // getDefendingUnits / getAttacker, so the exact GamePlayer identity here is not
      // load-bearing, but must be non-null.
      defender =
          data.getPlayerList().getPlayers().stream()
              .filter(p -> !p.equals(attacker))
              .findFirst()
              .orElse(attacker);
    }

    ExecutorSupport.ensureProAiInitialized(session, attacker);
    ExecutorSupport.ensureBattleDelegate(data);
    final BattleTracker tracker = data.getBattleDelegate().getBattleTracker();

    final UUID battleUuid = UUID.nameUUIDFromBytes(b.battleId().getBytes());
    final SyntheticBattle synthetic =
        new SyntheticBattle(
            battleUuid,
            battleSite,
            attacker,
            defender,
            attackingUnits,
            defendingUnits,
            /* amphibious */ false);
    ExecutorSupport.addPendingBattle(tracker, synthetic);

    final Optional<Territory> chosen;
    try {
      chosen =
          session
              .proAi()
              .retreatQuery(
                  battleUuid,
                  b.canSubmerge(),
                  battleSite,
                  possibleTerritories,
                  "sidecar-retreat-query");
    } finally {
      ExecutorSupport.removePendingBattle(tracker, synthetic);
    }

    return new RetreatPlan(chosen.map(Territory::getName).orElse(null));
  }

  private static GamePlayer requirePlayer(final GameData data, final String name) {
    final GamePlayer p = data.getPlayerList().getPlayerId(name);
    if (p == null) {
      throw new IllegalArgumentException("Unknown player in RetreatQueryRequest session: " + name);
    }
    return p;
  }
}
