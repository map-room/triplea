package games.strategy.triplea.ai.pro;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.triplea.ai.pro.util.ProUtils;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.xml.TestMapGameData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

/**
 * map-room#2745: round-1 Americans have no declared war, so opposing-team detection used to fall
 * back to every non-ally. British and ANZAC ships then counted as attackers on 101 Sea Zone and the
 * Atlantic transport ran to 64 Sea Zone, beside the South American True Neutrals.
 *
 * <p>The seeded move itself is {@code ProNeutralStaging2745ReproTest} on ww2global40_2nd_edition.
 * This pins the player set that feeds that threat list.
 */
public class ProNeutralStagingRepro2745Test {

  private GameData data;
  private GamePlayer americans;
  private GamePlayer british;
  private GamePlayer anzac;
  private GamePlayer french;
  private GamePlayer germans;
  private GamePlayer japanese;
  private GamePlayer italians;
  private GamePlayer russians;

  @BeforeEach
  void setUp() {
    ClientSetting.setPreferences(new MemoryPreferences());
    data = TestMapGameData.GLOBAL1940.getGameData();
    americans = data.getPlayerList().getPlayerId("Americans");
    british = data.getPlayerList().getPlayerId("British");
    anzac = data.getPlayerList().getPlayerId("ANZAC");
    french = data.getPlayerList().getPlayerId("French");
    germans = data.getPlayerList().getPlayerId("Germans");
    japanese = data.getPlayerList().getPlayerId("Japanese");
    italians = data.getPlayerList().getPlayerId("Italians");
    russians = data.getPlayerList().getPlayerId("Russians");
  }

  @Test
  void round1AmericansDoNotTreatAlliesAsOpposingTeam() {
    assertThat(americans.isAtWar(germans)).isFalse();
    assertThat(americans.isAtWar(british)).isFalse();

    final var opposing = ProUtils.getOpposingTeamPlayersInTurnOrder(americans);

    assertThat(opposing)
        .as("same-alliance future partners are not a fleet the neutral US should flee")
        .doesNotContain(british, anzac, french, russians);
    assertThat(opposing)
        .as("the Axis is still the opposing team when America has not declared war")
        .contains(germans, japanese, italians);
  }

  @Test
  void germanyStillTreatsRussiaAsOpposingTeam() {
    final var opposing = ProUtils.getOpposingTeamPlayersInTurnOrder(germans);

    assertThat(opposing)
        .as("Russia shares neither Germany's alliance nor Germany's declared enemies")
        .contains(russians);
    assertThat(opposing).doesNotContain(japanese, italians);
  }
}
