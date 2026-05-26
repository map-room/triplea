package games.strategy.triplea.ai.pro;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.Properties;
import games.strategy.triplea.ai.pro.data.AiTheaterPriority;
import games.strategy.triplea.ai.pro.data.ProPurchaseOption;
import games.strategy.triplea.ai.pro.data.ProPurchaseOptionMap;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.util.TuvCostsCalculator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.Setter;
import org.triplea.java.collections.CollectionUtils;
import org.triplea.java.collections.IntegerMap;

/** Pro AI data. */
@Getter
public final class ProData {
  // Default values
  private boolean isSimulation = false;
  private double winPercentage = 95;
  private double minWinPercentage = 75;
  private @Nullable Territory myCapital = null;
  private List<Territory> myUnitTerritories = new ArrayList<>();
  private Map<Unit, Territory> unitTerritoryMap = new HashMap<>();
  private IntegerMap<UnitType> unitValueMap = new IntegerMap<>();
  private @Nullable ProPurchaseOptionMap purchaseOptions = null;
  // If we purchased units this turn that consume other units, these are the units selected to be
  // consumed. These are already located at the factory locations, so should not be moved. In the
  // future, we could add logic about moving such units to factory territories from elsewhere.
  private final Set<Unit> unitsToBeConsumed = new HashSet<>();
  private double minCostPerHitPoint = Double.MAX_VALUE;

  // ---------------------------------------------------------------------------
  // Strategic Value Field (map-room#2755 PR-A — Phase 1B).
  //
  // Defaults are chosen to be **zero-impact**: wStrat = 0.0 means the field is
  // computed-and-dumpable but never blended into the value map. Phase 4A tuning
  // flips wStrat above 0 once the calibrated bracket from §10 is validated.
  // Setters are wired so the sidecar executor can populate them from env vars /
  // sysprops at the request boundary (mirrors AI_DUMP_VALUES pattern).
  // ---------------------------------------------------------------------------

  @Setter private AiTheaterPriority aiTheaterPriority = AiTheaterPriority.KGF;

  /** Capital anchor strength. Mid of §10 calibrated bracket of 50-100. */
  @Setter private double gCap = 75.0;

  @Setter private double gamma = 0.85;

  /** Weight of the strategic field in the blend. 0.0 = no behavior change. */
  @Setter private double wStrat = 0.0;

  @Setter private double alphaOff = 0.10;
  @Setter private double betaLaunch = 0.0;

  /** Amphib commit threshold in the targeted theater. Phase 3B raises this. */
  @Setter private double tCommitTargeted = 0.0;

  /** Amphib commit threshold off-theater. Phase 3B raises this. */
  @Setter private double tCommitOffTheater = 0.0;

  /** Off-theater minimum garrison floor per key holding. Phase 3A populates. */
  @Setter private Map<Territory, Integer> dFloor = Map.of();

  /**
   * Lazy-cached strategic value field. Computed on first {@code findTerritoryValues}-style call
   * when {@code wStrat > 0}, then reused for the remainder of the request. Sidecar is stateless per
   * HTTP request, so the cache is scoped to one decision call — see plan §0 nuance.
   */
  @Setter private @Nullable Map<Territory, Double> strategicValueField = null;

  /** Seeded RNG for deterministic AI decisions. Seeded via {@link #setSeed(long)}. */
  @Getter private Random rng = new Random();

  private AbstractProAi proAi;
  private GameData data;
  private GamePlayer player;

  /** Seeds the RNG used by AI subsystems for deterministic behaviour. */
  public void setSeed(final long seed) {
    rng = new Random(seed);
  }

  public void initialize(final AbstractProAi proAi) {
    hiddenInitialize(proAi, proAi.getGameData(), proAi.getGamePlayer(), false);
  }

  public void initializeSimulation(
      final AbstractProAi proAi, final GameData data, final GamePlayer player) {
    hiddenInitialize(proAi, data, player, true);
  }

  public Territory getUnitTerritory(final Unit unit) {
    return unitTerritoryMap.get(unit);
  }

