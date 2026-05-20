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
import games.strategy.triplea.ai.pro.util.ProUtils;
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
 * Regression tests for #2632: {@code findEnemyAttackOptions} used {@code
 * getEnemyPlayersInTurnOrder} (all non-allied) for threat assessment.
 *
 * <p>Root cause: in G40 round 1 the US is not yet politically allied with Britain, so British air
 * units in UK — which have range to reach North Atlantic staging sea zones — counted as ~21 TUV of
 * threat at the American amphib staging zone. {@code removeTerritoriesWhereTransportsAreExposed}
 * then cancelled the American amphib plan, causing the planner to enter a perpetual build-and-sit
 * loop despite the #2624 formula fix.
 *
 * <p>Fix: introduce {@link ProUtils#getOpposingTeamPlayersInTurnOrder}. A non-allied player is
 * excluded from the opposing team if they share a declared enemy with the current player — i.e.
 * they are on the same side. Example: Americans at war with Germany → British is also at war with
 * Germany → British shares Germany as a common enemy → British excluded from Americans' threat set.
 * Japan is not at war with Germany (they are Allied) → Japan remains in Americans' threat set.
 *
 * <p>Test anchor: G40 initial state, Americans ↔ Germany war declared for test setup.
 *
 * <ul>
 *   <li>British IS at war with Germans at G40 game start (initial relationship type = War).
 *   <li>Japanese is Allied with Germans at G40 game start — NOT at war.
 *   <li>Russians is Neutral with Germans at G40 game start.
 *   <li>Pirates (hidden AI player) is at war with every nation — excluded from the shared-enemy
 *       reference set via {@link ProUtils#isNeutralPlayer} so they do not poison team detection.
 * </ul>
 */
public class ProOpposingTeamThreatTest {

  private static final String WESTERN_GERMANY = "Western Germany";
  private static final String SEA_ZONE_112 = "112 Sea Zone";

  private GameData data;
  private GamePlayer americans;
  private GamePlayer british;
  private GamePlayer japanese;
  private GamePlayer russians;
  private GamePlayer germans;

  @BeforeEach
  void setUp() {
    ClientSetting.setPreferences(new MemoryPreferences());
    data = TestMapGameData.GLOBAL1940.getGameData();
    americans = data.getPlayerList().getPlayerId("Americans");
    british = data.getPlayerList().getPlayerId("British");
    japanese = data.getPlayerList().getPlayerId("Japanese");
    russians = data.getPlayerList().getPlayerId("Russians");
    germans = data.getPlayerList().getPlayerId("Germans");
  }

  /**
   * Acceptance criterion 1: after declaring war on Germany, British must be excluded from the
   * American opposing-team set.
   *
   * <p>British is already at war with Germany in G40 initial state. Once Americans declares war on
   * Germany, British shares Germany as a common enemy → British is on the same side → excluded.
   */
  @Test
  void britishExcludedFromAmericanOpposingTeamWhenAtWarWithSharedEnemy() {
    declareWar(americans, germans);

    final List<GamePlayer> opposingTeam = ProUtils.getOpposingTeamPlayersInTurnOrder(americans);

    assertThat(opposingTeam)
        .as(
            "British (at war with Germany, Americans' declared enemy) must be excluded from"
                + " Americans' opposing team")
        .doesNotContain(british);
  }

  /**
   * Acceptance criterion 2: Japan must remain in Americans' opposing-team set.
   *
   * <p>Japan is Allied with Germany (not at war). Therefore Japan does NOT share Germany as a
   * declared enemy with Americans → Japan stays on the opposing team.
   */
  @Test
  void japanRemainsInAmericanOpposingTeamBecauseItIsAlliedWithGermany() {
    declareWar(americans, germans);

    final List<GamePlayer> opposingTeam = ProUtils.getOpposingTeamPlayersInTurnOrder(americans);

    assertThat(opposingTeam)
        .as(
            "Japanese (Allied with Germany — not at war with Germany) must remain in Americans'"
                + " opposing team")
        .contains(japanese);
  }

  /**
   * Regression (acceptance criterion 4): continent-power Germany must still see Russia as a threat.
   *
   * <p>Germans is at war with British, ANZAC, French, Dutch at G40 start. Russians is Neutral with
   * all of those players. Therefore Russia shares no declared enemy with Germany → Russia remains
   * in Germany's opposing team.
   */
  @Test
  void russiaRemainsInGermanOpposingTeamBecauseRussiaSharesNoDeclaredEnemyWithGermany() {
    // Germans has declared enemies (British, ANZAC, French, Dutch) at G40 start.
    // Russia is neutral with all of those — no shared declared enemy.
    final List<GamePlayer> opposingTeam = ProUtils.getOpposingTeamPlayersInTurnOrder(germans);

    assertThat(opposingTeam)
        .as(
            "Russians (neutral with Germany's enemies British/ANZAC/French) must remain in"
                + " Germany's opposing team")
        .contains(russians);
  }

  /**
   * Integration test: with British units in UK, American amphib plans against Western Germany must
   * still succeed (British excluded from threat set → staging zone exposure check passes).
   *
   * <p>Before fix: British fighters from UK (range 4, can reach 112 Sea Zone) counted as ~21 TUV
   * threat → {@code removeTerritoriesWhereTransportsAreExposed} cancelled the amphib plan.
   *
   * <p>After fix: British excluded from Americans' opposing team → enemyTUVSwing ≈ 0 at staging
   * zone → plan passes the exposure check and emits at least one amphib unload move.
   */
  @Test
  void amphibPlanSucceedsWithBritishUnitsPresent() {
    declareWar(americans, germans);

    final Territory westernGermany = data.getMap().getTerritoryOrThrow(WESTERN_GERMANY);
    final Territory stagingSeaZone = data.getMap().getTerritoryOrThrow(SEA_ZONE_112);

    // Clear Western Germany defenders so the attack is trivially winnable.
    data.performChange(ChangeFactory.removeUnits(westernGermany, westernGermany.getUnits()));

    // Clear all naval units from sea zones (avoid German navy triggering exposure check).
    for (final Territory t : data.getMap().getTerritories()) {
      if (t.isWater()) {
        data.performChange(ChangeFactory.removeUnits(t, t.getUnits()));
      }
    }

    // Clear all non-American, non-British land units.
    // British land forces (fighters in UK, Scotland) are intentionally KEPT — they are the
    // "noise" this test validates is correctly excluded from the threat assessment.
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater()) {
        final List<Unit> toRemove =
            t.getMatches(
                Matches.unitIsOwnedBy(americans)
                    .negate()
                    .and(Matches.unitIsOwnedBy(british).negate()));
        if (!toRemove.isEmpty()) {
          data.performChange(ChangeFactory.removeUnits(t, toRemove));
        }
      }
    }

    // Place a pre-loaded American transport + destroyer in the staging sea zone.
    final Unit transport = GameDataTestUtil.transport(data).create(1, americans).get(0);
    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    final Unit destroyer = GameDataTestUtil.destroyer(data).create(1, americans).get(0);
    data.performChange(
        ChangeFactory.addUnits(stagingSeaZone, List.of(destroyer, transport, infantry)));
    infantry.setTransportedBy(transport);

    assertThat(transport.getTransporting(stagingSeaZone))
        .as("pre-condition: transport must carry the infantry")
        .containsExactly(infantry);

    final ProAi proAi = new ProAi("Test Americans", "Americans AI");
    final PlayerBridge playerBridgeMock = Mockito.mock(PlayerBridge.class);
    proAi.initialize(playerBridgeMock, americans);
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
      proAi.invokeCombatMoveForSidecar(moveDelegate, data, americans);
    }

    final long amphibUnloads =
        dispatched.stream()
            .filter(m -> m.getRoute().getStart().isWater() && !m.getRoute().getEnd().isWater())
            .count();
    assertThat(amphibUnloads)
        .as(
            "ProAi must dispatch ≥1 amphib unload for Americans with British units in UK —"
                + " British must be excluded from threat set (shares Germany as declared enemy)."
                + " All dispatched: "
                + dispatched)
        .isGreaterThan(0);
  }

  private void declareWar(final GamePlayer p1, final GamePlayer p2) {
    data.performChange(
        ChangeFactory.relationshipChange(
            p1,
            p2,
            data.getRelationshipTracker().getRelationshipType(p1, p2),
            data.getRelationshipTypeList().getRelationshipType("War")));
  }
}
