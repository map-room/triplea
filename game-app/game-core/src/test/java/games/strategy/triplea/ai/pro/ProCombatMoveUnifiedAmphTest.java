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
 * Integration tests for the unified CM assault model (design §6.3) in {@link
 * ProCombatMoveAi#findAmphAssaultOptions}.
 *
 * <p>Validates the AA / attack-0 exclusion from CM assault candidates (design §5.2-2 / §7.5(d)): AA
 * guns are fully eligible for NCM embark and disembark, but must be excluded from CM assault
 * candidates because they cannot contribute to the land battle (attack=0).
 *
 * <p>Test map: G40. Americans vs Japanese at war. All units cleared in {@link #setUp}.
 */
public class ProCombatMoveUnifiedAmphTest {

  private static final String SEA_ZONE_6 = "6 Sea Zone";
  private static final String JAPAN = "Japan";

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

  // ─── §7.5(d) — AA gun excluded from CM assault candidates ────────────────────

  /**
   * Regression for design §5.2-2 / §7.5(d): an AA gun (attack=0) that is already embarked in 6 Sea
   * Zone (adjacent to Japan) must NOT be dispatched in a CM assault, even when {@code
   * isEmbarked=true} and {@code isEmbarkedThisTurn=false}.
   *
   * <p>Before the fix, {@code findAmphAssaultOptions} included ALL land units that satisfy {@code
   * isEmbarked && !isEmbarkedThisTurn}, which includes AA guns. An AA gun dispatched into a combat
   * assault is pointless (attack=0 contributes nothing) and strands the unit on an enemy coast.
   *
   * <p>After the fix, the CM pass excludes units with {@code attack == 0} (AA guns and equivalents)
   * from assault candidates. AA guns remain fully eligible for NCM embark/disembark (including onto
   * just-captured coasts per §5.2-2).
   *
   * <p><b>Failure mode before the fix:</b> AA gun dispatched to Japan as a CM assault move.
   * <b>After the fix:</b> no assault move dispatched for the AA gun; it remains in 6 SZ until NCM.
   */
  @Test
  void aaGunExcludedFromCombatAssaultCandidates_issue2712d() {
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);
    final Territory japan = data.getMap().getTerritoryOrThrow(JAPAN);

    // AA gun in 6 SZ — embarked from a prior turn, NOT embarkedThisTurn.
    // Japan is adjacent to 6 SZ and is hostile (Japanese-owned) → valid 1-hop assault target.
    final Unit aaGun = GameDataTestUtil.aaGun(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(sz6, List.of(aaGun)));

    // AmphContext: AA gun is embarked but NOT fresh — eligible per the old !embarkedThisTurn check.
    final AmphContext ctx =
        new AmphContext(true, Set.of(aaGun.getId()), Set.of(/* not embarkedThisTurn */ ));

    final List<MoveDescription> dispatched = runCombatMove(americans, ctx);

    // AA gun must not be dispatched to ANY hostile coast — Japan OR Korea (both adjacent to 6 SZ).
    final boolean aaAssaultToAnyHostileCoast =
        dispatched.stream()
            .anyMatch(m -> m.getUnits().contains(aaGun) && !m.getRoute().getEnd().isWater());

    assertThat(aaAssaultToAnyHostileCoast)
        .as(
            "AA gun (attack=0) in 6 Sea Zone must NOT be dispatched as a CM assault to any "
                + "hostile coast (Japan or Korea). "
                + "Before the fix: any embarked land unit was included in assault candidates. "
                + "After the fix: attack-0 units are excluded from CM assault candidates per §5.2-2. "
                + "Dispatched: "
                + dispatched)
        .isFalse();
  }

  // ─── regression guard: infantry assault still works after AA exclusion ────────

  /**
   * Regression guard: after excluding AA from CM assault candidates, a regular infantry (attack>0)
   * staged in 6 SZ must still be dispatched as a CM assault to Japan.
   *
   * <p>This test ensures the AA exclusion does not accidentally filter attack-capable units.
   */
  @Test
  void infantryAssaultStillWorksAfterAaExclusion() {
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);
    final Territory japan = data.getMap().getTerritoryOrThrow(JAPAN);

    // Infantry staged in 6 SZ (embarked, not fresh) alongside the AA gun scenario.
    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(sz6, List.of(infantry)));

    final AmphContext ctx =
        new AmphContext(true, Set.of(infantry.getId()), Set.of(/* not embarkedThisTurn */ ));

    final List<MoveDescription> dispatched = runCombatMove(americans, ctx);

    // Infantry should assault to Japan or any adjacent hostile coast.
    final boolean infantryAssaultsAnyCoast =
        dispatched.stream()
            .anyMatch(m -> m.getUnits().contains(infantry) && !m.getRoute().getEnd().isWater());

    assertThat(infantryAssaultsAnyCoast)
        .as(
            "Infantry (attack>0) in 6 Sea Zone must still be dispatched as a CM assault to a "
                + "hostile coast (Japan or Korea) after the AA exclusion is applied. "
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

  private List<MoveDescription> runCombatMove(final GamePlayer player, final AmphContext ctx) {
    final ProAi proAi = new ProAi("Test " + player.getName(), player.getName() + " AI");
    final PlayerBridge bridge = mock(PlayerBridge.class);
    proAi.initialize(bridge, player);
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
      proAi.invokeCombatMoveForSidecar(moveDelegate, data, player);
    }
    return dispatched;
  }
}
