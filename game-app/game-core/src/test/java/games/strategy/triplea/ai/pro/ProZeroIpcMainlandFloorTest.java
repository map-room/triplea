package games.strategy.triplea.ai.pro;

import static games.strategy.triplea.delegate.GameDataTestUtil.destroyer;
import static games.strategy.triplea.delegate.GameDataTestUtil.infantry;
import static games.strategy.triplea.delegate.GameDataTestUtil.transport;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.player.PlayerBridge;
import games.strategy.triplea.ai.pro.logging.ProLogCapture;
import games.strategy.triplea.ai.pro.util.ProTerritoryValueUtils;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

/**
 * The zero-IPC amphib floor is for real islands only (map-room#2736).
 *
 * <p>British Guiana is British, production 0, and coastal, but it is land-connected to Brazil,
 * Venezuela, and Suriname. {@code findLandValue} already requires {@code landMassSize == 1}. The
 * combat-move copies of that floor must use the same gate: an amphib into British Guiana was
 * scoring {@code AttackValue = TUVSwing + 2.0}.
 */
public class ProZeroIpcMainlandFloorTest {

  private static final Pattern ATTACK_LINE =
      Pattern.compile(
          "AttackValue=([-0-9.eE]+), TUVSwing=([-0-9.eE]+), isAmphib=(true|false), British Guiana");

  private static final Pattern TERRITORY_VALUE_LINE =
      Pattern.compile("territory=British Guiana,.*territoryValue=([-0-9.eE]+)");

  private GameData data;
  private GamePlayer germans;
  private GamePlayer british;

  @BeforeEach
  void setUp() {
    ClientSetting.setPreferences(new MemoryPreferences());
    data = TestMapGameData.GLOBAL1940.getGameData();
    germans = data.getPlayerList().getPlayerId("Germans");
    british = data.getPlayerList().getPlayerId("British");
  }

  @Test
  void britishGuianaAmphibGetsNoZeroIpcFloor() {
    data.performChange(
        ChangeFactory.relationshipChange(
            germans,
            british,
            data.getRelationshipTracker().getRelationshipType(germans, british),
            data.getRelationshipTypeList().getRelationshipType("War")));

    final Territory guiana = data.getMap().getTerritoryOrThrow("British Guiana");
    final Territory sz88 = data.getMap().getTerritoryOrThrow("88 Sea Zone");
    assertThat(TerritoryAttachment.getProduction(guiana)).isEqualTo(0);
    assertThat(data.getMap().getNeighbors(guiana)).contains(sz88);
    assertThat(ProTerritoryValueUtils.landMassSize(germans, guiana))
        .as("British Guiana is a mainland colony, not a one-territory island")
        .isGreaterThan(1);
    assertThat(ProTerritoryValueUtils.isIsland(germans, guiana)).isFalse();
    assertThat(
            ProTerritoryValueUtils.isIsland(
                germans, data.getMap().getTerritoryOrThrow("Marshall Islands")))
        .as("the island gate must still recognize a real Pacific island")
        .isTrue();

    // One amphib target, so the attack survives into determineUnitsToAttackWith and that
    // site's territoryValue is logged. Other land goes to Germany and every unit is
    // removed; otherwise a higher-value coast (Brazil) or an already-loaded Atlantic
    // transport takes the only infantry and Guiana is deleted before that log.
    for (final Territory t : data.getMap().getTerritories()) {
      data.performChange(ChangeFactory.removeUnits(t, new ArrayList<>(t.getUnits())));
      if (!t.isWater() && !t.equals(guiana)) {
        data.performChange(ChangeFactory.changeOwner(t, germans));
      }
    }

    final Unit trn = transport(data).create(1, germans).get(0);
    final Unit inf = infantry(data).create(1, germans).get(0);
    final Unit dest = destroyer(data).create(1, germans).get(0);
    data.performChange(ChangeFactory.addUnits(sz88, List.of(dest, trn, inf)));
    inf.setTransportedBy(trn);

    final ProAi proAi = new ProAi("Test Germans", "Germans AI");
    final PlayerBridge playerBridgeMock = Mockito.mock(PlayerBridge.class);
    proAi.initialize(playerBridgeMock, germans);
    when(playerBridgeMock.getGameData()).thenReturn(data);

    final IMoveDelegate moveDelegate = Mockito.mock(IMoveDelegate.class);
    when(moveDelegate.performMove(any())).thenReturn(Optional.empty());

    try (ProLogCapture log = new ProLogCapture()) {
      proAi.invokeCombatMoveForSidecar(moveDelegate, data, germans);
      final List<String> lines = log.getLines();

      final Matcher attack =
          lines.stream().map(ATTACK_LINE::matcher).filter(Matcher::find).findFirst().orElse(null);
      assertThat(attack)
          .as("prioritizeAttackOptions must score an amphib attack on British Guiana")
          .isNotNull();
      assertThat(attack.group(3)).isEqualTo("true");
      final double attackValue = Double.parseDouble(attack.group(1));
      final double tuvSwing = Double.parseDouble(attack.group(2));
      assertThat(attackValue - tuvSwing)
          .as(
              "production is 0, so AttackValue - TUVSwing is the additive floor. A mainland"
                  + " colony must stay at 0, not ZERO_IPC_ISLAND_FLOOR. line="
                  + attack.group())
          .isCloseTo(0.0, within(0.05));

      final Matcher territoryValue =
          lines.stream()
              .map(TERRITORY_VALUE_LINE::matcher)
              .filter(Matcher::find)
              .findFirst()
              .orElse(null);
      assertThat(territoryValue)
          .as("determineUnitsToAttackWith must log a territoryValue for British Guiana")
          .isNotNull();
      assertThat(Double.parseDouble(territoryValue.group(1)))
          .as(
              "the attack-result floor must not apply to British Guiana. line="
                  + territoryValue.group())
          .isCloseTo(0.0, within(0.001));
    }
  }
}
