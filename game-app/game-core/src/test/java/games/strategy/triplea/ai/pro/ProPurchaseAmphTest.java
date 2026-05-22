package games.strategy.triplea.ai.pro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.player.PlayerBridge;
import games.strategy.triplea.ai.pro.data.AmphContext;
import games.strategy.triplea.ai.pro.data.ProPurchaseOption;
import games.strategy.triplea.ai.pro.data.ProPurchaseOptionMap;
import games.strategy.triplea.ai.pro.logging.ProLogCapture;
import games.strategy.triplea.delegate.remote.IPurchaseDelegate;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.java.collections.IntegerMap;

/**
 * Tests for amphib-no-transports purchase AI gating (Issue #2714).
 *
 * <p>When {@link AmphContext#isEnabled()}, the AI must not plan to buy transports — doing so causes
 * a server soft-reject ("Transports are disabled") and wastes IPC on budget reconciliation.
 *
 * <p>Test map: G40 Pacific. Japanese player has transports in their production frontier.
 *
 * <ul>
 *   <li>Toggle ON ({@link AmphContext#isEnabled()}) → no transport in purchase plan.
 *   <li>Toggle OFF ({@link AmphContext#DISABLED}) → transport may appear in purchase plan (normal
 *       flow unchanged).
 *   <li>{@link ProPurchaseOptionMap#getEffectiveSeaTransportOptions} returns empty list when
 *       enabled, full list when disabled.
 * </ul>
 */
public class ProPurchaseAmphTest {

  private GameData data;
  private GamePlayer japanese;

  @BeforeEach
  void setUp() {
    ClientSetting.setPreferences(new MemoryPreferences());
    data = TestMapGameData.GLOBAL1940.getGameData();
    japanese = data.getPlayerList().getPlayerId("Japanese");

    // Remove all units for a clean slate so the AI isn't distracted by existing defenses.
    for (final Territory t : data.getMap().getTerritories()) {
      data.performChange(ChangeFactory.removeUnits(t, t.getUnits()));
    }
  }

  // --- ProPurchaseOptionMap.getEffectiveSeaTransportOptions ---

  @Test
  void getEffectiveSeaTransportOptionsReturnsEmptyWhenEnabled() {
    final ProPurchaseOptionMap options = new ProPurchaseOptionMap(japanese, data);
    // Japanese have transports in their frontier — the raw list must be non-empty.
    assertThat(options.getSeaTransportOptions())
        .as("Japanese should have transport purchase options on a stock G40 map")
        .isNotEmpty();

    final List<ProPurchaseOption> effective =
        options.getEffectiveSeaTransportOptions(new AmphContext(true, Set.of(), Set.of()));
    assertThat(effective)
        .as("getEffectiveSeaTransportOptions must return empty list when AmphContext.enabled=true")
        .isEmpty();
  }

  @Test
  void getEffectiveSeaTransportOptionsPassesThroughWhenDisabled() {
    final ProPurchaseOptionMap options = new ProPurchaseOptionMap(japanese, data);
    final List<ProPurchaseOption> effective =
        options.getEffectiveSeaTransportOptions(AmphContext.DISABLED);
    assertThat(effective)
        .as("getEffectiveSeaTransportOptions must equal seaTransportOptions when disabled")
        .isEqualTo(options.getSeaTransportOptions());
  }

  // --- full purchase run: no transport in plan when amphib enabled ---

  @Test
  void purchasePlanContainsNoTransportsWhenAmphEnabled() {
    final List<ProductionRule> purchasedRules =
        runPurchase(japanese, new AmphContext(true, Set.of(), Set.of()));

    final boolean boughtTransport =
        purchasedRules.stream()
            .anyMatch(
                rule ->
                    rule.getResults().keySet().stream()
                        .filter(na -> na instanceof games.strategy.engine.data.UnitType)
                        .map(na -> (games.strategy.engine.data.UnitType) na)
                        .anyMatch(
                            ut -> {
                              final var ua = ut.getUnitAttachment();
                              return ua.getTransportCapacity() != -1 && ua.isSea();
                            }));

    assertThat(boughtTransport)
        .as(
            "With AmphContext enabled, the purchase plan must contain no transport unit type. "
                + "Bought rules: "
                + purchasedRules)
        .isFalse();
  }

  @Test
  void purchasePlanToggleOffUnchanged() {
    // Toggle off must not crash and must produce a valid (non-empty) purchase plan.
    final List<ProductionRule> purchasedRules = runPurchase(japanese, AmphContext.DISABLED);
    // We don't assert on whether a transport is bought (map state and IPC may vary),
    // just verify the method completes without errors.
    assertThat(purchasedRules)
        .as("Toggle-off purchase should produce at least one rule")
        .isNotEmpty();
  }

  // --- helpers ---

  /**
   * Runs {@link ProAi#invokePurchaseForSidecar} and collects all production rules that were queued
   * via the purchase delegate.
   */
  private List<ProductionRule> runPurchase(final GamePlayer player, final AmphContext ctx) {
    final ProAi proAi = new ProAi("Test " + player.getName(), player.getName() + " AI");
    final PlayerBridge bridge = mock(PlayerBridge.class);
    proAi.initialize(bridge, player);
    when(bridge.getGameData()).thenReturn(data);
    proAi.reinitializeProDataForSidecar();
    proAi.getProData().setAmphContext(ctx);

    final List<ProductionRule> purchased = new ArrayList<>();
    final IPurchaseDelegate purchaseDelegate = mock(IPurchaseDelegate.class);
    when(purchaseDelegate.purchase(org.mockito.ArgumentMatchers.any()))
        .then(
            inv -> {
              final IntegerMap<ProductionRule> rules = inv.getArgument(0);
              for (final ProductionRule rule : rules.keySet()) {
                for (int i = 0; i < rules.getInt(rule); i++) {
                  purchased.add(rule);
                }
              }
              return null;
            });

    // PUs for Japanese (standard G40 starting income).
    final int startingPUs = japanese.getResources().getQuantity("PUs");
    final int pusToSpend = startingPUs > 0 ? startingPUs : 30;

    try (ProLogCapture ignored = new ProLogCapture()) {
      proAi.invokePurchaseForSidecar(false, pusToSpend, purchaseDelegate, data, player);
    }
    return purchased;
  }
}
