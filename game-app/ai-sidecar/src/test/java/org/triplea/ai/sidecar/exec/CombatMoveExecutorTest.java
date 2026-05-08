package org.triplea.ai.sidecar.exec;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Integration tests for {@link CombatMoveExecutor}.
 *
 * <p>Under the Option X pivot (map-room#1824), politics runs in a separate {@code politics}
 * decision kind ({@link PoliticsExecutor}), not inside {@code CombatMoveExecutor}. The bot sends a
 * fresh post-declaration WireState in the subsequent {@code combat-move} request. These tests
 * verify that combat-move planning runs correctly when given a WireState that already reflects
 * post-politics relationships.
 */
class CombatMoveExecutorTest {

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

  /**
   * Smoke test: combat-move executor runs end-to-end and produces moves.
   *
   * <p>Under Option X, politics runs in PoliticsExecutor. The WireState sent to CombatMoveExecutor
   * already reflects post-declaration relationships. This test verifies the executor completes
   * successfully and returns a non-null plan with non-empty moves.
   */
  @Test
  void runsCombatMoveAndReturnsMoves() {
    final ProSessionSnapshotStore store = new ProSessionSnapshotStore(snapshotDir);
    final Session session = freshSession("Germans");

    // Round 1 purchase populates storedCombatMoveMap (required before combat-move executor runs).
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

    assertThat(plan.moves())
        .as("Combat-move must produce at least some moves (end-to-end pipeline check)")
        .isNotEmpty();
    assertThat(plan.sbrMoves()).isNotNull();
  }
}
