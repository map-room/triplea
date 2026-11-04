package org.triplea.ai.sidecar.wire;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.triplea.ai.pro.util.ProUtils;
import games.strategy.triplea.ai.pro.util.UkEconomySplit;
import games.strategy.triplea.settings.ClientSetting;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;

/**
 * map-room#4511 control. Anniversary (WW2v3-1941) has one UK economy and no {@code UK_Pacific}. The
 * split must stay out of it even if a wire state carries both pools.
 */
class UkEconomySplitAnniversaryTest {

  private static CanonicalGameData canonical;

  @BeforeAll
  static void init() {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load("WW2v3-1941.xml");
  }

  @Test
  void anniversaryHasNoUkPacificPlayer() {
    assertThat(canonical.cloneForSession().getPlayerList().getPlayerId("UK_Pacific")).isNull();
  }

  @Test
  void poolsOnTheWireDoNotCreateASplitOnAnniversary() {
    final GameData gd = canonical.cloneForSession();
    final WireState wire =
        new WireState(
            List.of(
                new WireTerritory("United Kingdom", "British", List.of(), null, "europe", null)),
            List.of(new WirePlayer("British", 30, List.of(), false, 20, 10)),
            1,
            "purchase",
            "British",
            List.of(),
            "ww2v3_1941");
    WireStateApplier.apply(gd, wire, new ConcurrentHashMap<>());

    assertThat(UkEconomySplit.of(gd)).isEmpty();
    // London is the one UK capital: its whole income and the whole treasury.
    final var london = gd.getMap().getTerritoryOrThrow("United Kingdom");
    assertThat(ProUtils.captureStopsIncome(london, gd.getMap())).isTrue();
    assertThat(ProUtils.capturedTreasury(london, gd)).isEqualTo(30);
  }
}
