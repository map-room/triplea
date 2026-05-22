package org.triplea.ai.sidecar.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.ai.pro.data.AmphContext;
import games.strategy.triplea.ai.pro.simulate.ProDummyDelegateBridge;
import games.strategy.triplea.delegate.GameDataTestUtil;
import games.strategy.triplea.delegate.MoveDelegate;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

/**
 * Unit tests for {@link RecordingMoveDelegate}.
 *
 * <p>After the refactor from a no-op interface stub to a validating {@link MoveDelegate} subclass,
 * most capture-path tests now require a fully wired {@link games.strategy.triplea.ai.pro.ProAi} and
 * a {@link games.strategy.engine.data.GameData} whose sequence is set to a valid combat-move or
 * noncombat-move step — those conditions are met by {@code CombatMoveExecutorIntegrationTest} and
 * {@code NoncombatMoveExecutorIntegrationTest}, which provide end-to-end capture coverage.
 *
 * <p>This test class verifies:
 *
 * <ul>
 *   <li>The structural invariant: {@link RecordingMoveDelegate} must extend {@link MoveDelegate}.
 *   <li>Regression map-room#2715: free-embark moves must be captured directly without being passed
 *       through Java's {@code MoveValidator} (which has no free-embark concept and rejects with
 *       "Not enough transports").
 * </ul>
 */
class RecordingMoveDelegateTest {

  @BeforeAll
  static void loadData() throws Exception {
    ClientSetting.setPreferences(new MemoryPreferences());
  }

  @Test
  void isSubclassOfMoveDelegate() {
    // RecordingMoveDelegate must extend the real MoveDelegate so super.performMove()
    // validates via MoveValidator rather than silently accepting every move.
    assertInstanceOf(MoveDelegate.class, new RecordingMoveDelegate(null));
  }

  /**
   * Regression for map-room#2715: a free-embark move (AmphContext.enabled, isLoad() route, empty
   * unitsToSeaTransports) must be <em>captured</em> by {@link RecordingMoveDelegate#performMove},
   * not rejected with "Not enough transports".
   *
   * <p>Before the fix, {@code performMove()} called {@code super.performMove()} unconditionally.
   * Java's {@code MoveValidator} has no concept of the free-embark rule: it always rejects a
   * land-to-sea route that has no physical transport mapped in {@code unitsToSeaTransports},
   * returning {@code Optional.of("Not enough transports")}. The AI then silently discarded the
   * move, leaving it fully inert in no-transports mode.
   *
   * <p>The fix adds an early-return guard: when the three free-embark conditions are all true
   * ({@code AmphContext.isEnabled()}, {@code route.isLoad()}, {@code
   * unitsToSeaTransports.isEmpty()}) the delegate bypasses {@code super.performMove()} and records
   * the move directly.
   *
   * <p><b>Failure mode without the fix:</b> {@code result.isPresent()} with message "Not enough
   * transports" and {@code recorder.captured().isEmpty()}.
   *
   * <p>This test does <b>not</b> mock {@code performMove} — mocking it defeats the purpose of
   * verifying the bypass exists in the real implementation path.
   */
  @Test
  void freeEmbarkIsCapturedNotRejectedByMoveValidator() {
    final GameData data = TestMapGameData.GLOBAL1940.getGameData();
    final GamePlayer americans = data.getPlayerList().getPlayerId("Americans");

    // Clear all units for a clean slate, then place one infantry in Korea.
    // Korea is adjacent to 6 Sea Zone (water) — the load route we'll use.
    // No transport anywhere: Java MoveValidator would return "Not enough transports".
    for (final Territory t : data.getMap().getTerritories()) {
      data.performChange(ChangeFactory.removeUnits(t, t.getUnits()));
    }
    final Territory korea = data.getMap().getTerritoryOrThrow("Korea");
    final Territory sz6 = data.getMap().getTerritoryOrThrow("6 Sea Zone");
    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(korea, List.of(infantry)));

    // Advance the game sequence to the Americans NCM step so MoveDelegate delegates are
    // correctly scoped (same pattern used in ProNonCombatMoveAmphTest).
    try (GameData.Unlocker ignored = data.acquireWriteLock()) {
      final int length = data.getSequence().size();
      for (int i = 0; i < length; i++) {
        if (data.getSequence().getStep().getName().contains("americansNonCombatMove")) {
          break;
        }
        data.getSequence().next();
      }
    }

    // Wire up ProAi + RecordingMoveDelegate — same sequence as NoncombatMoveExecutor.
    final ProAi proAi = new ProAi("Test Americans", "Americans AI");
    ExecutorSupport.initializeProAi(proAi, data, americans);
    final RecordingMoveDelegate recorder = new RecordingMoveDelegate(proAi);
    recorder.initialize("move", "Move");
    recorder.setDelegateBridgeAndPlayer(new ProDummyDelegateBridge(proAi, americans, data));
    proAi.reinitializeProDataForSidecar();
    // Set AmphContext AFTER reinitialize: reinitializeProDataForSidecar() resets proData,
    // so any context set before that call would be overwritten.
    proAi.getProData().setAmphContext(new AmphContext(true, Set.of(), Set.of()));

    // Build the free-embark MoveDescription:
    //   - Route: Korea (land) → 6 Sea Zone (water)  →  isLoad() == true
    //   - unitsToSeaTransports: empty  →  no physical transport involved
    final Route loadRoute = new Route(korea, sz6);
    assertTrue(loadRoute.isLoad(), "Precondition: Korea → 6 Sea Zone must satisfy isLoad()");
    final MoveDescription move = new MoveDescription(List.of(infantry), loadRoute, Map.of());
    assertTrue(
        move.getUnitsToSeaTransports().isEmpty(),
        "Precondition: unitsToSeaTransports must be empty for a free-embark move");

    // --- Exercise ---
    final Optional<String> result = recorder.performMove(move);

    // --- Assert ---
    assertTrue(
        result.isEmpty(),
        "Free-embark (AmphContext.enabled + isLoad + no sea transport) must be accepted "
            + "(Optional.empty()), not rejected. Error returned: "
            + result.orElse("(none)"));
    assertEquals(
        1, recorder.captured().size(), "Free-embark move must appear exactly once in captured()");
    assertEquals(
        move,
        recorder.captured().get(0).move(),
        "Captured move must match the submitted MoveDescription");
  }

  // NOTE: The former "capturesMovesInOrder", "partitionsNonBombingMoves",
  // "partitionsBombingMovesByEndTerritory", and "capturedListIsImmutable" tests relied on the
  // package-private Predicate<Territory> constructor and the no-op stub behaviour (empty-unit
  // moves were silently accepted). After the refactor, RecordingMoveDelegate calls
  // super.performMove(), which requires a valid game step in GameData.getSequence() before it
  // can even reach MoveValidator. Full end-to-end capture coverage — including the bombing
  // predicate — is provided by CombatMoveExecutorIntegrationTest and
  // NoncombatMoveExecutorIntegrationTest.

  @Test
  @Disabled(
      "TODO: covers now-illegal input — needs a live ProAi, bridge, and valid game step; "
          + "rewrite using the integration-test fixture if lightweight unit coverage is needed")
  void capturesMovesInOrder() {}

  @Test
  @Disabled("TODO: covers now-illegal input — see capturesMovesInOrder note")
  void partitionsNonBombingMoves() {}

  @Test
  @Disabled("TODO: covers now-illegal input — see capturesMovesInOrder note")
  void partitionsBombingMovesByEndTerritory() {}

  @Test
  @Disabled("TODO: covers now-illegal input — see capturesMovesInOrder note")
  void capturedListIsImmutable() {}
}