  public int getUnitValue(final UnitType type) {
    return unitValueMap.getInt(type);
  }

  private void hiddenInitialize(
      final AbstractProAi proAi,
      final GameData data,
      final GamePlayer player,
      final boolean isSimulation) {
    this.proAi = proAi;
    this.data = data;
    this.player = player;
    this.isSimulation = isSimulation;

    if (!Properties.getLowLuck(data.getProperties())) {
      winPercentage = 90;
      minWinPercentage = 65;
    }
    myCapital =
        TerritoryAttachment.getFirstOwnedCapitalOrFirstUnownedCapital(player, data.getMap())
            .orElse(null);
    myUnitTerritories =
        CollectionUtils.getMatches(
            data.getMap().getTerritories(), Matches.territoryHasUnitsOwnedBy(player));
    unitTerritoryMap = newUnitTerritoryMap(data);
    unitValueMap = new TuvCostsCalculator().getCostsForTuv(player);
    purchaseOptions = new ProPurchaseOptionMap(player, data);
    minCostPerHitPoint = getMinCostPerHitPoint(purchaseOptions.getLandOptions());
  }

  private static Map<Unit, Territory> newUnitTerritoryMap(final GameState data) {
    final Map<Unit, Territory> unitTerritoryMap = new HashMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      for (final Unit u : t.getUnits()) {
        unitTerritoryMap.put(u, t);
      }
    }
    return unitTerritoryMap;
  }

  private double getMinCostPerHitPoint(final List<ProPurchaseOption> landPurchaseOptions) {
    double minCostPerHitPoint = Double.MAX_VALUE;
    for (final ProPurchaseOption ppo : landPurchaseOptions) {
      if (ppo.getCostPerHitPoint() < minCostPerHitPoint) {
        minCostPerHitPoint = ppo.getCostPerHitPoint();
      }
    }
    return minCostPerHitPoint;
  }

  public ProTerritory getProTerritory(Map<Territory, ProTerritory> moveMap, Territory t) {
    return moveMap.computeIfAbsent(t, k -> new ProTerritory(t, this));
  }

  /**
   * SVF PR-B blend accessor (map-room#2755). Returns {@code S(n)} for a territory, or {@code 0.0}
   * if the strategic field is unset (i.e. {@code wStrat == 0} and no strategic dump is enabled).
   * Lazy-computes the full-board field on first read when {@code wStrat > 0}; the lazy-compute hook
   * in {@code findTerritoryValues} fires first in practice, so this method is the safety net for
   * callers (e.g. {@code findSeaTerritoryValues}) that don't pass through {@code
   * findTerritoryValues} first.
   *
   * <p>Returning 0 when unset is the zero-impact guarantee: {@code value += wStrat * S(t)} with
   * either factor at 0 leaves the baseline value-map byte-identical.
   */
  public double getStrategicValueFieldFor(final Territory t) {
    // Spec §2: SVF blend is US-only in Phase 1. Non-US players see 0 regardless of wStrat so
    // the lobby radio + env knobs only affect Americans behavior. Without this gate, axis
    // players (Germans, Japanese) saw Allied capitals as anchors and developed strategic
    // pulls of their own — e.g. Germans pulling armour from Southern France to Holland Belgium
    // because Belgium is 2 hops from London (75 × 0.85² = 54). That cross-player effect was
    // a spec-§2 violation caught in chase's live AI-vs-AI run. Plan §10 Q5 (extension to UK
    // and ANZAC) is a future phase — when it lands, this gate becomes a per-player config
    // check instead of a hardcoded "Americans" string.
    if (player == null || !"Americans".equals(player.getName())) {
      return 0.0;
    }
    if (strategicValueField == null) {
      if (wStrat <= 0) {
        return 0.0;
      }
      strategicValueField =
          games.strategy.triplea.ai.pro.util.ProStrategicValueField.compute(this, player);
    }
    final Double v = strategicValueField.get(t);
    return v == null ? 0.0 : v;
  }
}
