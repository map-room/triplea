package org.triplea.ai.sidecar.exec;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.settings.ClientSetting;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.dto.PoliticsPlan;
import org.triplea.ai.sidecar.dto.PoliticsRequest;
import org.triplea.ai.sidecar.dto.PurchaseRequest;
import org.triplea.ai.sidecar.session.ProSessionSnapshotStore;
import org.triplea.ai.sidecar.session.Session;
import org.triplea.ai.sidecar.session.SessionKey;
import org.triplea.ai.sidecar.wire.WireRelationship;
import org.triplea.ai.sidecar.wire.WireState;

import java.util.List;

/**
 * Integration test for {@link PoliticsExecutor}.
 *
 * <p>Verifies that PoliticsExecutor correctly runs {@code invokePoliticsForSidecar} and captures
 * war declarations via {@link PoliticsObserver}.
 *
 * <h2>Deterministic war-declaration setup</h2>
 *
 * <p>At round ≥ 21, {@code ProPoliticsAi.politicalActions()} has {@code roundFactor ≥ 1.0},
 * making {@code warChance ≥ 1.0}. With Americans and Chinese pre-set at war with Germany,
 * Russia is the only remaining valid enemy war target — so Germany ALWAYS declares war on
 * Russia at round 21.
 */
class PoliticsExecutorTest {

  @TempDir Path snapshotDir;

  private static CanonicalGameData canonical;

  /** Round at which warChance >= 1.0 (roundFactor = (21-1)*0.05 = 1.0). */
  private static final int DETERMINISTIC_WAR_ROUND = 21;

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

  /**
   * Core test: PoliticsExecutor returns a PoliticsPlan with kind="politics" and a non-null
   * declarations list.
   *
   * <p>Minimum assertion: executor completes without error and returns a valid plan.
   * When the deterministic round is used with Americans/Chinese pre-set to war, Germany
   * always declares war on Russia — asserted in the stronger test below.
   */
  @Test
  void returnsValidPoliticsPlan() {
    final ProSessionSnapshotStore store = new ProSessionSnapshotStore(snapshotDir);
    final Session session = freshSession("Germans");

    // Purchase first to populate storedCombatMoveMap (required for ProAi initialization).
    new PurchaseExecutor(store).execute(
        session,
        new PurchaseRequest(new WireState(List.of(), List.of(), 1, "purchase", "Germans",
            List.of())));

    final PoliticsPlan plan = new PoliticsExecutor(store).execute(
        session,
        new PoliticsRequest(new WireState(List.of(), List.of(), 1, "politics", "Germans",
            List.of())));

    assertThat(plan.kind()).isEqualTo("politics");
    assertThat(plan.declarations()).isNotNull();
  }

  /**
   * Deterministic test: at round 21 with Americans and Chinese pre-set at war with Germany,
   * Germany ALWAYS declares war on Russia (warChance >= 1.0 guarantees it; only one target left).
   */
  @Test
  void declaresWarOnRussiaAtDeterministicRound() {
    final ProSessionSnapshotStore store = new ProSessionSnapshotStore(snapshotDir);
    final Session session = freshSession("Germans");

    // Purchase at round 1 to initialize ProAi state.
    new PurchaseExecutor(store).execute(
        session,
        new PurchaseRequest(new WireState(List.of(), List.of(), 1, "purchase", "Germans",
            List.of())));

    // Pre-set Germany at war with Americans and Chinese.
    // At round 21, with only Russia as a valid enemy war target, Germany always declares war.
    final List<WireRelationship> preWarRelationships = List.of(
        new WireRelationship("Americans", "Germans", "war"),
        new WireRelationship("Chinese", "Germans", "war"));

    // Use "combatMove" phase so WireStateApplier.applyRoundAndStep can advance the sequence
    // to round 21. "politics" is not in StepNameMapper, so it would be silently skipped,
    // leaving round at 1 where warChance is < 1.0. PoliticsExecutor does not use the
    // phase value for any logic — only the round matters for the war-probability check.
    final PoliticsPlan plan = new PoliticsExecutor(store).execute(
        session,
        new PoliticsRequest(new WireState(List.of(), List.of(), DETERMINISTIC_WAR_ROUND,
            "combatMove", "Germans", preWarRelationships)));

    assertThat(plan.declarations())
        .as("Germany must declare war on Russia at round " + DETERMINISTIC_WAR_ROUND
            + " when warChance >= 1.0 and Russia is the only valid enemy target")
        .extracting(d -> d.target())
        .contains("Russians");
  }
}
