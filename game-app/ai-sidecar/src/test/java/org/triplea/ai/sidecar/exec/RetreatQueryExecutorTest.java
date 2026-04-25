package org.triplea.ai.sidecar.exec;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.delegate.battle.BattleTracker;
import games.strategy.triplea.delegate.battle.IBattle;
import games.strategy.triplea.settings.ClientSetting;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.dto.RetreatPlan;
import org.triplea.ai.sidecar.dto.RetreatQueryRequest;
import org.triplea.ai.sidecar.session.Session;
import org.triplea.ai.sidecar.session.SessionKey;
import org.triplea.ai.sidecar.wire.WireState;
import org.triplea.ai.sidecar.wire.WireTerritory;
import org.triplea.ai.sidecar.wire.WireUnit;

class RetreatQueryExecutorTest {

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
        new SessionKey("g1", nation),
        42L,
        proAi,
        data,
        new ConcurrentHashMap<>(),
        Executors.newSingleThreadExecutor());
  }

  // ------------------------------------------------------------------------
  // Test 1: Attacker retreat — Germans stack on Poland with Russians defending,
  //         legal retreat back to Germany or Slovakia Hungary. ProAi must
  //         either pick one of them or return null (press).
  // ------------------------------------------------------------------------

  @Test
  void attackerRetreat_returnsOneOfPossibleOrNull() {
    final Session session = freshSession("Germans");

    // Germans-owned battle site on the European front. Every unit on the wire territory is
    // owned by the wire owner (Germans), so the executor will treat all of them as the
    // attacking stack and fall back to a non-session player for the synthetic defender — the
    // GamePlayer identity is not load-bearing in ProRetreatAi, only attacker/unit counts are.
    final WireState wire =
        new WireState(
            List.of(
                new WireTerritory(
                    "Poland",
                    "Germans",
                    List.of(
                        new WireUnit("u-ger-inf-1", "infantry", 0, 0),
                        new WireUnit("u-ger-inf-2", "infantry", 0, 0),
                        new WireUnit("u-ger-art-1", "artillery", 0, 0),
                        new WireUnit("u-ger-tank-1", "armour", 0, 0)))),
            List.of(),
            1,
            "combat",
            "Germans",
            List.of());

    final RetreatQueryRequest req =
        new RetreatQueryRequest(
            wire,
            new RetreatQueryRequest.RetreatQueryBattle(
                "b-retreat-1",
                "Poland",
                /* canSubmerge */ false,
                List.of("Germany", "Slovakia Hungary")));

    final RetreatPlan plan = new RetreatQueryExecutor().execute(session, req);

    assertThat(plan).isNotNull();
    if (plan.retreatTo() != null) {
      assertThat(plan.retreatTo()).isIn("Germany", "Slovakia Hungary");
    }
    assertThat(pendingBattles(session)).isEmpty();
  }

  // ------------------------------------------------------------------------
  // Test 2: No possible retreat territories — ProAi is not even invoked;
  //         executor short-circuits to null.
  // ------------------------------------------------------------------------

  @Test
  void emptyPossibleRetreats_returnsNullWithoutException() {
    final Session session = freshSession("Germans");

    final WireState wire =
        new WireState(
            List.of(
                new WireTerritory(
                    "Poland", "Germans", List.of(new WireUnit("u-ger-inf-1", "infantry", 0, 0)))),
            List.of(),
            1,
            "combat",
            "Germans",
            List.of());

    final RetreatQueryRequest req =
        new RetreatQueryRequest(
            wire,
            new RetreatQueryRequest.RetreatQueryBattle(
                "b-retreat-empty", "Poland", false, List.of()));

    final RetreatPlan plan = new RetreatQueryExecutor().execute(session, req);
    assertThat(plan).isNotNull();
    assertThat(plan.retreatTo()).isNull();
    // Short-circuit path does not even touch the BattleTracker — no cleanup to assert.
  }

  // ------------------------------------------------------------------------
  // Test 3: Unit-id stability — placing units via WireState and then invoking
  //         the executor must not fail on unit lookups, and the ids in the
  //         session map must round-trip with resolved live Unit instances.
  // ------------------------------------------------------------------------

  @Test
  void unitIdRoundTrip_resolvesLiveInstancesOnSite() {
    final Session session = freshSession("Germans");

    final WireState wire =
        new WireState(
            List.of(
                new WireTerritory(
                    "Poland",
                    "Germans",
                    List.of(
                        new WireUnit("u-ger-inf-A", "infantry", 0, 0),
                        new WireUnit("u-ger-art-A", "artillery", 0, 0)))),
            List.of(),
            1,
            "combat",
            "Germans",
            List.of());

    final RetreatQueryRequest req =
        new RetreatQueryRequest(
            wire,
            new RetreatQueryRequest.RetreatQueryBattle(
                "b-retreat-stable", "Poland", false, List.of("Germany")));

    final RetreatPlan plan = new RetreatQueryExecutor().execute(session, req);
    assertThat(plan).isNotNull();
    if (plan.retreatTo() != null) {
      assertThat(plan.retreatTo()).isEqualTo("Germany");
    }

    // Unit ids from the wire were registered in the session id map and resolved to live
    // Unit instances in the cloned GameData.
    assertThat(session.unitIdMap()).containsKeys("u-ger-inf-A", "u-ger-art-A");
    final UUID aId = session.unitIdMap().get("u-ger-inf-A");
    assertThat(
            session.gameData().getMap().getTerritoryOrNull("Poland").getUnits().stream()
                .anyMatch(u -> u.getId().equals(aId)))
        .isTrue();
  }

  // ------------------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------------------

  @SuppressWarnings("unchecked")
  private static Set<IBattle> pendingBattles(final Session session) {
    try {
      final BattleTracker tracker = session.gameData().getBattleDelegate().getBattleTracker();
      final Field f = BattleTracker.class.getDeclaredField("pendingBattles");
      f.setAccessible(true);
      return (Set<IBattle>) f.get(tracker);
    } catch (final ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }
}
