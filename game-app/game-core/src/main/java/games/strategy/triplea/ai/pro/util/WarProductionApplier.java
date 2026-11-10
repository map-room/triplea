package games.strategy.triplea.ai.pro.util;

import games.strategy.engine.data.Change;
import games.strategy.engine.data.CompositeChange;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MutableProperty;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.attachments.FireTriggerParams;
import games.strategy.triplea.attachments.ICondition;
import games.strategy.triplea.attachments.TriggerAttachment;
import games.strategy.triplea.ui.ObjectiveDummyDelegateBridge;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.triplea.java.collections.IntegerMap;

/**
 * Stands in for Global 1940 {@code triggerAttachment_Americans_War_Production_*} (map-room#4754).
 *
 * <p>Those triggers swap {@code factory_minor} for {@code factory_major} on Eastern, Central and
 * Western United States once the US is at war with any Axis power ({@code after:americansPolitics},
 * {@code uses=1}). The sidecar is stateless and never fires XML triggers. Purchase simulation may
 * declare that war on a {@link GameData} copy; without this, {@code purchase()} still sees
 * 9-capacity minor ICs.
 *
 * <p>Mirrors {@code MovementRestrictionLifter}: collect the XML triggers by their effect, test
 * their conditions against the copy's relationships, and apply TripleA's own unit removal and
 * placement. Only {@code player}'s triggers are considered, so Japan's purchase simulation does not
 * upgrade US ICs after declaring war on the Americans ({@code after:americansPolitics} is a US-turn
 * trigger). The XML already no-ops when a major (or upgrade) factory is present ({@code
 * conditionAttachment_Americans_Major_Factory_*} is inverted unit presence), so a second call or a
 * wire that already carries {@code factory_major} is a no-op. {@code uses=1} is re-derived each
 * request from a fresh canonical clone, the same way the restriction lifter re-derives a single-use
 * lift.
 */
public final class WarProductionApplier {

  private static final Logger LOG = System.getLogger(WarProductionApplier.class.getName());

  private static final String FACTORY_MINOR = "factory_minor";
  private static final String FACTORY_MAJOR = "factory_major";

  /** Fire every satisfied trigger regardless of its step, uses or chance. */
  private static final FireTriggerParams FIRE_PARAMS =
      new FireTriggerParams(null, null, false, false, false, false);

  private WarProductionApplier() {}

  /**
   * Applies every satisfied war-production factory swap attached to {@code player} on {@code
   * gameData}. {@code player} must belong to {@code gameData}.
   */
  public static void apply(final GameData gameData, final GamePlayer player) {
    final Set<TriggerAttachment> upgrading =
        TriggerAttachment.collectForAllTriggersMatching(
            Set.of(player), WarProductionApplier::isWarProductionSwap);
    if (upgrading.isEmpty()) {
      return;
    }
    final CollectingBridge bridge = new CollectingBridge(gameData);
    final Map<ICondition, Boolean> tested =
        TriggerAttachment.collectTestsForAllTriggers(upgrading, bridge);
    final Set<TriggerAttachment> satisfied =
        upgrading.stream().filter(t -> t.isSatisfied(tested)).collect(Collectors.toSet());
    if (satisfied.isEmpty()) {
      return;
    }
    TriggerAttachment.triggerUnitRemoval(satisfied, bridge, FIRE_PARAMS);
    TriggerAttachment.triggerUnitPlacement(satisfied, bridge, FIRE_PARAMS);
    if (!bridge.changes.isEmpty()) {
      gameData.performChange(bridge.changes);
      LOG.log(
          Level.INFO,
          () ->
              "war production applied by "
                  + satisfied.stream().map(TriggerAttachment::getName).sorted().toList());
    }
  }

  /**
   * True for triggers that remove a minor IC and place a major IC. In Global 1940 that is exactly
   * {@code triggerAttachment_Americans_War_Production_Eastern/Central/Western}.
   */
  private static boolean isWarProductionSwap(final TriggerAttachment trigger) {
    return TriggerAttachment.removeUnitsMatch().test(trigger)
        && TriggerAttachment.placeMatch().test(trigger)
        && mapHasUnitType(trigger, "removeUnits", FACTORY_MINOR)
        && mapHasUnitType(trigger, "placement", FACTORY_MAJOR);
  }

  @SuppressWarnings("unchecked")
  private static boolean mapHasUnitType(
      final TriggerAttachment trigger, final String property, final String unitTypeName) {
    return trigger
        .getPropertyOrEmpty(property)
        .map(MutableProperty::getValue)
        .filter(Map.class::isInstance)
        .map(Map.class::cast)
        .stream()
        .flatMap(map -> map.values().stream())
        .anyMatch(value -> integerMapHasUnitType(value, unitTypeName));
  }

  private static boolean integerMapHasUnitType(final Object value, final String unitTypeName) {
    if (!(value instanceof IntegerMap<?> units)) {
      return false;
    }
    return units.keySet().stream()
        .anyMatch(
            key -> key instanceof UnitType unitType && unitTypeName.equals(unitType.getName()));
  }

  /** Condition-testing bridge that keeps the trigger's changes instead of discarding them. */
  private static final class CollectingBridge extends ObjectiveDummyDelegateBridge {
    private final CompositeChange changes = new CompositeChange();

    CollectingBridge(final GameData data) {
      super(data);
    }

    @Override
    public void addChange(final Change change) {
      changes.add(change);
    }
  }
}
