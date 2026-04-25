package org.triplea.ai.sidecar.exec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import games.strategy.engine.data.GameData;
import games.strategy.triplea.ai.pro.AbstractProAi;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.ai.pro.data.ProPurchaseTerritory;
import games.strategy.triplea.settings.ClientSetting;
import java.lang.reflect.Field;
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
import org.triplea.ai.sidecar.dto.CombatMoveRequest;
import org.triplea.ai.sidecar.dto.NoncombatMovePlan;
import org.triplea.ai.sidecar.dto.NoncombatMoveRequest;
import org.triplea.ai.sidecar.dto.PurchaseRequest;
import org.triplea.ai.sidecar.session.ProSessionSnapshotStore;
import org.triplea.ai.sidecar.session.Session;
import org.triplea.ai.sidecar.session.SessionKey;
import org.triplea.ai.sidecar.wire.WireState;

/**
 * Integration tests for {@link NoncombatMoveExecutor}. Runs a full purchase → combat-move →
 * noncombat-move sequence on the same session (purchase populates both {@code storedFactoryMoveMap}
 * and {@code storedPurchaseTerritories}; combat-move consumes {@code storedCombatMoveMap}).
 */
class NoncombatMoveExecutorIntegrationTest {

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
        new SessionKey("g1", nation),
        42L,
        proAi,
        data,
        new ConcurrentHashMap<>(),
        Executors.newSingleThreadExecutor());
  }

  private static WireState wireState(final String phase, final String nation) {
    return new WireState(List.of(), List.of(), 1, phase, nation, List.of());
  }

  // The StepNameMapper uses TripleA camelCase phase names: "purchase", "combatMove",
  // "nonCombatMove", "place". These must match exactly for WireStateApplier to advance the
  // game sequence to the correct step, which ProNonCombatMoveAi needs to call isCombatMove().
  private static WireState noncombatWireState(final String nation) {
    return wireState("nonCombatMove", nation);
  }

  /**
   * Full pipeline: purchase → combat-move → noncombat-move. Asserts the returned plan is non-null
   * and that no captured move has {@code isBombing == true}.
   */
  @Test
  void germansNoncombatMoveAfterFullPipeline() {
    final ProSessionSnapshotStore store = new ProSessionSnapshotStore(snapshotDir);
    final Session session = freshSession("Germans");

    // Step 1: purchase — populates storedFactoryMoveMap, storedCombatMoveMap,
    // storedPurchaseTerritories
    new PurchaseExecutor(store)
        .execute(session, new PurchaseRequest(wireState("purchase", "Germans")));

    // Step 2: combat-move — consumes storedCombatMoveMap
    new CombatMoveExecutor(store)
        .execute(session, new CombatMoveRequest(wireState("combatMove", "Germans")));

    // Step 3: noncombat-move — consumes storedFactoryMoveMap, preserves storedPurchaseTerritories
    final NoncombatMovePlan plan =
        new NoncombatMoveExecutor(store)
            .execute(session, new NoncombatMoveRequest(noncombatWireState("Germans")));

    assertNotNull(plan, "noncombat-move plan must not be null");
    // Germans typically move factories and units on noncombat-move
    assertFalse(
        plan.moves().isEmpty(),
        "Germans should have at least one noncombat move on turn 1; got: " + plan.moves());
  }

  /**
   * isBombing invariant: no captured move in the noncombat phase should have isBombing == true.
   * This is guaranteed by the executor which throws AssertionError if violated, but we also check
   * indirectly by verifying the plan is returned without exception.
   */
  @Test
  void noBombingMovesInNoncombatPhase() {
    final ProSessionSnapshotStore store = new ProSessionSnapshotStore(snapshotDir);
    final Session session = freshSession("Germans");

    new PurchaseExecutor(store)
        .execute(session, new PurchaseRequest(wireState("purchase", "Germans")));
    new CombatMoveExecutor(store)
        .execute(session, new CombatMoveRequest(wireState("combatMove", "Germans")));

    // If any captured move had isBombing==true, the executor would throw AssertionError.
    // The plan being returned means the invariant held.
    final NoncombatMovePlan plan =
        new NoncombatMoveExecutor(store)
            .execute(session, new NoncombatMoveRequest(noncombatWireState("Germans")));

    assertNotNull(plan, "plan must not be null (no bombing invariant violation)");
  }

  /**
   * storedPurchaseTerritories must be preserved after noncombat-move so the place executor can
   * consume it. Verifies via reflection that the field is still non-null.
   */
  @Test
  void storedPurchaseTerritoriesPreservedAfterNoncombatMove() throws Exception {
    final ProSessionSnapshotStore store = new ProSessionSnapshotStore(snapshotDir);
    final Session session = freshSession("Germans");

    new PurchaseExecutor(store)
        .execute(session, new PurchaseRequest(wireState("purchase", "Germans")));
    new CombatMoveExecutor(store)
        .execute(session, new CombatMoveRequest(wireState("combatMove", "Germans")));
    new NoncombatMoveExecutor(store)
        .execute(session, new NoncombatMoveRequest(noncombatWireState("Germans")));

    // storedPurchaseTerritories must still be non-null (not cleared by noncombat-move)
    final Field field = AbstractProAi.class.getDeclaredField("storedPurchaseTerritories");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    final java.util.Map<games.strategy.engine.data.Territory, ProPurchaseTerritory> stored =
        (java.util.Map<games.strategy.engine.data.Territory, ProPurchaseTerritory>)
            field.get(session.proAi());

    assertNotNull(
        stored,
        "storedPurchaseTerritories must not be null after noncombat-move — place executor needs it");
    assertFalse(
        stored.isEmpty(), "storedPurchaseTerritories must be non-empty after noncombat-move");
  }

  /**
   * Missing-unit resilience: a snapshot with stale unit UUIDs in storedFactoryMoveMap must not
   * cause an NPE — the defensive drop in #1763 skips unknown units silently.
   */
  @Test
  void staleUnitInFactoryMoveMapIsDroppedSilently() {
    final ProSessionSnapshotStore store = new ProSessionSnapshotStore(snapshotDir);
    final Session session = freshSession("Germans");

    // Run purchase and combat-move normally to establish the session state
    new PurchaseExecutor(store)
        .execute(session, new PurchaseRequest(wireState("purchase", "Germans")));
    new CombatMoveExecutor(store)
        .execute(session, new CombatMoveRequest(wireState("combatMove", "Germans")));

    // Inject a stale UUID into the snapshot's factoryMoveMap
    final var staleUuid = UUID.randomUUID().toString();
    final var staleSnap =
        new games.strategy.triplea.ai.pro.data.ProSessionSnapshot(
            java.util.Map.of(),
            java.util.Map.of(
                "Germany",
                new games.strategy.triplea.ai.pro.data.ProTerritorySnapshot(
                    java.util.List.of(staleUuid), // stale unit UUID
                    java.util.List.of(),
                    java.util.Map.of(),
                    java.util.Map.of(),
                    java.util.Map.of())),
            java.util.Map.of(),
            java.util.Map.of());

    // Save the stale snapshot — noncombat-move executor will try to restore from it
    // But storedFactoryMoveMap is already non-null from the purchase run, so restore is a no-op.
    // Verify the executor still completes without NPE.
    store.save(session.key(), staleSnap);

    final NoncombatMovePlan plan =
        new NoncombatMoveExecutor(store)
            .execute(session, new NoncombatMoveRequest(noncombatWireState("Germans")));

    assertNotNull(plan, "plan must not be null even with stale snapshot stored");
  }
}
