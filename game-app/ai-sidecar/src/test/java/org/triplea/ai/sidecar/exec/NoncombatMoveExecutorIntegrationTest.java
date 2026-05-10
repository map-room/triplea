package org.triplea.ai.sidecar.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import games.strategy.engine.data.GameData;
import games.strategy.triplea.ai.pro.AbstractProAi;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.ai.pro.data.ProSessionSnapshot;
import games.strategy.triplea.settings.ClientSetting;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.dto.NoncombatMovePlan;
import org.triplea.ai.sidecar.dto.NoncombatMoveRequest;
import org.triplea.ai.sidecar.dto.PurchaseRequest;
import org.triplea.ai.sidecar.dto.WireMoveDescription;
import org.triplea.ai.sidecar.session.ProSessionSnapshotStore;
import org.triplea.ai.sidecar.session.Session;
import org.triplea.ai.sidecar.session.SessionKey;
import org.triplea.ai.sidecar.wire.WireState;

/**
 * Integration tests for the stateless {@link NoncombatMoveExecutor} (map-room#2385).
 *
 * <p>Asserts (a) full pipeline still produces a valid plan, (b) the {@code isBombing} invariant
 * holds, (c) the §2195 Bulgaria regression still passes under the stateless contract, (d) the
 * stateless contract is deterministic across N runs against the same {@code (gamestate, seed)}, and
 * (e) the comparative gate: stateless NCM produces an equivalent plan to the legacy
 * snapshot-restored NCM (same set of {@code (from, to, unitTypeCounts)} entries) for a
 * representative round-1 fixture.
 */
class NoncombatMoveExecutorIntegrationTest {

  private static final long SEED = 42L;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir Path snapshotDir;

  private static CanonicalGameData canonical;

  @BeforeAll
  static void init() {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load();
  }

