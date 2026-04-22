package games.strategy.triplea.ai.pro.data;

import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.Constants;
import java.util.Map;
import org.triplea.java.collections.IntegerMap;

/**
 * Resource tracker for a player whose economy is split across multiple pools, each serving a subset
 * of the player's territories. Used for British in Axis &amp; Allies Global 1940 — London (Europe)
 * and Calcutta (Pacific) have independent IPC pools that can only pay for purchases placed in their
 * own territories.
 *
 * <p>Inherits {@link ProResourceTracker}'s no-territory API so callers that don't know about the
 * split still see sensible behavior: {@code hasEnough(ppo)} returns true if EITHER pool has enough,
 * {@code isEmpty()} false until BOTH pools are depleted. For correctness, every call site that
 * knows its placement target should call the territory-aware overloads.
 *
 * <p>Unmapped territories default to the Europe pool (conservative — London is the primary British
 * capital and keeps behavior sensible if an unexpected territory is encountered).
 */
public class ProSplitResourceTracker extends ProResourceTracker {

  public enum Pool {
    EUROPE,
    PACIFIC
  }

  private final IntegerMap<Resource> europeResources;
  private final IntegerMap<Resource> pacificResources;
  private IntegerMap<Resource> tempEurope = new IntegerMap<>();
  private IntegerMap<Resource> tempPacific = new IntegerMap<>();
  private final Map<Territory, Pool> poolByTerritory;
  private final Resource pusResource;

  public ProSplitResourceTracker(
      final int europePus,
      final int pacificPus,
      final Map<Territory, Pool> poolByTerritory,
      final GameState data) {
    super(0, data);
    this.pusResource = data.getResourceList().getResourceOrThrow(Constants.PUS);
    this.europeResources = new IntegerMap<>();
    this.europeResources.add(pusResource, europePus);
    this.pacificResources = new IntegerMap<>();
    this.pacificResources.add(pusResource, pacificPus);
    this.poolByTerritory = poolByTerritory;
  }

  // --- Territory-aware API (preferred) ---

  @Override
  public boolean hasEnough(final ProPurchaseOption ppo, final Territory placeTerr) {
    return poolRemaining(poolFor(placeTerr)).greaterThanOrEqualTo(ppo.getCosts());
  }

  @Override
  public void purchase(final ProPurchaseOption ppo, final Territory placeTerr) {
    poolResources(poolFor(placeTerr)).subtract(ppo.getCosts());
  }

  @Override
  public void tempPurchase(final ProPurchaseOption ppo, final Territory placeTerr) {
    tempFor(poolFor(placeTerr)).add(ppo.getCosts());
  }

  @Override
  public void removeTempPurchase(final ProPurchaseOption ppo, final Territory placeTerr) {
    if (ppo != null) {
      tempFor(poolFor(placeTerr)).subtract(ppo.getCosts());
    }
  }

  // --- No-territory API (inherited; overridden to span both pools) ---

  @Override
  public boolean hasEnough(final ProPurchaseOption ppo) {
    return poolRemaining(Pool.EUROPE).greaterThanOrEqualTo(ppo.getCosts())
        || poolRemaining(Pool.PACIFIC).greaterThanOrEqualTo(ppo.getCosts());
  }

  /** No-territory purchase: defaults to the Europe pool (unmapped-territory policy). */
  @Override
  public void purchase(final ProPurchaseOption ppo) {
    europeResources.subtract(ppo.getCosts());
  }

  /** No-territory tempPurchase: defaults to the Europe pool. */
  @Override
  public void tempPurchase(final ProPurchaseOption ppo) {
    tempEurope.add(ppo.getCosts());
  }

  /** No-territory removeTempPurchase: defaults to the Europe pool. */
  @Override
  public void removeTempPurchase(final ProPurchaseOption ppo) {
    if (ppo != null) {
      tempEurope.subtract(ppo.getCosts());
    }
  }

  @Override
  public void confirmTempPurchases() {
    europeResources.subtract(tempEurope);
    pacificResources.subtract(tempPacific);
    clearTempPurchases();
  }

  @Override
  public void clearTempPurchases() {
    tempEurope = new IntegerMap<>();
    tempPacific = new IntegerMap<>();
  }

  @Override
  public boolean isEmpty() {
    return poolRemaining(Pool.EUROPE).allValuesEqual(0)
        && poolRemaining(Pool.PACIFIC).allValuesEqual(0);
  }

  @Override
  public int getTempPUs(final GameState data) {
    return tempEurope.getInt(pusResource) + tempPacific.getInt(pusResource);
  }

  @Override
  public String toString() {
    return "europe=" + poolRemaining(Pool.EUROPE) + " pacific=" + poolRemaining(Pool.PACIFIC);
  }

  // --- Helpers ---

  private Pool poolFor(final Territory t) {
    final Pool p = poolByTerritory.get(t);
    return p == null ? Pool.EUROPE : p;
  }

  private IntegerMap<Resource> poolResources(final Pool p) {
    return p == Pool.EUROPE ? europeResources : pacificResources;
  }

  private IntegerMap<Resource> tempFor(final Pool p) {
    return p == Pool.EUROPE ? tempEurope : tempPacific;
  }

  private IntegerMap<Resource> poolRemaining(final Pool p) {
    final IntegerMap<Resource> out = new IntegerMap<>(poolResources(p));
    out.subtract(tempFor(p));
    return out;
  }
}
