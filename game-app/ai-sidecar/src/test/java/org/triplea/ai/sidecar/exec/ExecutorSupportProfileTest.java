package org.triplea.ai.sidecar.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.settings.ClientSetting;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;

class ExecutorSupportProfileTest {
  private static CanonicalGameData canonical;

  @BeforeAll
  static void init() throws Exception {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load("ww2global40_2nd_edition.xml");
  }

  @Test
  void omittedAndHardBuildStockProAi() {
    assertThat(ExecutorSupport.newProAi("n", "Germans", null).getProData().isBeta()).isFalse();
    assertThat(ExecutorSupport.newProAi("n", "Germans", "hard").getProData().isBeta()).isFalse();
  }

  @Test
  void hardBetaBuildsBetaProAi() {
    assertThat(ExecutorSupport.newProAi("n", "Germans", "hard-beta").getProData().isBeta())
        .isTrue();
  }

  @Test
  void unknownProfileThrowsInsteadOfFallingBack() {
    for (final String bad : new String[] {"beta", "", "true", "Hard Beta"}) {
      assertThatThrownBy(() -> ExecutorSupport.newProAi("n", "Germans", bad))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Unknown profile");
    }
  }

  @Test
  void strafingTerritoryNamesFollowsWhatDoCombatMoveRecorded() {
    final GameData data = canonical.cloneForSession();
    final ProAi proAi = ExecutorSupport.newProAi("n", "Germans", null);
    assertThat(PurchaseExecutor.strafingTerritoryNames(proAi)).isEmpty();

    final Territory karelia = data.getMap().getTerritoryOrNull("Karelia");
    assertThat(karelia).isNotNull();
    proAi.setStoredStrafingTerritories(List.of(karelia));
    assertThat(PurchaseExecutor.strafingTerritoryNames(proAi)).containsExactly("Karelia");

    proAi.setStoredStrafingTerritories(List.of());
    assertThat(PurchaseExecutor.strafingTerritoryNames(proAi)).isEmpty();
  }
}
