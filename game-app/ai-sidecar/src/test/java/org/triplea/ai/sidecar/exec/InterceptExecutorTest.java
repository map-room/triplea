package org.triplea.ai.sidecar.exec;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.settings.ClientSetting;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.dto.InterceptPlan;
import org.triplea.ai.sidecar.dto.InterceptRequest;
import org.triplea.ai.sidecar.dto.InterceptRequest.PendingInterceptor;
import org.triplea.ai.sidecar.session.Session;
import org.triplea.ai.sidecar.session.SessionKey;
import org.triplea.ai.sidecar.wire.WireState;
import org.triplea.ai.sidecar.wire.WireTerritory;
import org.triplea.ai.sidecar.wire.WireUnit;

class InterceptExecutorTest {

  private static CanonicalGameData canonical;

  @BeforeAll
  static void initPrefs() {
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

  // -------------------------------------------------------------------------
  // Test 1: No pending interceptors → immediate empty plan, no ProAi call.
  // -------------------------------------------------------------------------

  @Test
  void emptyPendingInterceptors_returnsEmptyPlan() {
    final Session session = freshSession("Germans");

    final WireState wire =
        new WireState(
            List.of(
                new WireTerritory(
                    "Western Germany",
                    "Germans",
                    List.of(new WireUnit("u-usa-bmr-1", "bomber", 0, 0, 0, "Americans")))),
            List.of(),
            1,
            "combat",
            "Americans",
            List.of());

    final InterceptRequest req =
        new InterceptRequest(
            wire,
            new InterceptRequest.InterceptBattle(
                "battle-1",
                "Western Germany",
                "Americans",
                "Germans",
                List.of() /* no pending interceptors */));

    final InterceptPlan plan = new InterceptExecutor().execute(session, req);
    assertThat(plan).isNotNull();
    assertThat(plan.interceptorIds()).isEmpty();
  }

  // -------------------------------------------------------------------------
  // Test 2: Bombers in territory + fighters as candidates → plan contains only
  //   valid fighter IDs from the candidate set.
  // -------------------------------------------------------------------------

  @Test
  void bombersPresent_fightersAsInterceptors_planContainsValidIds() {
    final Session session = freshSession("Germans");

    final WireState wire =
        new WireState(
            List.of(
                // Western Germany: German-owned territory with two American bombers
                new WireTerritory(
                    "Western Germany",
                    "Germans",
                    List.of(
                        new WireUnit("u-usa-bmr-1", "bomber", 0, 0, 0, "Americans"),
                        new WireUnit("u-usa-bmr-2", "bomber", 0, 0, 0, "Americans"))),
                // Germany: source territory with two German fighters available to intercept
                new WireTerritory(
                    "Germany",
                    "Germans",
                    List.of(
                        new WireUnit("u-ger-ftr-1", "fighter", 0, 0),
                        new WireUnit("u-ger-ftr-2", "fighter", 0, 0)))),
            List.of(),
            1,
            "combat",
            "Americans",
            List.of());

    final InterceptRequest req =
        new InterceptRequest(
            wire,
            new InterceptRequest.InterceptBattle(
                "battle-2",
                "Western Germany",
                "Americans",
                "Germans",
                List.of(
                    new PendingInterceptor("Germany", new WireUnit("u-ger-ftr-1", "fighter", 0, 0)),
                    new PendingInterceptor(
                        "Germany", new WireUnit("u-ger-ftr-2", "fighter", 0, 0)))));

    final InterceptPlan plan = new InterceptExecutor().execute(session, req);
    assertThat(plan).isNotNull();
    // Acceptance criterion: at least one interceptor must be sent when bombers are
    // present and fighters are available. Two bombers vs two fighters always favours
    // interception in TUV terms (fighters are cheaper and have higher combat values).
    assertThat(plan.interceptorIds()).isNotEmpty();
    assertThat(plan.interceptorIds())
        .allMatch(id -> id.equals("u-ger-ftr-1") || id.equals("u-ger-ftr-2"));
  }

  // -------------------------------------------------------------------------
  // Test 3: Unit-ID round-trip — any IDs emitted in the plan must have been
  //   registered in the session id map and be a subset of the candidate IDs.
  //   Also verifies the acceptance criterion: at least one interceptor is sent.
  // -------------------------------------------------------------------------

  @Test
  void unitIdRoundTrip_pickedIdsAreSubsetOfCandidates() {
    final Session session = freshSession("Germans");

    final WireState wire =
        new WireState(
            List.of(
                new WireTerritory(
                    "Western Germany",
                    "Germans",
                    List.of(new WireUnit("u-usa-bmr-RT", "bomber", 0, 0, 0, "Americans"))),
                new WireTerritory(
                    "Germany",
                    "Germans",
                    List.of(
                        new WireUnit("u-ger-ftr-RT1", "fighter", 0, 0),
                        new WireUnit("u-ger-ftr-RT2", "fighter", 0, 0)))),
            List.of(),
            1,
            "combat",
            "Americans",
            List.of());

    final InterceptRequest req =
        new InterceptRequest(
            wire,
            new InterceptRequest.InterceptBattle(
                "battle-rt",
                "Western Germany",
                "Americans",
                "Germans",
                List.of(
                    new PendingInterceptor(
                        "Germany", new WireUnit("u-ger-ftr-RT1", "fighter", 0, 0)),
                    new PendingInterceptor(
                        "Germany", new WireUnit("u-ger-ftr-RT2", "fighter", 0, 0)))));

    final InterceptPlan plan = new InterceptExecutor().execute(session, req);

    // All wire IDs were registered in the session map after WireStateApplier ran.
    assertThat(session.unitIdMap()).containsKeys("u-usa-bmr-RT", "u-ger-ftr-RT1", "u-ger-ftr-RT2");

    // Acceptance criterion: at least one interceptor must be sent (real-state assertion).
    assertThat(plan.interceptorIds()).isNotEmpty();

    // Any IDs the plan emits must be strings we sent in (not UUID.toString()).
    assertThat(plan.interceptorIds())
        .allMatch(id -> id.equals("u-ger-ftr-RT1") || id.equals("u-ger-ftr-RT2"));
  }

  // -------------------------------------------------------------------------
  // Test 4: No attacker units in the territory → empty plan (no-bombers
  //   short-circuit fires before calling ProAi).
  // -------------------------------------------------------------------------

  @Test
  void noBombersInTerritory_returnsEmptyPlan() {
    final Session session = freshSession("Germans");

    // Territory has only German units — no American bombers visible in wire.
    final WireState wire =
        new WireState(
            List.of(
                new WireTerritory(
                    "Western Germany",
                    "Germans",
                    List.of(new WireUnit("u-ger-inf-1", "infantry", 0, 0)))),
            List.of(),
            1,
            "combat",
            "Americans",
            List.of());

    final InterceptRequest req =
        new InterceptRequest(
            wire,
            new InterceptRequest.InterceptBattle(
                "battle-nobombers",
                "Western Germany",
                "Americans",
                "Germans",
                List.of(
                    new PendingInterceptor(
                        "Germany", new WireUnit("u-ger-ftr-nb", "fighter", 0, 0)))));

    final InterceptPlan plan = new InterceptExecutor().execute(session, req);
    assertThat(plan).isNotNull();
    assertThat(plan.interceptorIds()).isEmpty();
  }
}
