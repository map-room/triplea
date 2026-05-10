package org.triplea.ai.sidecar.exec;

import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.RepairRule;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.delegate.PurchaseDelegate;
import java.util.Map;
import javax.annotation.Nullable;
import org.triplea.java.collections.IntegerMap;

/**
 * {@link PurchaseDelegate} subclass that captures the purchase and repair maps passed to {@link
 * #purchase} and {@link #purchaseRepair}, without forwarding to {@code super}.
 *
 * <p><b>Why super is NOT called here:</b> {@link PurchaseDelegate#purchase(IntegerMap)} mutates
 * {@code GameData} by deducting PUs and adding purchased units to {@code
 * player.getUnitCollection()} via {@code bridge.addChange()}. The place phase later calls {@code
 * AbstractProAi.invokePlaceForSidecar()}, which explicitly injects units from {@code
 * storedPurchaseTerritories} into the player's holding pool before {@code purchaseAi.place()} runs.
 * If {@code super.purchase()} were called here, those units would be injected a second time (since
 * {@link org.triplea.ai.sidecar.wire.WireStateApplier#apply} does not clear the player's holding
 * pool). Fixing this would require coordinated changes to {@code
 * AbstractProAi.invokePlaceForSidecar} in {@code game-core}; that is tracked as a follow-up. For
 * now, budget validation is the responsibility of the {@link PurchaseExecutor} {@code trimToFit}
 * backstop.
 *
 * <p><b>Thread-safety:</b> not thread-safe. Each {@code PurchaseExecutor.execute} call creates a
 * fresh instance and the session-scoped executor serialises access on the Java side.
 */
public final class RecordingPurchaseDelegate extends PurchaseDelegate {

  private IntegerMap<ProductionRule> capturedPurchase = new IntegerMap<>();
  private Map<Unit, IntegerMap<RepairRule>> capturedRepair = Map.of();

  public IntegerMap<ProductionRule> capturedPurchase() {
    return capturedPurchase;
  }

  public Map<Unit, IntegerMap<RepairRule>> capturedRepair() {
    return capturedRepair;
  }

  /**
   * Captures the purchase map. Does NOT call {@code super.purchase()} to avoid mutating the
   * player's unit holding pool (see class Javadoc). Always returns {@code null} (success).
   */
  @Override
  public @Nullable String purchase(final IntegerMap<ProductionRule> productionRules) {
    this.capturedPurchase = new IntegerMap<>(productionRules);
    return null;
  }

  /**
   * Captures the repair map and forwards to {@code super.purchaseRepair()} to deduct repair IPC
   * from {@code player.getResources()} via {@link
   * games.strategy.triplea.ai.pro.simulate.ProDummyDelegateBridge#addChange}. This mirrors what the
   * real {@link games.strategy.triplea.delegate.PurchaseDelegate} does and is necessary so that the
   * subsequent {@code ProPurchaseAi.purchase()} call initialises its {@link
   * games.strategy.triplea.ai.pro.data.ProResourceTracker} with the correct post-repair budget.
   *
   * <p>Unlike {@link #purchase}, calling {@code super.purchaseRepair()} is safe here: repair does
   * NOT add new units to the player's holding pool (it only reduces unit damage and deducts PUs),
   * so no double-injection occurs in {@link
   * games.strategy.triplea.ai.pro.AbstractProAi#invokePlaceForSidecar}.
   */
  @Override
  public @Nullable String purchaseRepair(final Map<Unit, IntegerMap<RepairRule>> productionRules) {
    this.capturedRepair = Map.copyOf(productionRules);
    return super.purchaseRepair(productionRules);
  }
}
