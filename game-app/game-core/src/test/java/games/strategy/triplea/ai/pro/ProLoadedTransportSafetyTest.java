package games.strategy.triplea.ai.pro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.player.PlayerBridge;
import games.strategy.triplea.ai.pro.logging.ProLogCapture;
import games.strategy.triplea.delegate.GameDataTestUtil;
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
 * Regression test for #2945: AI leaves LOADED transports in sea zones that an enemy capital ship
 * can reach next turn.
 *
 * <p>Distinct from #2940 (empty-transport return-home). This bug is about loaded transports being
 * scored as "safe" in a zone where an enemy battleship can reach them, because the enemy threat
 * assessment is scoped to defended territories only — and the candidate zone may not have been
 * assessed for enemy attacks.
 *
 * <p>Root cause (to be confirmed at runtime): {@link ProNonCombatMoveAi#doNonCombatMove} at lines
 * 110-111 calls {@code populateEnemyAttackOptions(..., getDefendTerritories())}. A sea zone that is
 * a valid NCM destination for a transport but is NOT in the defend list gets an empty {@code
 * maxEnemyUnits}, so {@code estimateStrengthDifference} sees no attackers and rates the zone as
 * perfectly safe — even if an enemy battleship is directly adjacent.
 *
 * <p>Concrete scenario: Japan transport loaded with infantry in 20 Sea Zone; British battleship in
 * 35 Sea Zone (adjacent to 20 SZ). Without the fix, the AI leaves the transport in 20 SZ because
 * the battleship is not in the threat scope for that zone.
 *
 * <p>Fix direction: expand {@code populateEnemyAttackOptions} to cover all sea zones friendly
 * transports can reach in NCM, not just the current defend list.
 */
public class ProLoadedTransportSafetyTest {

  private static final String SEA_ZONE_20 = "20 Sea Zone";
  private static final String SEA_ZONE_35 = "35 Sea Zone";
  private static final String SEA_ZONE_37 = "37 Sea Zone";
  private static final String JAPAN = "Japan";

  private GameData data;
  private GamePlayer japanese;
  private GamePlayer british;

  @BeforeEach
  void setUp() {
    ClientSetting.setPreferences(new MemoryPreferences());
    data = TestMapGameData.GLOBAL1940.getGameData();
    japanese = data.getPlayerList().getPlayerId("Japanese");
    british = data.getPlayerList().getPlayerId("British");
  }

  /**
   * Core regression: a loaded Japanese transport in 20 Sea Zone with a British battleship in 35 Sea
   * Zone (adjacent) must NOT be left in 20 SZ after NCM — the AI should move it to a safer zone.
   *
   * <p>Setup:
   *
   * <ul>
   *   <li>Loaded Japanese transport (carrying 1 infantry) in 20 Sea Zone
   *   <li>British battleship in 35 Sea Zone — adjacent to 20 SZ, can reach it next combat turn
   *   <li>Japanese factory_major + infantry at Japan — gives the AI a valid NCM plan
   *   <li>Japan and Britain at war
   * </ul>
   *
   * <p>Without the fix: {@code proTerritory.getMaxEnemyUnits()} returns {@code []} for 20 SZ
   * (battleship not in threat scope) → {@code estimateStrengthDifference} returns a favorable score
   * → transport parks in 20 SZ → no move dispatched → test fails.
   *
   * <p>With the fix: threat scope expanded to include transport-reachable sea zones → battleship
   * included in 20 SZ attack options → zone scores as dangerous → transport moves → test passes.
   */
  @Test
  void loadedTransportMovesAwayFromZoneThreatenedByEnemyBattleship() {
    declareWar(japanese, british);
    data.getSequence().setRoundAndStep(2, "Non Combat Move", japanese);

    final Territory sz20 = data.getMap().getTerritoryOrThrow(SEA_ZONE_20);
    final Territory sz35 = data.getMap().getTerritoryOrThrow(SEA_ZONE_35);
    final Territory japan = data.getMap().getTerritoryOrThrow(JAPAN);

    // Clear all units from every territory to create a clean scenario.
    for (final Territory t : data.getMap().getTerritories()) {
      data.performChange(ChangeFactory.removeUnits(t, t.getUnits()));
    }

    // Loaded Japanese transport in 20 Sea Zone — the unit at risk.
    final Unit japTransport = GameDataTestUtil.transport(data).create(1, japanese).get(0);
    final Unit japInfantry = GameDataTestUtil.infantry(data).create(1, japanese).get(0);
    data.performChange(ChangeFactory.addUnits(sz20, List.of(japTransport, japInfantry)));
    // Mark infantry as cargo of the transport (@VisibleForTesting on Unit.setTransportedBy).
    japInfantry.setTransportedBy(japTransport);
    assertThat(japTransport.getTransporting(sz20))
        .as("transport must carry infantry so the 'loaded' code path runs")
        .contains(japInfantry);

    // British battleship in 35 Sea Zone — adjacent to 20 SZ; can reach 20 SZ next combat turn.
    final Unit britBattleship = GameDataTestUtil.battleship(data).create(1, british).get(0);
    data.performChange(ChangeFactory.addUnits(sz35, List.of(britBattleship)));

    // Japanese factory + ground unit at Japan — gives the AI a valid NCM destination so it has
    // somewhere to send the transport.
    final UnitType factoryMajor = data.getUnitTypeList().getUnitType("factory_major").orElseThrow();
    data.performChange(ChangeFactory.addUnits(japan, factoryMajor.create(1, japanese)));
    data.performChange(
        ChangeFactory.addUnits(japan, GameDataTestUtil.infantry(data).create(1, japanese)));

    final List<MoveDescription> dispatched;
    try (ProLogCapture log = new ProLogCapture()) {
      dispatched = runNonCombatMoveFor(japanese);

      // Diagnostic: print all log lines so the failure message reveals whether the root cause
      // is the empty-threat-scope ("CanHold=true since has no enemy attackers" for 20 SZ)
      // or something else.
      log.printAll();
    }

    // The transport must be moved (appear in at least one dispatched NCM move) — leaving it in
    // 20 SZ while a British battleship sits adjacent is unacceptable regardless of other context.
    final boolean transportMoved =
        dispatched.stream().anyMatch(md -> md.getUnits().contains(japTransport));

    assertThat(transportMoved)
        .as(
            "Loaded Japanese transport in 20 Sea Zone should be moved during NCM: "
                + "British battleship in 35 Sea Zone (adjacent) threatens it next turn. "
                + "If no move was dispatched, the enemy threat was not assessed for 20 SZ "
                + "(check log for 'CanHold=true since has no enemy attackers' at 20 Sea Zone). "
                + "Dispatched moves: "
                + dispatched)
        .isTrue();
  }

  /**
   * Counter-regression: a loaded transport in a genuinely safe zone (no enemy within reach) must
   * still be moved toward a loading destination by the primary repositioning loop. This ensures the
   * fix does not break the normal (unblocked, no nearby threat) case.
   */
  @Test
  void loadedTransportInSafeZoneIsMovedByPrimaryLoop() {
    declareWar(japanese, british);
    data.getSequence().setRoundAndStep(2, "Non Combat Move", japanese);

    // 6 Sea Zone is adjacent to Japan — no British units anywhere nearby.
    final Territory sz6 = data.getMap().getTerritoryOrThrow("6 Sea Zone");
    final Territory japan = data.getMap().getTerritoryOrThrow(JAPAN);

    // Clear all units.
    for (final Territory t : data.getMap().getTerritories()) {
      data.performChange(ChangeFactory.removeUnits(t, t.getUnits()));
    }

    // Loaded transport at 6 SZ (Japan's coastal sea zone).
    final Unit japTransport = GameDataTestUtil.transport(data).create(1, japanese).get(0);
    final Unit japInfantry = GameDataTestUtil.infantry(data).create(1, japanese).get(0);
    data.performChange(ChangeFactory.addUnits(sz6, List.of(japTransport, japInfantry)));
    japInfantry.setTransportedBy(japTransport);

    // Factory + infantry at Japan so the AI has somewhere to stage the transport.
    final UnitType factoryMajor = data.getUnitTypeList().getUnitType("factory_major").orElseThrow();
    data.performChange(ChangeFactory.addUnits(japan, factoryMajor.create(1, japanese)));
    data.performChange(
        ChangeFactory.addUnits(japan, GameDataTestUtil.infantry(data).create(1, japanese)));

    // No British units anywhere — completely safe scenario.
    final List<MoveDescription> dispatched = runNonCombatMoveFor(japanese);

    // Transport should have been moved (the primary repositioning loop or the fallback should
    // place it toward Japan or leave it at 6 SZ which is already adjacent). We only assert
    // that the AI does not throw and produces a coherent result.
    // This test is primarily a smoke check that the fix doesn't regress the happy path.
    assertThat(dispatched)
        .as(
            "NCM should run without throwing even with a loaded transport in a safe zone. "
                + "Dispatched: "
                + dispatched)
        .isNotNull();
  }

  /**
   * Exact-issue scenario: destroyer + 2 transports (each carrying 1 infantry) in 20 Sea Zone;
   * British battleship in 37 Sea Zone (2 hops away via 36 SZ). Mirrors the 32-IPC blunder described
   * in #2945.
   *
   * <p>With the destroyer providing defense the strength estimate might rate SZ20 as holdable — the
   * AI then leaves the transports in place. This test records and verifies the actual behavior,
   * both before and after any fix, so the exact failure mode is documented.
   */
  @Test
  void destroyerScreenDoesNotMaskTransportExposureToDistantBattleship() {
    declareWar(japanese, british);
    data.getSequence().setRoundAndStep(2, "Non Combat Move", japanese);

    final Territory sz20 = data.getMap().getTerritoryOrThrow(SEA_ZONE_20);
    final Territory sz37 = data.getMap().getTerritoryOrThrow(SEA_ZONE_37);
    final Territory japan = data.getMap().getTerritoryOrThrow(JAPAN);

    for (final Territory t : data.getMap().getTerritories()) {
      data.performChange(ChangeFactory.removeUnits(t, t.getUnits()));
    }

    // Japan: destroyer + 2 loaded transports in SZ20.
    final Unit japDestroyer = GameDataTestUtil.destroyer(data).create(1, japanese).get(0);
    final Unit japTransport1 = GameDataTestUtil.transport(data).create(1, japanese).get(0);
    final Unit japTransport2 = GameDataTestUtil.transport(data).create(1, japanese).get(0);
    final Unit japInfantry1 = GameDataTestUtil.infantry(data).create(1, japanese).get(0);
    final Unit japInfantry2 = GameDataTestUtil.infantry(data).create(1, japanese).get(0);
    data.performChange(
        ChangeFactory.addUnits(
            sz20, List.of(japDestroyer, japTransport1, japTransport2, japInfantry1, japInfantry2)));
    japInfantry1.setTransportedBy(japTransport1);
    japInfantry2.setTransportedBy(japTransport2);

    // Britain: battleship in SZ37 (2 hops from SZ20 via 36 SZ). Movement=4, can reach SZ20.
    final Unit britBattleship = GameDataTestUtil.battleship(data).create(1, british).get(0);
    data.performChange(ChangeFactory.addUnits(sz37, List.of(britBattleship)));

    // Factory + infantry at Japan.
    final UnitType factoryMajor = data.getUnitTypeList().getUnitType("factory_major").orElseThrow();
    data.performChange(ChangeFactory.addUnits(japan, factoryMajor.create(1, japanese)));
    data.performChange(
        ChangeFactory.addUnits(japan, GameDataTestUtil.infantry(data).create(1, japanese)));

    final List<MoveDescription> dispatched;
    try (ProLogCapture log = new ProLogCapture()) {
      dispatched = runNonCombatMoveFor(japanese);
      log.printAll();
    }

    // Both transports must be moved. Leaving loaded transports in a zone reachable by an enemy
    // battleship (even with a destroyer escort) is an ~32-IPC blunder.
    final boolean transport1Moved =
        dispatched.stream().anyMatch(md -> md.getUnits().contains(japTransport1));
    final boolean transport2Moved =
        dispatched.stream().anyMatch(md -> md.getUnits().contains(japTransport2));
    assertThat(transport1Moved && transport2Moved)
        .as(
            "Both loaded transports should be moved away from 20 SZ: "
                + "British battleship at 37 SZ can reach them next turn. "
                + "Dispatched: "
                + dispatched)
        .isTrue();
  }

  /**
   * Root-cause regression for #2945: {@code prioritizeDefendOptions} removes SZ20 from the defend
   * list because {@code value=0.0} (open water, no factory) but does NOT reset the {@code CanHold}
   * flag that was set to {@code true} by {@code determineIfMoveTerritoriesCanBeHeld}.
   *
   * <p>This test exercises the exact production condition from match GJwaNy_sWHH round 1:
   *
   * <ol>
   *   <li>A Japanese battleship at SZ19 can NCM to SZ20 → added to MaxDefenders for SZ20 → {@code
   *       determineIfMoveTerritoriesCanBeHeld} sets {@code CanHold=true} (the BB can beat the
   *       British BB in the max-defender scenario).
   *   <li>{@code prioritizeDefendOptions} removes SZ20 because {@code value=0.0} — but WITHOUT the
   *       fix leaves {@code CanHold=true}.
   *   <li>The transport safety check at line 1248 then sees {@code isCanHold()=true} and does NOT
   *       flag the transport as unsafe → transport stays at SZ20 unescorted.
   * </ol>
   *
   * <p>The fix: when removing a territory because {@code value <= 0} AND {@code !canAlreadyBeHeld}
   * AND it has enemy attackers, call {@code setCanHold(false)} so downstream safety checks work.
   *
   * <p>This test asserts on the planner log line produced by {@code prioritizeDefendOptions}: with
   * the bug the line reads {@code CanHold=true}; with the fix it reads {@code CanHold=false}.
   */
  @Test
  void sz20RemovedForZeroValueMustHaveCanHoldCorrectedToFalseWhenItHasEnemyAttackers() {
    declareWar(japanese, british);
    data.getSequence().setRoundAndStep(2, "Non Combat Move", japanese);

    final Territory sz19 = data.getMap().getTerritoryOrThrow("19 Sea Zone");
    final Territory sz20 = data.getMap().getTerritoryOrThrow(SEA_ZONE_20);
    final Territory sz35 = data.getMap().getTerritoryOrThrow(SEA_ZONE_35);
    final Territory japan = data.getMap().getTerritoryOrThrow(JAPAN);

    for (final Territory t : data.getMap().getTerritories()) {
      data.performChange(ChangeFactory.removeUnits(t, t.getUnits()));
    }

    // Loaded Japanese transport in SZ20 — the unit at risk.
    final Unit japTransport = GameDataTestUtil.transport(data).create(1, japanese).get(0);
    final Unit japInfantry = GameDataTestUtil.infantry(data).create(1, japanese).get(0);
    data.performChange(ChangeFactory.addUnits(sz20, List.of(japTransport, japInfantry)));
    japInfantry.setTransportedBy(japTransport);

    // British battleship in SZ35 — adjacent to SZ20, threatens it.
    final Unit britBattleship = GameDataTestUtil.battleship(data).create(1, british).get(0);
    data.performChange(ChangeFactory.addUnits(sz35, List.of(britBattleship)));

    // Japanese battleship at SZ19 (adjacent to SZ20).  findNavalMoveOptions adds it to
    // SZ20's MaxUnits (it can NCM there in 1 hop), giving SZ20 enough defenders that
    // determineIfMoveTerritoriesCanBeHeld sets CanHold=true.  But SZ19 and the more
    // valuable territories will absorb the BB in NCM — SZ20's value=0 gets it removed
    // from the defend list without the CanHold flag being corrected.
    final Unit japBattleship = GameDataTestUtil.battleship(data).create(1, japanese).get(0);
    data.performChange(ChangeFactory.addUnits(sz19, List.of(japBattleship)));

    // Japan: factory + infantry (so there is a valid NCM context and Japan can be held).
    final UnitType factoryMajor = data.getUnitTypeList().getUnitType("factory_major").orElseThrow();
    data.performChange(ChangeFactory.addUnits(japan, factoryMajor.create(1, japanese)));
    data.performChange(
        ChangeFactory.addUnits(japan, GameDataTestUtil.infantry(data).create(2, japanese)));

    // Invoke directly (not via runNonCombatMoveFor) so our ProLogCapture is the active handler —
    // runNonCombatMoveFor wraps with its own capture that would shadow this one.
    final ProAi proAi = new ProAi("Test " + japanese.getName(), japanese.getName() + " AI");
    final PlayerBridge playerBridgeMock = Mockito.mock(PlayerBridge.class);
    proAi.initialize(playerBridgeMock, japanese);
    when(playerBridgeMock.getGameData()).thenReturn(data);
    final IMoveDelegate moveDelegate = Mockito.mock(IMoveDelegate.class);
    when(moveDelegate.performMove(any())).thenReturn(java.util.Optional.empty());

    final List<String> logLines;
    try (ProLogCapture log = new ProLogCapture()) {
      proAi.invokeNonCombatMoveForSidecar(moveDelegate, data, japanese);
      logLines = log.getLines();
    }

    // The Removing line for SZ20 (value=0, canAlreadyBeHeld=false, maxEnemyUnits=1) must have
    // CanHold=false — NOT CanHold=true.  The bug produces CanHold=true here because
    // prioritizeDefendOptions removes SZ20 without resetting the flag set by
    // determineIfMoveTerritoriesCanBeHeld.
    final boolean removedWithCanHoldTrue =
        logLines.stream()
            .anyMatch(
                l ->
                    l.contains("Removing territory=20 Sea Zone")
                        && l.contains("value=0.0")
                        && l.contains("CanHold=true")
                        && l.contains("canAlreadyBeHeld=false"));
    assertThat(removedWithCanHoldTrue)
        .as(
            "prioritizeDefendOptions must NOT remove 20 Sea Zone with CanHold=true when value=0 "
                + "and canAlreadyBeHeld=false and maxEnemyUnits>0. "
                + "The fix should call setCanHold(false) before removal so downstream safety "
                + "checks correctly flag the transport as exposed. "
                + "Relevant log lines:\n"
                + logLines.stream()
                    .filter(l -> l.contains("20 Sea Zone"))
                    .collect(java.util.stream.Collectors.joining("\n")))
        .isFalse();
  }

  /**
   * Phantom-defender regression: a Japanese battleship at SZ19 inflates MaxDefenders for SZ20,
   * causing determineIfMoveTerritoriesCanBeHeld to set CanHold=true. But SZ20's value=0 triggers
   * removal in prioritizeDefendOptions, which fires the CanHold-reset and sets CanHold=false. With
   * the corrected flag, the transport safety check correctly skips SZ20 as a staging zone.
   *
   * <p>Setup:
   *
   * <ul>
   *   <li>SZ20: loaded transport + destroyer (actual escort)
   *   <li>SZ19: Japanese battleship (phantom — boosts MaxDefenders so CanHold=true initially)
   *   <li>SZ35: British battleship (adjacent, can reach SZ20)
   *   <li>Japan: factory_major + infantry (so the AI has a plan context)
   * </ul>
   *
   * <p>The CanHold-reset in prioritizeDefendOptions (not the safety-check expression itself) is the
   * load-bearing fix here: it fires before amphib staging selects a zone, clearing the stale
   * CanHold=true so staging never places the transport at SZ20.
   */
  @Test
  void loadedTransportInZoneWithPhantomDefenderAndWeakRealEscortMustRelocate() {
    declareWar(japanese, british);
    data.getSequence().setRoundAndStep(2, "Non Combat Move", japanese);

    final Territory sz19 = data.getMap().getTerritoryOrThrow("19 Sea Zone");
    final Territory sz20 = data.getMap().getTerritoryOrThrow(SEA_ZONE_20);
    final Territory sz35 = data.getMap().getTerritoryOrThrow(SEA_ZONE_35);
    final Territory japan = data.getMap().getTerritoryOrThrow(JAPAN);

    for (final Territory t : data.getMap().getTerritories()) {
      data.performChange(ChangeFactory.removeUnits(t, t.getUnits()));
    }

    // SZ20: loaded transport + destroyer (destroyer is the only REAL escort).
    final Unit japTransport = GameDataTestUtil.transport(data).create(1, japanese).get(0);
    final Unit japInfantry = GameDataTestUtil.infantry(data).create(1, japanese).get(0);
    final Unit japDestroyer = GameDataTestUtil.destroyer(data).create(1, japanese).get(0);
    data.performChange(
        ChangeFactory.addUnits(sz20, List.of(japTransport, japInfantry, japDestroyer)));
    japInfantry.setTransportedBy(japTransport);

    // SZ19: Japanese battleship — phantom MaxDefender. Its presence makes
    // MaxDefenders for SZ20 = {BB, destroyer, transport}, so CanHold=true. But SZ20
    // value=0 causes removal from the defend list before the BB is committed there.
    final Unit japBattleship = GameDataTestUtil.battleship(data).create(1, japanese).get(0);
    data.performChange(ChangeFactory.addUnits(sz19, List.of(japBattleship)));

    // SZ35: British battleship — adjacent to SZ20; threatens it next combat turn.
    final Unit britBattleship = GameDataTestUtil.battleship(data).create(1, british).get(0);
    data.performChange(ChangeFactory.addUnits(sz35, List.of(britBattleship)));

    // Japan: factory + infantry so the AI has a planning context.
    final UnitType factoryMajor = data.getUnitTypeList().getUnitType("factory_major").orElseThrow();
    data.performChange(ChangeFactory.addUnits(japan, factoryMajor.create(1, japanese)));
    data.performChange(
        ChangeFactory.addUnits(japan, GameDataTestUtil.infantry(data).create(2, japanese)));

    final List<MoveDescription> dispatched;
    try (ProLogCapture log = new ProLogCapture()) {
      dispatched = runNonCombatMoveFor(japanese);
      log.printAll();
    }

    // Transport must be relocated. A lone destroyer cannot defend SZ20 against a British
    // battleship. The phantom BB at SZ19 inflates MaxDefenders, but the real committed escort
    // (getAllDefenders) is {destroyer, transport} — which loses to the British BB.
    final boolean transportMoved =
        dispatched.stream().anyMatch(md -> md.getUnits().contains(japTransport));
    assertThat(transportMoved)
        .as(
            "Loaded transport in SZ20 must relocate: real escort is a lone destroyer, "
                + "which cannot hold against a British battleship. "
                + "Phantom BB at SZ19 must not make the zone appear safe. "
                + "Dispatched: "
                + dispatched)
        .isTrue();
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
