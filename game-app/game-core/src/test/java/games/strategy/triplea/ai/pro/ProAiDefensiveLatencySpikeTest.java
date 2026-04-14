package games.strategy.triplea.ai.pro;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameSequence;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.player.PlayerBridge;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.PurchaseDelegate;
import games.strategy.triplea.delegate.battle.BattleDelegate;
import games.strategy.triplea.delegate.battle.BattleTracker;
import games.strategy.triplea.delegate.battle.IBattle;
import games.strategy.triplea.delegate.battle.MustFightBattle;
import games.strategy.triplea.delegate.data.CasualtyDetails;
import games.strategy.triplea.delegate.data.CasualtyList;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.xml.TestMapGameData;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

/**
 * Issue #1735 — defensive latency micro-spike.
 *
 * <p>Wires a real {@link MustFightBattle} (Germans defending Germany against a hypothetical UK
 * land-invasion force) into the real {@link BattleTracker}'s {@code pendingBattles} set via
 * reflection, then drives {@link AbstractProAi#selectCasualties} and {@link
 * AbstractProAi#retreatQuery} directly — the same way step 1 drove {@code purchase()}. Measures
 * cold + 3 warm runs each and dumps the DTO shape (unit picks for casualties, Optional territory
 * for retreat).
 *
 * <p>Critical question for #1734: are defensive calls sub-second, or do they also blow up to ~10
 * s like purchase? Purchase was expensive because it forward-simulates combat/ncm/place steps;
 * these two entry points do not (selectCasualties is pure sort, retreatQuery runs a single odds
 * calc).
 */
public class ProAiDefensiveLatencySpikeTest {

  @Test
  public void germansDefendingGermanyLatency() throws Exception {
    ClientSetting.setPreferences(new MemoryPreferences());

    final GameData gameData = TestMapGameData.GLOBAL1940.getGameData();
    final GamePlayer germans = gameData.getPlayerList().getPlayerId("Germans");
    final GamePlayer british = gameData.getPlayerList().getPlayerId("British");
    Assertions.assertNotNull(germans);
    Assertions.assertNotNull(british);

    // Need to initialize the PurchaseDelegate so the ProAi can spin up (initialize() looks at the
    // sequence). Any step name is fine — pick whichever the XML starts at; AbstractProAi's
    // selectCasualties/retreatQuery don't call the step-index walker.
    advanceToPurchaseStepFor(gameData, "Germans");

    final Territory germany = gameData.getMap().getTerritoryOrNull("Germany");
    Assertions.assertNotNull(germany, "Germany territory must exist in Global 1940");

    // Defender units: everything already in Germany territory (German-owned).
    final List<Unit> defenders = new ArrayList<>(germany.getUnits());
    Assertions.assertFalse(defenders.isEmpty(), "Germany must have defenders at game start");

    // Attacker units: grab a realistic UK land force from United Kingdom for the spike — we don't
    // move them in the map, we just hand them to MustFightBattle.setUnits() as the "attacking"
    // side, which is a headless flag path documented on MustFightBattle.setUnits.
    final Territory uk = gameData.getMap().getTerritoryOrNull("United Kingdom");
    Assertions.assertNotNull(uk);
    final List<Unit> attackers = new ArrayList<>(uk.getUnits());
    Assertions.assertFalse(attackers.isEmpty(), "UK must have units to borrow as attackers");

    // Wire a real MustFightBattle into the real BattleDelegate's BattleTracker via reflection on
    // the private pendingBattles field. This is the only way to get
    // battleTracker.getPendingBattle(battleId) to return non-null inside AbstractProAi without
    // running the full CombatMove step.
    final BattleDelegate battleDelegate = (BattleDelegate) gameData.getDelegate("battle");
    final BattleTracker tracker = battleDelegate.getBattleTracker();
    final MustFightBattle battle = new MustFightBattle(germany, british, gameData, tracker);
    battle.setHeadless(true);
    battle.setUnits(defenders, attackers, List.of(), germans, List.of());
    injectPendingBattle(tracker, battle);
    final UUID battleId = battle.getBattleId();
    System.out.printf(
        "[spike-def] battle wired: germany defenders=%d vs uk attackers=%d battleId=%s%n",
        defenders.size(), attackers.size(), battleId);

    // Spin up the real ProAi for Germans.
    final ProAi proAi = new ProAi("SpikeDef", "Germans");
    final IDelegateBridge bridge = newDelegateBridge(germans);
    final PurchaseDelegate purchaseDelegate = (PurchaseDelegate) gameData.getDelegate("purchase");
    purchaseDelegate.setDelegateBridgeAndPlayer(bridge);
    final PlayerBridge playerBridgeMock = Mockito.mock(PlayerBridge.class);
    when(playerBridgeMock.getGameData()).thenReturn(gameData);
    proAi.initialize(playerBridgeMock, germans);

    // ---- selectCasualties ----
    // Default casualty list: kill the first 3 defender units.
    final int hitCount = 3;
    final CasualtyList defaultCasualties = new CasualtyList();
    for (int i = 0; i < hitCount && i < defenders.size(); i++) {
      defaultCasualties.addToKilled(defenders.get(i));
    }

    final long[] selectTimes = new long[4];
    CasualtyDetails lastDetails = null;
    for (int i = 0; i < selectTimes.length; i++) {
      final long t = System.nanoTime();
      lastDetails =
          proAi.selectCasualties(
              defenders,
              new HashMap<>(),
              hitCount,
              "spike",
              Mockito.mock(games.strategy.triplea.delegate.DiceRoll.class),
              germans,
              defenders,
              attackers,
              false,
              Collections.emptyList(),
              defaultCasualties,
              battleId,
              germany,
              false);
      selectTimes[i] = System.nanoTime() - t;
    }
    final String selectDto = casualtiesToJson(lastDetails);
    System.out.printf(
        "[spike-def] selectCasualties cold=%.1fms warm1=%.1fms warm2=%.1fms warm3=%.1fms%n",
        selectTimes[0] / 1e6,
        selectTimes[1] / 1e6,
        selectTimes[2] / 1e6,
        selectTimes[3] / 1e6);
    System.out.println("[spike-def] selectCasualties DTO: " + selectDto);

    // ---- retreatQuery ----
    // Possible retreat destinations: all neighbours Germans own.
    final Collection<Territory> retreatTerritories = new ArrayList<>();
    for (final Territory neighbor : gameData.getMap().getNeighbors(germany)) {
      if (germans.equals(neighbor.getOwner()) && !neighbor.isWater()) {
        retreatTerritories.add(neighbor);
      }
    }
    System.out.printf("[spike-def] retreat candidates=%d%n", retreatTerritories.size());

    final long[] retreatTimes = new long[4];
    java.util.Optional<Territory> lastRetreat = java.util.Optional.empty();
    for (int i = 0; i < retreatTimes.length; i++) {
      final long t = System.nanoTime();
      lastRetreat = proAi.retreatQuery(battleId, false, germany, retreatTerritories, "spike");
      retreatTimes[i] = System.nanoTime() - t;
    }
    System.out.printf(
        "[spike-def] retreatQuery    cold=%.1fms warm1=%.1fms warm2=%.1fms warm3=%.1fms%n",
        retreatTimes[0] / 1e6,
        retreatTimes[1] / 1e6,
        retreatTimes[2] / 1e6,
        retreatTimes[3] / 1e6);
    System.out.println("[spike-def] retreatQuery DTO: " + retreatToJson(lastRetreat));
  }

