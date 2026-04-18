package org.triplea.ai.sidecar.exec;

import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.delegate.PlaceDelegate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Validating {@link PlaceDelegate} subclass that captures every successful {@link #placeUnits}
 * call made by {@link games.strategy.triplea.ai.pro.ProPurchaseAi#place}.
 *
 * <p>Each call is forwarded to {@code super.placeUnits()} first, which enforces §20 production
 * rules (wasConquered, factory ownership, capacity). Only placements that pass validation are
 * captured; invalid ones return the error string so that ProAi can observe the rejection and
 * re-plan if needed.
 *
 * <p>Callers must set up the bridge before use:
 * <ol>
 *   <li>{@code recorder.initialize("place", "Place")}
 *   <li>{@code recorder.setDelegateBridgeAndPlayer(new ProDummyDelegateBridge(proAi, player, data))}
 * </ol>
 *
 * <p>{@code ProPurchaseAi.doPlace()} calls {@code del.placeUnits(List.of(unit), t, NOT_BID)} once
 * per unit, so captures accumulate as a flat list of single-unit {@link PlaceCapture} records.
 * The {@link PlaceExecutor} groups them by territory when building the
 * {@link org.triplea.ai.sidecar.dto.PlacePlan}.
 */
public final class RecordingPlaceDelegate extends PlaceDelegate {

  /** A single captured placeUnits call (validation already passed). */
  public record PlaceCapture(Collection<Unit> units, Territory territory) {}

  private final List<PlaceCapture> captured = new ArrayList<>();

  @Override
  public Optional<String> placeUnits(
      final Collection<Unit> units, final Territory at, final BidMode bidMode) {
    final Optional<String> error = super.placeUnits(units, at, bidMode);
    if (error.isPresent()) {
      // Placement failed §20 validation — do NOT record, propagate error to ProAi.
      return error;
    }
    captured.add(new PlaceCapture(List.copyOf(units), at));
    return Optional.empty();
  }

  /** Returns a snapshot of all successfully validated placements in call order. */
  public List<PlaceCapture> captured() {
    return List.copyOf(captured);
  }
}
