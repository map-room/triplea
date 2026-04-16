package org.triplea.ai.sidecar.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.triplea.ai.sidecar.CanonicalGameData;

/**
 * Unit tests for {@link RecordingMoveDelegate}. Verifies capture order and SBR partition using
 * the package-private {@code Predicate<Territory>} constructor to avoid needing a live
 * {@link games.strategy.triplea.ai.pro.ProAi} instance.
 */
class RecordingMoveDelegateTest {

  private static GameData data;
  private static Territory germany;
  private static Territory poland;
  private static Territory ukraine;

  @BeforeAll
  static void loadData() throws Exception {
    data = CanonicalGameData.load().cloneForSession();
    germany = data.getMap().getTerritoryOrNull("Germany");
    poland  = data.getMap().getTerritoryOrNull("Poland");
    ukraine = data.getMap().getTerritoryOrNull("Ukraine");
  }

  @Test
  void capturesMovesInOrder() {
    final RecordingMoveDelegate delegate = new RecordingMoveDelegate(t -> false);
    final MoveDescription m1 = new MoveDescription(List.of(), new Route(germany, poland));
    final MoveDescription m2 = new MoveDescription(List.of(), new Route(poland, ukraine));

    assertEquals(Optional.empty(), delegate.performMove(m1));
    assertEquals(Optional.empty(), delegate.performMove(m2));

    final List<RecordingMoveDelegate.CapturedMove> captured = delegate.captured();
    assertEquals(2, captured.size());
    assertEquals(m1, captured.get(0).move());
    assertEquals(m2, captured.get(1).move());
  }

  @Test
  void partitionsNonBombingMoves() {
    final RecordingMoveDelegate delegate = new RecordingMoveDelegate(t -> false);
    delegate.performMove(new MoveDescription(List.of(), new Route(germany, poland)));
    delegate.performMove(new MoveDescription(List.of(), new Route(germany, ukraine)));

    assertTrue(delegate.captured().stream().noneMatch(RecordingMoveDelegate.CapturedMove::isBombing),
        "all moves should be non-bombing when predicate returns false");
  }

  @Test
  void partitionsBombingMovesByEndTerritory() {
    // Bombing predicate: only ukraine is an SBR target
    final Set<Territory> sbrTargets = Set.of(ukraine);
    final RecordingMoveDelegate delegate = new RecordingMoveDelegate(sbrTargets::contains);

    delegate.performMove(new MoveDescription(List.of(), new Route(germany, poland)));  // not SBR
    delegate.performMove(new MoveDescription(List.of(), new Route(germany, ukraine))); // SBR

    final List<RecordingMoveDelegate.CapturedMove> captured = delegate.captured();
    assertFalse(captured.get(0).isBombing(), "poland move must not be bombing");
    assertTrue(captured.get(1).isBombing(), "ukraine move must be bombing");
  }

  @Test
  void capturedListIsImmutable() {
    final RecordingMoveDelegate delegate = new RecordingMoveDelegate(t -> false);
    delegate.performMove(new MoveDescription(List.of(), new Route(germany, poland)));
    final List<RecordingMoveDelegate.CapturedMove> snapshot = delegate.captured();
    // Subsequent calls return new snapshots — snapshot doesn't grow
    delegate.performMove(new MoveDescription(List.of(), new Route(germany, ukraine)));
    assertEquals(1, snapshot.size(), "captured() snapshot must not reflect later additions");
  }
}
