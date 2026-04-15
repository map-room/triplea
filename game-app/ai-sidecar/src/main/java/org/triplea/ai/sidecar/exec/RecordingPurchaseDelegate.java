package org.triplea.ai.sidecar.exec;

import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.RepairRule;
import games.strategy.engine.data.Unit;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.message.IRemote;
import games.strategy.engine.posted.game.pbem.PbemMessagePoster;
import games.strategy.net.websocket.ClientNetworkBridge;
import games.strategy.triplea.delegate.remote.IPurchaseDelegate;
import java.io.Serializable;
import java.util.Map;
import org.triplea.java.collections.IntegerMap;

/**
 * {@link IPurchaseDelegate} stub that captures the {@code IntegerMap<ProductionRule>} handed to
 * {@link #purchase} and the repair map handed to {@link #purchaseRepair}, while no-opping every
 * other method on the interface. Used by {@code PurchaseExecutor} to intercept the side-effect
 * output of {@code games.strategy.triplea.ai.pro.AbstractProAi#purchase} without mutating the
 * session's {@link games.strategy.engine.data.GameData}.
 *
 * <p><b>Thread-safety:</b> not thread-safe. Each {@code PurchaseExecutor.execute} call creates a
 * fresh instance and the session-scoped executor serialises access on the Java side.
 */
public final class RecordingPurchaseDelegate implements IPurchaseDelegate {

  private IntegerMap<ProductionRule> capturedPurchase = new IntegerMap<>();
  private Map<Unit, IntegerMap<RepairRule>> capturedRepair = Map.of();
  private IDelegateBridge bridge;
  private boolean hasPostedTurnSummary;
  private String name = "recordingPurchase";
  private String displayName = "Recording Purchase";

  public IntegerMap<ProductionRule> capturedPurchase() {
    return capturedPurchase;
  }

  public Map<Unit, IntegerMap<RepairRule>> capturedRepair() {
    return capturedRepair;
  }

  @Override
  public String purchase(final IntegerMap<ProductionRule> productionRules) {
    this.capturedPurchase = new IntegerMap<>(productionRules);
    return null;
  }

  @Override
  public String purchaseRepair(final Map<Unit, IntegerMap<RepairRule>> productionRules) {
    this.capturedRepair = Map.copyOf(productionRules);
    return null;
  }

  @Override
  public void setHasPostedTurnSummary(final boolean hasPostedTurnSummary) {
    this.hasPostedTurnSummary = hasPostedTurnSummary;
  }

  @Override
  public boolean getHasPostedTurnSummary() {
    return hasPostedTurnSummary;
  }

  @Override
  public boolean postTurnSummary(final PbemMessagePoster poster, final String title) {
    return true;
  }

  @Override
  public void initialize(final String name, final String displayName) {
    this.name = name;
    this.displayName = displayName;
  }

  @Override
  public void setDelegateBridgeAndPlayer(final IDelegateBridge delegateBridge) {
    this.bridge = delegateBridge;
  }

  @Override
  public void setDelegateBridgeAndPlayer(
      final IDelegateBridge delegateBridge, final ClientNetworkBridge clientNetworkBridge) {
    this.bridge = delegateBridge;
  }

  @Override
  public void start() {}

  @Override
  public void end() {}

  @Override
  public String getName() {
    return name;
  }

  @Override
  public String getDisplayName() {
    return displayName;
  }

  @Override
  public IDelegateBridge getBridge() {
    return bridge;
  }

  @Override
  public Serializable saveState() {
    return new Serializable() {
      private static final long serialVersionUID = 1L;
    };
  }

  @Override
  public void loadState(final Serializable state) {}

  @Override
  public Class<? extends IRemote> getRemoteType() {
    return IPurchaseDelegate.class;
  }

  @Override
  public boolean delegateCurrentlyRequiresUserInput() {
    return false;
  }
}
