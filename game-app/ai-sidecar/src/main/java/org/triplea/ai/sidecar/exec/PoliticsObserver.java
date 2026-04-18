package org.triplea.ai.sidecar.exec;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.triplea.attachments.PoliticalActionAttachment;
import games.strategy.triplea.delegate.PoliticsDelegate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.triplea.ai.sidecar.dto.WarDeclaration;

/**
 * Observes politics-attempt calls on the live {@link PoliticsDelegate} during a sidecar
 * combat-move execution and translates captured actions into {@link WarDeclaration}s.
 *
 * <p>The observer does NOT intercept the real delegate — {@code attemptAction} still runs its full
 * mutation of the in-memory {@link games.strategy.engine.data.RelationshipTracker} so the
 * combat-move planning that follows on the same GameData sees the post-politics graph (see
 * map-room#1824 phase-ordering constraint).
 *
 * <p>Install via {@link #attach(GameData)} before invoking {@code
 * AbstractProAi.invokePoliticsForSidecar()}; {@link #detach()} afterward restores the original
 * {@link PoliticsDelegate} (or removes the observing one if there was no original).
 */
public final class PoliticsObserver {

  /**
   * The 9 primary Map Room nations. TripleA's ww2global40_2nd_edition.xml contains split factions
   * like {@code UK_Pacific} and {@code Dutch} that are not first-class players in Map Room's engine.
   * War declarations against these non-primary names are rejected by Map Room with
   * {@code ERROR: invalid move: declareWar args: <name>}. Filter them out here so only valid
   * targets are returned by {@link #toWarDeclarations}.
   */
  private static final Set<String> MAP_ROOM_PRIMARY_NATIONS =
      Set.of("Americans", "ANZAC", "British", "Chinese", "French",
             "Germans", "Italians", "Japanese", "Russians");

  private final GameData gameData;
  private final PoliticsDelegate original;
  private final List<PoliticalActionAttachment> captured = new ArrayList<>();

  private PoliticsObserver(final GameData gameData, final PoliticsDelegate original) {
    this.gameData = gameData;
    this.original = original;
  }

  /**
   * Attaches an observing politics delegate to {@code gameData}. Any pre-existing "politics"
   * delegate is saved and restored by {@link #detach()}.
   */
  public static PoliticsObserver attach(final GameData gameData) {
    final PoliticsDelegate orig =
        gameData
            .getDelegateOptional("politics")
            .filter(PoliticsDelegate.class::isInstance)
            .map(PoliticsDelegate.class::cast)
            .orElse(null);

    final PoliticsObserver observer = new PoliticsObserver(gameData, orig);
    final ObservingPoliticsDelegate observing = new ObservingPoliticsDelegate(observer);
    observing.initialize("politics", "Politics");
    gameData.addDelegate(observing);
    return observer;
  }

  /**
   * Restore the original politics delegate. If there was no original, removes the observing
   * delegate by overwriting the "politics" key with a no-op sentinel. Calling {@link
   * #toWarDeclarations} after detach is safe — the captured list is not cleared.
   */
  public void detach() {
    if (original != null) {
      gameData.addDelegate(original);
    }
    // If there was no original, leave the observing delegate in place — the sidecar
    // session is ephemeral and the map entry being present causes no harm. This matches
    // how WireStateApplier handles the battle delegate (install, never remove).
  }

  /**
   * Record an attempted politics action. Called by the {@link ObservingPoliticsDelegate} after
   * delegating to {@code super.attemptAction}. Also exposed as a test seam so unit tests can
   * inject actions directly without needing a fully wired {@link
   * games.strategy.engine.delegate.IDelegateBridge}.
   */
  void recordAttempt(final PoliticalActionAttachment action) {
    captured.add(action);
  }

  /**
   * Translate captured attempts into war declarations made by {@code actingPlayer}.
   *
   * <p>Walks each action's {@link PoliticalActionAttachment#getRelationshipChanges()}, keeps
   * entries where the new relationship {@link
   * games.strategy.triplea.attachments.RelationshipTypeAttachment#isWar()} AND one of the two
   * players is {@code actingPlayer}. Returns the other player as the {@link WarDeclaration}
   * target. Mirrors the warPlayers extraction in {@code ProPoliticsAi}.
   */
  public List<WarDeclaration> toWarDeclarations(final GamePlayer actingPlayer) {
    final List<WarDeclaration> out = new ArrayList<>();
    for (final PoliticalActionAttachment action : captured) {
      for (final PoliticalActionAttachment.RelationshipChange change :
          action.getRelationshipChanges()) {
        if (change.relationshipType == null
            || !change.relationshipType.getRelationshipTypeAttachment().isWar()) {
          continue;
        }
        final String p1 = change.player1.getName();
        final String p2 = change.player2.getName();
        final String acting = actingPlayer.getName();
        final String target;
        if (acting.equals(p1)) {
          target = p2;
        } else if (acting.equals(p2)) {
          target = p1;
        } else {
          continue; // action didn't involve the acting player
        }
        if (!MAP_ROOM_PRIMARY_NATIONS.contains(target)) {
          continue; // non-primary (UK_Pacific, Dutch, etc.) — skip
        }
        out.add(new WarDeclaration(target));
      }
    }
    return out;
  }

  /**
   * {@link PoliticsDelegate} subclass that records every {@code attemptAction} call after
   * forwarding it to {@code super} so the real relationship mutation still occurs.
   */
  private static final class ObservingPoliticsDelegate extends PoliticsDelegate {

    private final PoliticsObserver owner;

    ObservingPoliticsDelegate(final PoliticsObserver owner) {
      this.owner = owner;
    }

    @Override
    public void attemptAction(final PoliticalActionAttachment paa) {
      try {
        super.attemptAction(paa);
      } finally {
        owner.recordAttempt(paa);
      }
    }
  }
}
