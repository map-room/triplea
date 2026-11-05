package games.strategy.triplea.ai.pro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Random;
import org.junit.jupiter.api.Test;

class ProDataSeedTest {

  /**
   * ProPoliticsAi takes {@code getRng()} in its constructor, before the sidecar calls {@code
   * setSeed}. If setSeed swapped in a new Random, the politics roll (and so the whole simulated
   * turn, and the purchase plan) would draw from an unseeded one.
   */
  @Test
  void setSeedReseedsTheSameRandomInstance() {
    final ProData proData = new ProData();
    final Random heldByCollaborator = proData.getRng();

    proData.setSeed(4609L);

    assertSame(heldByCollaborator, proData.getRng());
    final Random expected = new Random(4609L);
    for (int i = 0; i < 5; i++) {
      assertEquals(expected.nextLong(), heldByCollaborator.nextLong());
    }
  }
}
