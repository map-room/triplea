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
import org.triplea.ai.sidecar.dto.PoliticsPlan;
import org.triplea.ai.sidecar.dto.PoliticsRequest;
import org.triplea.ai.sidecar.dto.PurchaseRequest;
import org.triplea.ai.sidecar.session.ProSessionSnapshotStore;
import org.triplea.ai.sidecar.session.Session;
import org.triplea.ai.sidecar.session.SessionKey;
import org.triplea.ai.sidecar.wire.WireState;

/**
 * Smoke test for {@link PoliticsExecutor}.
 *
 * <p>Verifies the executor wires up correctly: it runs {@code invokePoliticsForSidecar}, captures
 * declarations through {@link PoliticsObserver}, and returns a well-formed {@link PoliticsPlan}
 * with {@code kind="politics"} and a non-null declarations list.
 *
 * <p><b>What this test does NOT verify:</b> that {@code ProPoliticsAi.politicalActions()} actually
 * elects to declare war in any given scripted scenario. That depends on ProAi heuristics (round,
 * power ratios, enemy adjacency, seeded RNG) which are not stable to force from unit-test inputs.
 * The end-to-end behaviour "AI Germany declares on Russia at round ≥ 3 and invades same turn" is
 * instead covered by the 🧑 Manual gate in the PR test plan, with a screenshot of a live
 * multiplayer match.
 */
class PoliticsExecutorTest {

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

  @Test
  void returnsValidPoliticsPlan() {
    final ProSessionSnapshotStore store = new ProSessionSnapshotStore(snapshotDir);
    final Session session = freshSession("Germans");

    // Purchase first to populate storedCombatMoveMap (required for ProAi initialization).
    new PurchaseExecutor(store)
        .execute(
            session,
            new PurchaseRequest(
                new WireState(List.of(), List.of(), 1, "purchase", "Germans", List.of())));

    final PoliticsPlan plan =
        new PoliticsExecutor(store)
            .execute(
                session,
                new PoliticsRequest(
                    new WireState(List.of(), List.of(), 1, "politics", "Germans", List.of())));

    assertThat(plan.kind()).isEqualTo("politics");
    assertThat(plan.declarations()).isNotNull();
  }
}