  @SuppressWarnings("unchecked")
  private static void injectPendingBattle(final BattleTracker tracker, final IBattle battle)
      throws Exception {
    final Field f = BattleTracker.class.getDeclaredField("pendingBattles");
    f.setAccessible(true);
    final Set<IBattle> set = (Set<IBattle>) f.get(tracker);
    set.add(battle);
  }

  private static String casualtiesToJson(final CasualtyDetails d) {
    if (d == null) {
      return "(null)";
    }
    final StringBuilder sb = new StringBuilder();
    sb.append("{\"kind\":\"selectCasualties\",\"killed\":[");
    boolean first = true;
    for (final Unit u : d.getKilled()) {
      if (!first) {
        sb.append(",");
      }
      first = false;
      sb.append("\"").append(u.getType().getName()).append("\"");
    }
    sb.append("],\"damaged\":[");
    first = true;
    for (final Unit u : d.getDamaged()) {
      if (!first) {
        sb.append(",");
      }
      first = false;
      sb.append("\"").append(u.getType().getName()).append("\"");
    }
    sb.append("],\"autoCalculated\":").append(d.getAutoCalculated()).append("}");
    return sb.toString();
  }

  private static String retreatToJson(final java.util.Optional<Territory> opt) {
    if (opt.isEmpty()) {
      return "{\"kind\":\"retreatQuery\",\"retreat\":false}";
    }
    return "{\"kind\":\"retreatQuery\",\"retreat\":true,\"to\":\""
        + opt.get().getName()
        + "\"}";
  }

  private static void advanceToPurchaseStepFor(final GameData gameData, final String playerName) {
    final GameSequence sequence = gameData.getSequence();
    int tries = 0;
    while (tries++ < 200) {
      final GameStep step = sequence.getStep();
      if (step != null
          && step.getPlayerId() != null
          && playerName.equals(step.getPlayerId().getName())
          && GameStep.isPurchaseStepName(step.getName())) {
        return;
      }
      sequence.next();
    }
    throw new IllegalStateException("Never reached " + playerName + " purchase step");
  }
}
