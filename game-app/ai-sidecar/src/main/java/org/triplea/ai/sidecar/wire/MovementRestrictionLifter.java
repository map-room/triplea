package org.triplea.ai.sidecar.wire;

import games.strategy.engine.data.Change;
import games.strategy.engine.data.CompositeChange;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.MutableProperty;
import games.strategy.triplea.attachments.FireTriggerParams;
import games.strategy.triplea.attachments.ICondition;
import games.strategy.triplea.attachments.TriggerAttachment;
import games.strategy.triplea.ui.ObjectiveDummyDelegateBridge;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.triplea.util.Tuple;

/**
 * Lifts a player's XML movement restriction once the game's own lifting trigger would have fired
 * (map-room#4709).
 *
 * <p>Global 1940 restricts where the neutral Americans and Japanese may move ({@code
 * movementRestrictionTerritories}) and clears the restriction with a trigger, such as {@code
 * triggerAttachment_Americans_Unrestricted_Movement} ({@code before:americansCombatMove}, when at
 * war with any Axis power). The sidecar is stateless: every request clones the canonical XML and
 * applies Map Room's state, and no trigger ever fires. Without this, the restriction is permanent,
 * and {@code MoveValidator} silently refuses every US move through 88 Sea Zone for the whole game.
 *
 * <p>This mirrors the trigger rather than hardcoding zones or nations: it collects every trigger
 * that sets {@code movementRestrictionTerritories}, tests that trigger's XML conditions against the
 * applied relationships, and fires the satisfied ones' player-property changes through TripleA's
 * own {@link TriggerAttachment#triggerPlayerPropertyChange}. Before the war the condition fails and
 * the restriction still binds.
 *
 * <p>The XML triggers are single-use ({@code uses=1}): in TripleA the lift outlasts a later peace.
 * Here it is re-derived from each request's relationships. That only differs if a nation returns to
 * peace with every Axis power, which Map Room's politics never does.
 */
final class MovementRestrictionLifter {

  private static final Logger LOG = System.getLogger(MovementRestrictionLifter.class.getName());

  static final String RESTRICTION_PROPERTY = "movementRestrictionTerritories";

  /** Fire every satisfied trigger regardless of its step, uses or chance. */
  private static final FireTriggerParams FIRE_PARAMS =
      new FireTriggerParams(null, null, false, false, false, false);

  private MovementRestrictionLifter() {}

  /** Applies every satisfied restriction-lifting trigger to {@code gameData}. */
  static void apply(final GameData gameData) {
    final Set<TriggerAttachment> lifting =
        TriggerAttachment.collectForAllTriggersMatching(
            new HashSet<>(gameData.getPlayerList().getPlayers()),
            MovementRestrictionLifter::setsMovementRestriction);
    if (lifting.isEmpty()) {
      return;
    }
    final CollectingBridge bridge = new CollectingBridge(gameData);
    final Map<ICondition, Boolean> tested =
        TriggerAttachment.collectTestsForAllTriggers(lifting, bridge);
    final Set<TriggerAttachment> satisfied =
        lifting.stream().filter(t -> t.isSatisfied(tested)).collect(Collectors.toSet());
    if (satisfied.isEmpty()) {
      return;
    }
    TriggerAttachment.triggerPlayerPropertyChange(satisfied, bridge, FIRE_PARAMS);
    if (!bridge.changes.isEmpty()) {
      gameData.performChange(bridge.changes);
      LOG.log(
          Level.INFO,
          () ->
              "movement restriction lifted by "
                  + satisfied.stream().map(TriggerAttachment::getName).sorted().toList());
    }
  }

  private static boolean setsMovementRestriction(final TriggerAttachment trigger) {
    return trigger
        .getPropertyOrEmpty("playerProperty")
        .map(MutableProperty::getValue)
        .filter(List.class::isInstance)
        .map(List.class::cast)
        .filter(
            properties ->
                properties.stream()
                    .anyMatch(
                        p ->
                            p instanceof Tuple<?, ?> tuple
                                && RESTRICTION_PROPERTY.equals(tuple.getFirst())))
        .isPresent();
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
