package org.triplea.ai.sidecar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.settings.ClientSetting;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

class CanonicalGameDataTest {

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
  }

  @Test
  void loadsGlobal1940() {
    final CanonicalGameData canonical = CanonicalGameData.load();
    final GameData data = canonical.template();
    assertNotNull(data);
    assertNotNull(data.getPlayerList().getPlayerId("Germans"));
  }

  /**
   * Decisive isolation probe: two concurrent cloneForSession() calls must produce completely
   * disjoint object graphs. Specifically, Unit instances (which carry the mutable {@code submerged}
   * field that WireStateVerifier checks) must be different Java objects, and mutating one must not
   * bleed into the other.
   *
   * <p>This rules out Hypothesis 2 (clone leaks isolation via postDeSerialize global registration
   * or shared interned/transient objects). If this test passes, the "apply-drift" log seen in CI
   * near the ConcurrencySmokeTest failure is from WireStateVerifierTest (intentional drift test),
   * not from cross-thread contamination in ConcurrencySmokeTest.
   */
  @Test
  void concurrentClones_unitGraphsAreFullyDisjoint_submergedMutationDoesNotBleed()
      throws Exception {
    final CanonicalGameData canonical = CanonicalGameData.load();

    // Produce two clones concurrently (same pattern as the sidecar thread pool).
    final CompletableFuture<GameData> futA =
        CompletableFuture.supplyAsync(canonical::cloneForSession);
    final CompletableFuture<GameData> futB =
        CompletableFuture.supplyAsync(canonical::cloneForSession);
    final GameData a = futA.get();
    final GameData b = futB.get();

    // Germany always has units in Global 1940 — pick the first one.
    final Unit aUnit = a.getMap().getTerritoryOrThrow("Germany").getUnits().iterator().next();
    final Unit bUnit = b.getMap().getTerritoryOrThrow("Germany").getUnits().iterator().next();

    // Hypothesis 2 check: different Java instances (not the same interned/cached object).
    assertNotSame(aUnit, bUnit, "Unit instances from concurrent clones must be distinct objects");

    // Hypothesis 1 / 2 check: mutating submerged on clone A must not affect clone B.
    assertFalse(aUnit.getSubmerged(), "precondition: aUnit starts not submerged");
    assertFalse(bUnit.getSubmerged(), "precondition: bUnit starts not submerged");
    aUnit.setSubmerged(true);
    assertFalse(
        bUnit.getSubmerged(),
        "setSubmerged(true) on clone A's unit bled into clone B — clones are NOT isolated");
  }

  @Test
  void cloneIsIndependent() {
    final CanonicalGameData canonical = CanonicalGameData.load();
    final GameData a = canonical.cloneForSession();
    final GameData b = canonical.cloneForSession();
    assertNotSame(a, b);
    // PU mutation on A must not leak into B
    a.getPlayerList()
        .getPlayerId("Germans")
        .getResources()
        .addResource(a.getResourceList().getResourceOrThrow("PUs"), 100);
    final int bPus = b.getPlayerList().getPlayerId("Germans").getResources().getQuantity("PUs");
    assertEquals(30, bPus);
  }
}
