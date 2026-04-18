package org.triplea.ai.sidecar.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.RepairRule;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.delegate.PurchaseDelegate;
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
  void isSubclassOfPurchaseDelegate() {
    // RecordingPurchaseDelegate must extend PurchaseDelegate so the class hierarchy is correct.
    assertInstanceOf(PurchaseDelegate.class, new RecordingPurchaseDelegate());
  }

  @Test
  void capturesPurchaseMap() {
    final GameData data = canonical.cloneForSession();
    final RecordingPurchaseDelegate d = new RecordingPurchaseDelegate();
    final IntegerMap<ProductionRule> input = new IntegerMap<>();
    final ProductionRule anyRule =
        data.getProductionRuleList().getProductionRules().iterator().next();
    input.add(anyRule, 4);
    // purchase() does NOT call super to avoid PU deduction / unit injection side-effects.
    // It always returns null (success) and captures the map.
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

  // NOTE: The former "noOpMethodsDoNotThrow" test called d.start() and d.end() without a bridge.
  // After the refactor RecordingPurchaseDelegate extends PurchaseDelegate → BaseTripleADelegate,
  // so start() and end() fire triggers via bridge.getData() — calling them without a live bridge
  // will NPE. start()/end() are NOT called by PurchaseExecutor (only purchase() and purchaseRepair()
  // are called by ProAi). The test is therefore removed; start/end coverage is provided by
  // PurchaseExecutorTest and Phase3PurchaseIntegrationTest.
}
