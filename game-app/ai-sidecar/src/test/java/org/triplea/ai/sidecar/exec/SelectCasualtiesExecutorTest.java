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
import org.triplea.ai.sidecar.dto.SelectCasualtiesPlan;
import org.triplea.ai.sidecar.dto.SelectCasualtiesRequest;
import org.triplea.ai.sidecar.session.Session;
import org.triplea.ai.sidecar.session.SessionKey;
import org.triplea.ai.sidecar.wire.WireState;
import org.triplea.ai.sidecar.wire.WireTerritory;
import org.triplea.ai.sidecar.wire.WireUnit;

class SelectCasualtiesExecutorTest {

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

  private static WireState wireStateWithGermanStack(final List<WireUnit> units) {
    return new WireState(
        List.of(new WireTerritory("Germany", "Germans", units)),
        List.of(),
        1,
        "combat",
        "Germans",
        List.of());
  }

  // ------------------------------------------------------------------------
  // Test 1: mixed-unit stack, hitCount < selectFrom.size(). Returns a
  //         plausible casualty set that ProAi actually picked.
  // ------------------------------------------------------------------------

  @Test
  void mixedStack_returnsCheapestUnitsWhenEnemiesPresent() {
    final Session session = freshSession("Germans");

    final List<WireUnit> stack =
        List.of(
            new WireUnit("u-inf-1", "infantry", 0, 0),
            new WireUnit("u-art-1", "artillery", 0, 0),
            new WireUnit("u-tank-1", "armour", 0, 0));

    // Place a single enemy infantry on Germany so the wire state references at least one
    // "enemy" unit resolvable from the territory — we pass it to the executor as an enemy,
    // which drives ProAi down the bubble-sort (needToCheck) branch that consults the battle.
    final List<WireUnit> withEnemy =
        List.of(
            new WireUnit("u-inf-1", "infantry", 0, 0),
            new WireUnit("u-art-1", "artillery", 0, 0),
            new WireUnit("u-tank-1", "armour", 0, 0),
            new WireUnit("u-enemy-inf-1", "infantry", 0, 0));

    final SelectCasualtiesRequest req =
        new SelectCasualtiesRequest(
            wireStateWithGermanStack(withEnemy),
            new SelectCasualtiesRequest.SelectCasualtiesBattle(
                "b1",
                "Germany",
                /* attackerNation */ "Russians",
                /* defenderNation */ "Germans",
                /* hitCount */ 2,
                stack,
                /* friendlyUnits */ stack,
                /* enemyUnits */ List.of(new WireUnit("u-enemy-inf-1", "infantry", 0, 0)),
                /* isAmphibious */ false,
                /* amphibiousLandAttackers */ List.of(),
                /* defaultCasualties */ List.of("u-inf-1", "u-art-1"),
                /* allowMultipleHitsPerUnit */ false));

    final SelectCasualtiesPlan plan = new SelectCasualtiesExecutor().execute(session, req);

    assertThat(plan.killed()).hasSize(2);
    // Every killed id must be one of the originally-selectable ids.
    assertThat(plan.killed())
        .allSatisfy(id -> assertThat(List.of("u-inf-1", "u-art-1", "u-tank-1")).contains(id));
    // Infantry is cheapest (3) and artillery is next (4), so the bubble-sort-on-cost path
    // should surface those two before the armour (6).
    assertThat(plan.killed()).containsExactlyInAnyOrder("u-inf-1", "u-art-1");

    // Nothing should remain in the tracker after the call.
    assertThat(pendingBattles(session)).isEmpty();
  }

  // ------------------------------------------------------------------------
  // Test 2: Unit-id round-trip. Request uses Map Room string ids, plan
  //         returns the same string ids, not Java UUIDs.
  // ------------------------------------------------------------------------

