package org.triplea.ai.sidecar.exec;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.delegate.battle.BattleTracker;
import games.strategy.triplea.delegate.battle.IBattle;
import games.strategy.triplea.settings.ClientSetting;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.dto.ScramblePlan;
import org.triplea.ai.sidecar.dto.ScrambleRequest;
import org.triplea.ai.sidecar.session.Session;
import org.triplea.ai.sidecar.session.SessionKey;
import org.triplea.ai.sidecar.wire.WireState;
import org.triplea.ai.sidecar.wire.WireTerritory;
import org.triplea.ai.sidecar.wire.WireUnit;

class ScrambleExecutorTest {

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
  // Test 1: Single source with airbase + fighters → ProAi may or may not
  //   scramble depending on odds, but if it does the plan must only contain
  //   fighter IDs for that source territory and at most the live cap.
  // ------------------------------------------------------------------------

  @Test
  void singleSource_fighterPlanKeyedBySourceTerritory() {
    final Session session = freshSession("Germans");

    final WireState wire =
        new WireState(
            List.of(
                // Defending sea zone: a couple of attacking enemy transports/units so the
                // synthetic battle is non-trivial. We pre-seed British surface units to give
                // ProScrambleAi something to compute odds against.
                new WireTerritory(
                    "112 Sea Zone",
                    "Germans",
                    List.of(
                        new WireUnit("u-uk-dd-1", "destroyer", 0, 0),
                        new WireUnit("u-uk-dd-2", "destroyer", 0, 0))),
                new WireTerritory(
                    "Western Germany",
                    "Germans",
                    List.of(
                        new WireUnit("u-ger-airfield", "airfield", 0, 0),
                        new WireUnit("u-ger-ftr-1", "fighter", 0, 0),
                        new WireUnit("u-ger-ftr-2", "fighter", 0, 0)))),
            List.of(),
            1,
            "combat",
            "Germans");

    final ScrambleRequest req =
        new ScrambleRequest(
            wire,
            new ScrambleRequest.ScrambleBattle(
                "112 Sea Zone",
                Map.of(
                    "Western Germany",
                    new ScrambleRequest.ScrambleSource(
                        1,
                        List.of(
                            new WireUnit("u-ger-ftr-1", "fighter", 0, 0),
                            new WireUnit("u-ger-ftr-2", "fighter", 0, 0))))));

    final ScramblePlan plan = new ScrambleExecutor().execute(session, req);

    assertThat(plan).isNotNull();
    assertThat(plan.scramblers()).isNotNull();
    if (!plan.scramblers().isEmpty()) {
      assertThat(plan.scramblers()).containsOnlyKeys("Western Germany");
      assertThat(plan.scramblers().get("Western Germany"))
          .isSubsetOf("u-ger-ftr-1", "u-ger-ftr-2");
    }
    assertThat(pendingBattles(session)).isEmpty();
  }

  // ------------------------------------------------------------------------
  // Test 2: Multiple source territories — two different airbases contributing
  //   fighters. Plan must respect per-source keying; any emitted IDs must
  //   belong to the source they're listed under.
  // ------------------------------------------------------------------------

  @Test
  void multipleSources_idsKeyedByCorrectSource() {
    final Session session = freshSession("Germans");

    final WireState wire =
        new WireState(
            List.of(
                new WireTerritory(
                    "112 Sea Zone",
                    "Germans",
                    List.of(new WireUnit("u-uk-dd-A", "destroyer", 0, 0))),
                new WireTerritory(
                    "Western Germany",
                    "Germans",
                    List.of(
                        new WireUnit("u-ger-airfield-w", "airfield", 0, 0),
                        new WireUnit("u-ger-ftr-w", "fighter", 0, 0))),
                new WireTerritory(
                    "Germany",
                    "Germans",
                    List.of(
                        new WireUnit("u-ger-airfield-g", "airfield", 0, 0),
                        new WireUnit("u-ger-ftr-g", "fighter", 0, 0)))),
            List.of(),
            1,
            "combat",
            "Germans");

    final ScrambleRequest req =
        new ScrambleRequest(
            wire,
            new ScrambleRequest.ScrambleBattle(
                "112 Sea Zone",
                Map.of(
                    "Western Germany",
                    new ScrambleRequest.ScrambleSource(
                        1, List.of(new WireUnit("u-ger-ftr-w", "fighter", 0, 0))),
                    "Germany",
                    new ScrambleRequest.ScrambleSource(
                        1, List.of(new WireUnit("u-ger-ftr-g", "fighter", 0, 0))))));

    final ScramblePlan plan = new ScrambleExecutor().execute(session, req);

    assertThat(plan).isNotNull();
    for (final Map.Entry<String, List<String>> e : plan.scramblers().entrySet()) {
      assertThat(e.getKey()).isIn("Western Germany", "Germany");
      if ("Western Germany".equals(e.getKey())) {
        assertThat(e.getValue()).containsOnly("u-ger-ftr-w");
      } else {
        assertThat(e.getValue()).containsOnly("u-ger-ftr-g");
      }
    }
    assertThat(pendingBattles(session)).isEmpty();
  }