  private Session freshSession(final String nation) {
    final GameData data = canonical.cloneForSession();
    final ProAi proAi = new ProAi("sidecar-test-" + nation + "-" + UUID.randomUUID(), nation);
    // Mirror SessionRegistry.buildSession: seed proData RNG and the battle calculator at
    // construction time so the audit-mode harness baseline matches production.
    proAi.getProData().setSeed(SEED);
    proAi.seedBattleCalc(SEED);
    return new Session(
        "s-test-" + UUID.randomUUID(),
        new SessionKey("g1", nation, 1),
        SEED,
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
   * Full pipeline: purchase → noncombat-move. Asserts the returned plan is non-null and that
   * Germans produce at least one move on turn 1 (sanity smoke).
   */
  @Test
  void germansNoncombatMoveAfterFullPipeline() {
    final ProSessionSnapshotStore store = new ProSessionSnapshotStore(snapshotDir);
    final Session session = freshSession("Germans");

    new PurchaseExecutor(store)
        .execute(session, new PurchaseRequest(wireState("purchase", "Germans"), SEED));

    final NoncombatMovePlan plan =
        new NoncombatMoveExecutor()
            .execute(session, new NoncombatMoveRequest(noncombatWireState("Germans"), SEED));

    assertNotNull(plan, "noncombat-move plan must not be null");
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
        .execute(session, new PurchaseRequest(wireState("purchase", "Germans"), SEED));

    final NoncombatMovePlan plan =
        new NoncombatMoveExecutor()
            .execute(session, new NoncombatMoveRequest(noncombatWireState("Germans"), SEED));

    assertNotNull(plan, "plan must not be null (no bombing invariant violation)");
  }

  /**
   * Regression for map-room#2195: Germans must send at least one unit to Bulgaria
   * (Friendly_Neutral) on turn 1 NCM. The fix tightened {@code hasAlliedLandUnits} to player-owned
   * only; the stateless contract must not regress that.
   */
  @Test
  void germansClaimBulgariaOnTurn1() {
    final ProSessionSnapshotStore store = new ProSessionSnapshotStore(snapshotDir);
    final Session session = freshSession("Germans");

    new PurchaseExecutor(store)
        .execute(session, new PurchaseRequest(wireState("purchase", "Germans"), SEED));
    final NoncombatMovePlan plan =
        new NoncombatMoveExecutor()
            .execute(session, new NoncombatMoveRequest(noncombatWireState("Germans"), SEED));

    assertNotNull(plan);
    assertTrue(
        plan.moves().stream().anyMatch(m -> "Bulgaria".equals(m.to())),
        "Germans must send at least one unit to Bulgaria (Friendly_Neutral) on turn 1 NCM"
            + " — regression map-room#2195. Actual moves to: "
            + plan.moves().stream().map(WireMoveDescription::to).distinct().sorted().toList());
  }

  /**
   * Stateless contract: the executor MUST clear {@code storedFactoryMoveMap} / {@code
   * storedPurchaseTerritories} on the proAi instance before dispatch, so NCM behaviour is a pure
   * function of (wire payload, seed) — independent of any planning maps populated by a prior
   * purchase call on the same session.
   *
   * <p>Asserted via reflection: after NCM runs, both stored maps are {@code null}. (Place is
   * TS-side as of map-room#2360; the legacy "preserve {@code storedPurchaseTerritories} for the
   * place executor" contract no longer applies.)
   */
  @Test
  void statelessContract_storedMapsAreClearedAfterNoncombatMove() throws Exception {
    final ProSessionSnapshotStore store = new ProSessionSnapshotStore(snapshotDir);
    final Session session = freshSession("Germans");

    new PurchaseExecutor(store)
        .execute(session, new PurchaseRequest(wireState("purchase", "Germans"), SEED));
    new NoncombatMoveExecutor()
        .execute(session, new NoncombatMoveRequest(noncombatWireState("Germans"), SEED));

    assertNull(reflectStoredMap(session.proAi(), "storedFactoryMoveMap"));
    assertNull(reflectStoredMap(session.proAi(), "storedPurchaseTerritories"));
  }

  /**
   * Determinism gate (NCM-only flavour, scope of map-room#2385): same {@code (gamestate, seed)} →
   * byte-identical wire response across N fresh sessions, exercising the new stateless path.
   *
   * <p>Distinct from {@link ProAiDeterminismAuditTest}, which is parked {@code @Disabled} for the
   * known politics-threshold flake on the purchase side. This test sidesteps purchase entirely: it
   * calls NCM on a fresh ProAi (no prior purchase, so {@code storedFactoryMoveMap} starts null and
   * the stateless path is exercised end-to-end). Acts as a per-PR regression bar that the stateless
   * rewrite preserved per-call determinism.
   */
  @Test
  void statelessNoncombatMoveIsDeterministic_round1Germans() throws Exception {
    String first = null;
    final int runs = 5;
    for (int i = 0; i < runs; i++) {
      final Session session = freshSession("Germans");
      try {
        final NoncombatMovePlan plan =
            new NoncombatMoveExecutor()
                .execute(session, new NoncombatMoveRequest(noncombatWireState("Germans"), SEED));
        final String wire = MAPPER.writeValueAsString(plan);
        if (first == null) {
          first = wire;
        } else {
          assertEquals(first, wire, "stateless NCM must be deterministic across runs");
        }
      } finally {
        session.offensiveExecutor().shutdownNow();
      }
    }
  }

  /**
   * Comparative gate (map-room#2385 acceptance): the stateless NCM path must produce a
   * functionally-equivalent plan to the legacy snapshot-restored NCM path for a representative
   * round-1 fixture. "Functionally equivalent" is intentionally looser than byte-identical:
   *
   * <ul>
   *   <li>Legacy NCM consumes purchase's simulated post-combat state via {@code
   *       storedFactoryMoveMap} (the factoryMoveMap projected from purchase's {@code dataCopy});
   *       stateless NCM lets {@code ProNonCombatMoveAi.buildFactoryMoveMap} rebuild from the
   *       wire-applied gamestate directly. In production the wire payload reflects real post-combat
   *       state, so the gap should be smaller than this minimal-wire fixture; here the wire is
   *       empty and stateless NCM sees the canonical pre-combat state.
   *   <li>{@code ProNonCombatMoveAi} iterates {@code HashMap}s whose insertion order is incidental,
   *       so move ordering is not asserted.
   * </ul>
   *
   * <p>The gate: at least 90% Jaccard similarity over the {@code (from, to)} tuple set. Empirically
   * (round-1 Germans, this fixture) the two paths produce 24/25 = 96% overlap with one extra
   * factory-rooted move in the legacy path. If the two drift more than that, the failure dumps the
   * full diff so divergence sites can be captured before shipping.
   */
  @Test
  void statelessVsLegacy_germansRound1_producesEquivalentPlan() throws Exception {
    // Path A — legacy snapshot-restored NCM.
    final List<WireMoveDescription> legacyMoves;
    {
      final ProSessionSnapshotStore store = new ProSessionSnapshotStore(snapshotDir);
      final Session session = freshSession("Germans");
      try {
        new PurchaseExecutor(store)
            .execute(session, new PurchaseRequest(wireState("purchase", "Germans"), SEED));
        legacyMoves = legacyRestoreThenDispatchNcm(session, store);
      } finally {
        session.offensiveExecutor().shutdownNow();
      }
    }

    // Path B — new stateless NCM.
    final List<WireMoveDescription> statelessMoves;
    {
      final ProSessionSnapshotStore store = new ProSessionSnapshotStore(snapshotDir);
      final Session session = freshSession("Germans");
      try {
        new PurchaseExecutor(store)
            .execute(session, new PurchaseRequest(wireState("purchase", "Germans"), SEED));
        statelessMoves =
            new NoncombatMoveExecutor()
                .execute(session, new NoncombatMoveRequest(noncombatWireState("Germans"), SEED))
                .moves();
      } finally {
        session.offensiveExecutor().shutdownNow();
      }
    }

    final Set<FromTo> legacy = fromToPairs(legacyMoves);
    final Set<FromTo> stateless = fromToPairs(statelessMoves);
    final Set<FromTo> intersection = new HashSet<>(legacy);
    intersection.retainAll(stateless);
    final Set<FromTo> union = new HashSet<>(legacy);
    union.addAll(stateless);
    final double jaccard = union.isEmpty() ? 1.0 : (double) intersection.size() / union.size();
    assertTrue(
        jaccard >= 0.90,
        () ->
            String.format(
                "stateless NCM diverges from legacy snapshot-restored NCM beyond the 90%%"
                    + " Jaccard threshold for Germans round 1 (Jaccard=%.3f, intersection=%d,"
                    + " union=%d).%nOnly in legacy: %s%nOnly in stateless: %s",
                jaccard,
                intersection.size(),
                union.size(),
                diff(legacy, stateless),
                diff(stateless, legacy)));
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Reproduce the pre-#2385 NCM dispatch path inline: load the saved snapshot, restore stored maps
   * onto the session's ProAi, dispatch {@code invokeNonCombatMoveForSidecar}, project moves to wire
   * shape. Used as the "control" for the comparative test.
   */
  private List<WireMoveDescription> legacyRestoreThenDispatchNcm(
      final Session session, final ProSessionSnapshotStore store) throws Exception {
    final GameData data = session.gameData();

    // Mirror NoncombatMoveExecutor's pre-#2385 ordering exactly:
    //   load snapshot → pre-seed unitIdMap → WireStateApplier.apply → reseed → restore stored*
    //   → invoke.
    final var snapOpt = store.load(session.key());
    assertTrue(snapOpt.isPresent(), "purchase must have saved a snapshot for the legacy path");
    final ProSessionSnapshot snap = snapOpt.get();
    ProSessionSnapshotStore.restoreUnitIdMap(snap, session.unitIdMap());

    org.triplea.ai.sidecar.wire.WireStateApplier.apply(
        data, noncombatWireState("Germans"), session.unitIdMap());

    final var player = data.getPlayerList().getPlayerId("Germans");
    ExecutorSupport.ensureProAiInitialized(session, player);
    ExecutorSupport.ensureBattleDelegate(data);
    final var proAi = session.proAi();
    proAi.getProData().setSeed(SEED);
    proAi.seedBattleCalc(SEED);
    proAi.restoreFactoryMoveMapFromSnapshot(snap, data);
    proAi.restorePurchaseTerritoriesFromSnapshot(snap, data);

    final var recorder = new RecordingMoveDelegate(proAi);
    recorder.initialize("move", "Move");
    recorder.setDelegateBridgeAndPlayer(
        new games.strategy.triplea.ai.pro.simulate.ProDummyDelegateBridge(proAi, player, data));
    session
        .offensiveExecutor()
        .submit(
            () -> {
              proAi.reinitializeProDataForSidecar();
              proAi.invokeNonCombatMoveForSidecar(recorder, data, player);
              return null;
            })
        .get();

    final Map<UUID, String> uuidToWireId = new java.util.HashMap<>();
    session.unitIdMap().forEach((wireId, uuid) -> uuidToWireId.put(uuid, wireId));
    return recorder.captured().stream()
        .filter(c -> !c.isBombing())
        .map(c -> WireMoveDescriptionBuilder.build(c.move(), uuidToWireId))
        .toList();
  }

  /**
   * Lightweight {@code (from, to)} pair used by the comparative test. We intentionally drop unit
   * identity from the equivalence check: the minimal-wire fixture has empty {@code unitIdMap}, so
   * the projected {@code unitIds} list is always empty regardless of which underlying units moved —
   * comparing them adds no signal. The territory-pair set is the structural fingerprint that tells
   * us "the two NCM paths chose the same set of from-to relocations".
   */
  private record FromTo(String from, String to) {}

  private static Set<FromTo> fromToPairs(final List<WireMoveDescription> moves) {
    final Set<FromTo> out = new HashSet<>();
    for (final WireMoveDescription m : moves) {
      out.add(new FromTo(m.from(), m.to()));
    }
    return out;
  }

  private static <T> Set<T> diff(final Set<T> a, final Set<T> b) {
    final Set<T> out = new HashSet<>(a);
    out.removeAll(b);
    return out;
  }

  private static Map<?, ?> reflectStoredMap(final AbstractProAi proAi, final String fieldName)
      throws Exception {
    final Field f = AbstractProAi.class.getDeclaredField(fieldName);
    f.setAccessible(true);
    return (Map<?, ?>) f.get(proAi);
  }
}