  @Test
  void unitIdRoundTrip_returnsMapRoomIdsNotUuids() {
    final Session session = freshSession("Germans");

    final List<WireUnit> stack =
        List.of(
            new WireUnit("german-inf-A", "infantry", 0, 0),
            new WireUnit("german-inf-B", "infantry", 0, 0));

    final SelectCasualtiesRequest req =
        new SelectCasualtiesRequest(
            wireStateWithGermanStack(stack),
            new SelectCasualtiesRequest.SelectCasualtiesBattle(
                "b2",
                "Germany",
                "Russians",
                "Germans",
                1,
                stack,
                stack,
                /* enemyUnits */ List.of(),
                false,
                List.of(),
                List.of("german-inf-A"),
                false));

    final SelectCasualtiesPlan plan = new SelectCasualtiesExecutor().execute(session, req);

    assertThat(plan.killed()).hasSize(1);
    final String id = plan.killed().get(0);
    // Must be a Map Room id, not a UUID string.
    assertThat(id).isIn("german-inf-A", "german-inf-B");
    assertThat(id).doesNotMatch("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  }

  // ------------------------------------------------------------------------
  // Test 3: allowMultipleHitsPerUnit=true with a battleship. Verifies the
  //         executor tolerates the multi-hit flag and that damaged defaults
  //         flow through without error.
  // ------------------------------------------------------------------------

  @Test
  void multiHitBattleship_executesWithoutErrorAndReturnsValidIds() {
    final Session session = freshSession("Germans");

    final WireState wire =
        new WireState(
            List.of(
                new WireTerritory(
                    "112 Sea Zone",
                    "Germans",
                    List.of(
                        new WireUnit("u-bb-1", "battleship", 0, 0),
                        new WireUnit("u-cr-1", "cruiser", 0, 0)))),
            List.of(),
            1,
            "combat",
            "Germans",
            List.of());

    final List<WireUnit> selectFrom =
        List.of(
            new WireUnit("u-bb-1", "battleship", 0, 0), new WireUnit("u-cr-1", "cruiser", 0, 0));

    final SelectCasualtiesRequest req =
        new SelectCasualtiesRequest(
            wire,
            new SelectCasualtiesRequest.SelectCasualtiesBattle(
                "b3",
                "112 Sea Zone",
                "British",
                "Germans",
                /* hitCount */ 1,
                selectFrom,
                /* friendlyUnits */ selectFrom,
                /* enemyUnits */ List.of(),
                false,
                List.of(),
                /* defaultCasualties */ List.of("u-cr-1"),
                /* allowMultipleHitsPerUnit */ true));

    final SelectCasualtiesPlan plan = new SelectCasualtiesExecutor().execute(session, req);

    assertThat(plan.killed()).hasSize(1);
    // The cheaper unit (cruiser) should be surfaced first by the cost sort.
    assertThat(plan.killed().get(0)).isEqualTo("u-cr-1");
    assertThat(plan.damaged()).isEmpty();
  }

  // ------------------------------------------------------------------------
  // Test 4 (Fix 3a regression): defaultCasualties.size() != hitCount must
  // throw IllegalArgumentException (→ 400).
  //
  // AbstractProAi.selectCasualties (AbstractProAi.java:390) enforces
  // defaultCasualties.size() == hitCount. The upstream Map Room builder at
  // decision-detectors.ts:120 reads battle.autoDefenseCasualties, which is
  // only populated in the auto-profile path (battle-steps.ts:432,739) where
  // autoCasualtiesWithProfile produces exactly hitCount selections. Any
  // mismatch is therefore a protocol violation that we reject early at the
  // sidecar boundary with a clear 400 rather than an opaque 500 from ProAi.
  // ------------------------------------------------------------------------

  @Test
  void defaultCasualtiesSizeMismatch_throwsIllegalArgument() {
    final Session session = freshSession("Germans");

    final List<WireUnit> stack =
        List.of(
            new WireUnit("u-inf-1", "infantry", 0, 0), new WireUnit("u-inf-2", "infantry", 0, 0));

    // hitCount=2 but only 1 defaultCasualty — protocol violation.
    final SelectCasualtiesRequest req =
        new SelectCasualtiesRequest(
            wireStateWithGermanStack(stack),
            new SelectCasualtiesRequest.SelectCasualtiesBattle(
                "b4",
                "Germany",
                "Russians",
                "Germans",
                /* hitCount */ 2,
                stack,
                /* friendlyUnits */ stack,
                /* enemyUnits */ List.of(),
                false,
                List.of(),
                /* defaultCasualties — one less than hitCount */ List.of("u-inf-1"),
                false));

    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> new SelectCasualtiesExecutor().execute(session, req));
  }

  // ------------------------------------------------------------------------
  // Helper: snoop BattleTracker.pendingBattles via reflection to assert the
  // executor cleans up after itself.
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
