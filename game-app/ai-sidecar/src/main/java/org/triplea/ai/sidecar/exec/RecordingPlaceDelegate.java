package org.triplea.ai.sidecar.exec;

import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.message.IRemote;
import games.strategy.net.websocket.ClientNetworkBridge;
import games.strategy.triplea.delegate.UndoablePlacement;
import games.strategy.triplea.delegate.data.PlaceableUnits;
import games.strategy.triplea.delegate.remote.IAbstractPlaceDelegate;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Stub {@link IAbstractPlaceDelegate} that captures every {@link #placeUnits} call made by
 * {@link games.strategy.triplea.ai.pro.ProPurchaseAi#place}.
 *
 * <p>{@code ProPurchaseAi.doPlace()} calls {@code del.placeUnits(List.of(unit), t, NOT_BID)} once
 * per unit, so captures accumulate as a flat list of single-unit {@link PlaceCapture} records.
 * The {@link PlaceExecutor} groups them by territory when building the {@link
 * org.triplea.ai.sidecar.dto.PlacePlan}.
 *
 * <p>All other {@link IAbstractPlaceDelegate} methods are no-ops; the ProAi place path only calls
 * {@code placeUnits} and (for the "remaining units" fallback path) {@code getPlaceableUnits}.
 * {@code getPlaceableUnits} returns an empty {@link PlaceableUnits}, which causes the fallback
 * path to place nothing — the same outcome as having no remaining units.
 */
public final class RecordingPlaceDelegate implements IAbstractPlaceDelegate {

  /** A single captured placeUnits call. */
  public record PlaceCapture(Collection<Unit> units, Territory territory) {}

  private final List<PlaceCapture> captured = new ArrayList<>();

  @Override
  public Optional<String> placeUnits(
      final Collection<Unit> units, final Territory at, final BidMode bidMode) {
    captured.add(new PlaceCapture(List.copyOf(units), at));
    return Optional.empty();
  }

  /** Returns a snapshot of all captured placements in call order. */
  public List<PlaceCapture> captured() {
    return List.copyOf(captured);
  }

  // -----------------------------------------------------------------------
  // No-op implementations — none of these are called by ProPurchaseAi.place()
  // on the hot path (storedPurchaseTerritories → doPlace() → placeUnits).
  // -----------------------------------------------------------------------

  @Override
  public List<UndoablePlacement> getMovesMade() {
    return List.of();
  }

  @Override
  @Nullable
  public String undoMove(final int moveIndex) {
    return null;
  }

  /** Returns empty PlaceableUnits — sufficient for the "remaining units" fallback path. */
  @Override
  public PlaceableUnits getPlaceableUnits(final Collection<Unit> units, final Territory at) {
    return new PlaceableUnits();
  }

  @Override
  public int getPlacementsMade() {
    return 0;
  }

  @Override
  public Collection<Territory> getTerritoriesWhereAirCantLand() {
    return List.of();
  }

  @Override
  public void initialize(final String name, final String displayName) {}

  @Override
  public void setDelegateBridgeAndPlayer(final IDelegateBridge delegateBridge) {}

  @Override
  public void setDelegateBridgeAndPlayer(
      final IDelegateBridge delegateBridge, final ClientNetworkBridge clientNetworkBridge) {}

  @Override
  public void start() {}

  @Override
  public void end() {}

  @Override
  public String getName() {
    return "";
  }

  @Override
  public String getDisplayName() {
    return "";
  }

  @Override
  @Nullable
  public IDelegateBridge getBridge() {
    return null;
  }

  @Override
  @Nullable
  public Serializable saveState() {
    return null;
  }

  @Override
  public void loadState(final Serializable state) {}

  @Override
  public Class<? extends IRemote> getRemoteType() {
    return IAbstractPlaceDelegate.class;
  }

  @Override
  public boolean delegateCurrentlyRequiresUserInput() {
    return false;
  }
}
