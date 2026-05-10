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
 * <p><b>Why super is NOT called for purchase:</b> {@link PurchaseDelegate#purchase(IntegerMap)}
 * mutates {@code GameData} by deducting PUs and adding purchased units to {@code
 * player.getUnitCollection()} via {@code bridge.addChange()}. The sidecar plans purchase only — the
 * actual placement is done TS-side by Map Room — so capturing the map without mutating the player's
 * unit holding pool is the correct shape. Budget validation is handled by the {@link
 * PurchaseExecutor} {@code trimToFit} backstop.
 *
 * <p><b>Thread-safety:</b> not thread-safe. Each {@code PurchaseExecutor.execute} call creates a
 * fresh instance.
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
   */
  @Override
  public @Nullable String purchaseRepair(final Map<Unit, IntegerMap<RepairRule>> productionRules) {
    this.capturedRepair = Map.copyOf(productionRules);
    return super.purchaseRepair(productionRules);
  }
}
