package org.triplea.ai.sidecar.exec;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.triplea.settings.ClientSetting;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.dto.PurchasePlan;
import org.triplea.ai.sidecar.dto.PurchaseRequest;
import org.triplea.ai.sidecar.wire.WireState;

/**
 * map-room#4509: Global 1940 Japan banked its 26 IPCs on round 1 about half the time, because the
 * purchase simulation sailed the home fleet out of 6 Sea Zone and the "can't defend" verdict on the
 * emptied zone vetoed every other buy.
 *
 * <p>The outcome does not follow the seed. It follows per-load state (unit ids, so hash order), so
 * a single fixed-seed run proves nothing: each iteration reloads the canonical data.
 */
class PurchaseEmptiedZone4509Test {
  private static final String MAP = "ww2global40_2nd_edition";
  private static final int LOADS = 30;

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
  }

  @Test
  void japanRound1NeverEndsWithZeroSpend() throws Exception {
    final Map<String, Integer> tally = new TreeMap<>();
    int empty = 0;
    for (int i = 0; i < LOADS; i++) {
      final GameData data = CanonicalGameData.load(MAP + ".xml").cloneForSession();
      final PurchasePlan plan =
          new PurchaseExecutor()
              .executeOn(
                  data,
                  new PurchaseRequest(
                      new WireState(
                          List.of(), List.of(), 1, "purchase", "Japanese", List.of(), MAP),
                      42L));
      tally.merge(plan.buys().toString(), 1, Integer::sum);
      if (plan.buys().isEmpty()) {
        empty++;
      }
    }
    assertThat(empty).as("empty Japan R1 purchases in %d loads: %s", LOADS, tally).isZero();
  }
}
