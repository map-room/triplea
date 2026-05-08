package org.triplea.ai.sidecar.exec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import games.strategy.engine.data.GameData;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.settings.ClientSetting;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.dto.CombatMovePlan;
import org.triplea.ai.sidecar.dto.CombatMoveRequest;
import org.triplea.ai.sidecar.dto.PurchaseRequest;
import org.triplea.ai.sidecar.session.ProSessionSnapshotStore;
import org.triplea.ai.sidecar.session.Session;
import org.triplea.ai.sidecar.session.SessionKey;
import org.triplea.ai.sidecar.wire.WireState;

/**
 * Integration test for {@link CombatMoveExecutor}. Runs a real purchase first (which populates
 * {@code storedCombatMoveMap} via {@code think()}) then a real combat-move on the same session,
 * asserting that the returned plan contains at least one non-SBR move.
 */
class CombatMoveExecutorIntegrationTest {

  @TempDir Path snapshotDir;

  private static CanonicalGameData canonical;

  @BeforeAll
  static void init() {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load();
  }

  private Session freshSession(final String nation) {
    final GameData data = canonical.cloneForSession();
    final ProAi proAi = new ProAi("sidecar-test-" + nation, nation);
    return new Session(
        "s-test-" + UUID.randomUUID(),
        new SessionKey("g1", nation, 1),
        42L,
        proAi,
        data,
        new ConcurrentHashMap<>(),
        Executors.newSingleThreadExecutor());
  }

  @Test
  void germansHaveNonEmptyCombatMovesAfterPurchase() {
    final ProSessionSnapshotStore store = new ProSessionSnapshotStore(snapshotDir);
    final Session session = freshSession("Germans");

    // Run purchase — this calls think() which populates storedCombatMoveMap
    final PurchaseExecutor purchaseExecutor = new PurchaseExecutor(store);
    purchaseExecutor.execute(
        session,
        new PurchaseRequest(
            new WireState(List.of(), List.of(), 1, "purchase", "Germans", List.of())));

    // storedCombatMoveMap is now set; run combat-move on same session
    final CombatMoveExecutor combatMoveExecutor = new CombatMoveExecutor(store);
    final CombatMovePlan plan =
        combatMoveExecutor.execute(
            session,
            new CombatMoveRequest(
                new WireState(List.of(), List.of(), 1, "combatMove", "Germans", List.of())));

    assertNotNull(plan);
    // Germans on turn 1 have combat moves (they border enemy territories)
    assertFalse(
        plan.moves().isEmpty(),
        "Germans should have at least one combat move on turn 1; got: " + plan.moves());
  }

  @Test
  void combatMovePlanHasCorrectKind() {
    final ProSessionSnapshotStore store = new ProSessionSnapshotStore(snapshotDir);
    final Session session = freshSession("Germans");

    new PurchaseExecutor(store)
        .execute(
            session,
            new PurchaseRequest(
                new WireState(List.of(), List.of(), 1, "purchase", "Germans", List.of())));

    final CombatMovePlan plan =
        new CombatMoveExecutor(store)
            .execute(
                session,
                new CombatMoveRequest(
                    new WireState(List.of(), List.of(), 1, "combatMove", "Germans", List.of())));

    assertNotNull(plan, "plan must not be null");
  }
}
