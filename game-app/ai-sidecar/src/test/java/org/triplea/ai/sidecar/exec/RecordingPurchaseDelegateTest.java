package org.triplea.ai.sidecar.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.RepairRule;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.settings.ClientSetting;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.java.collections.IntegerMap;

class RecordingPurchaseDelegateTest {

  private static CanonicalGameData canonical;

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load();
  }

  @Test
  void capturesPurchaseMap() {
    final GameData data = canonical.cloneForSession();
    final RecordingPurchaseDelegate d = new RecordingPurchaseDelegate();
    final IntegerMap<ProductionRule> input = new IntegerMap<>();
    final ProductionRule anyRule =
        data.getProductionRuleList().getProductionRules().iterator().next();
    input.add(anyRule, 4);
    assertNull(d.purchase(input));
    assertEquals(4, d.capturedPurchase().getInt(anyRule));
  }

  @Test
  void capturesRepairMap() {
    final RecordingPurchaseDelegate d = new RecordingPurchaseDelegate();
    final Map<Unit, IntegerMap<RepairRule>> input = Map.of();
    assertNull(d.purchaseRepair(input));
    assertNotNull(d.capturedRepair());
  }

  @Test
  void noOpMethodsDoNotThrow() {
    final RecordingPurchaseDelegate d = new RecordingPurchaseDelegate();
    d.start();
    d.end();
    d.setHasPostedTurnSummary(true);
    assertFalse(d.delegateCurrentlyRequiresUserInput());
    assertNotNull(d.saveState());
  }
}