  // ------------------------------------------------------------------------
  // Test 3: Empty possibleScramblers → short-circuit to empty plan without
  //   touching ProAi or the BattleTracker.
  // ------------------------------------------------------------------------

  @Test
  void emptyPossibleScramblers_returnsEmptyPlan() {
    final Session session = freshSession("Germans");

    final WireState wire =
        new WireState(
            List.of(
                new WireTerritory(
                    "112 Sea Zone",
                    "Germans",
                    List.of(new WireUnit("u-uk-dd-X", "destroyer", 0, 0)))),
            List.of(),
            1,
            "combat",
            "Germans");

    final ScrambleRequest req =
        new ScrambleRequest(
            wire, new ScrambleRequest.ScrambleBattle("112 Sea Zone", Map.of()));

    final ScramblePlan plan = new ScrambleExecutor().execute(session, req);
    assertThat(plan).isNotNull();
    assertThat(plan.scramblers()).isEmpty();
    // Short-circuit path never lazily attaches the battle delegate, so there is no tracker to
    // inspect — the important invariant is just that execute() returns a clean empty plan.
  }

  // ------------------------------------------------------------------------
  // Test 4: Unit-id round-trip — IDs sent in must be registered in the
  //   session id map and, if the plan comes back non-empty, must round-trip
  //   to their original Map Room string IDs.
  // ------------------------------------------------------------------------

  @Test
  void unitIdRoundTrip_preservesMapRoomIds() {
    final Session session = freshSession("Germans");

    final WireState wire =
        new WireState(
            List.of(
                new WireTerritory(
                    "112 Sea Zone",
                    "Germans",
                    List.of(new WireUnit("u-uk-dd-R", "destroyer", 0, 0))),
                new WireTerritory(
                    "Western Germany",
                    "Germans",
                    List.of(
                        new WireUnit("u-ger-airfield-R", "airfield", 0, 0),
                        new WireUnit("u-ger-ftr-R1", "fighter", 0, 0),
                        new WireUnit("u-ger-ftr-R2", "fighter", 0, 0)))),
            List.of(),
            1,
            "combat",
            "Germans");

    final ScrambleRequest req =
        new ScrambleRequest(
            wire,
            new ScrambleRequest.ScrambleBattle(
                "112 Sea Zone",
                Map.of(
                    "Western Germany",
                    new ScrambleRequest.ScrambleSource(
                        2,
                        List.of(
                            new WireUnit("u-ger-ftr-R1", "fighter", 0, 0),
                            new WireUnit("u-ger-ftr-R2", "fighter", 0, 0))))));

    final ScramblePlan plan = new ScrambleExecutor().execute(session, req);
    assertThat(plan).isNotNull();

    // Unit ids from the wire were registered in the session map.
    assertThat(session.unitIdMap())
        .containsKeys("u-ger-ftr-R1", "u-ger-ftr-R2", "u-ger-airfield-R", "u-uk-dd-R");

    // Any ids the plan emits must be the same strings we sent in (not UUID.toString()).
    for (final List<String> ids : plan.scramblers().values()) {
      assertThat(ids).allMatch(id -> id.equals("u-ger-ftr-R1") || id.equals("u-ger-ftr-R2"));
    }
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
