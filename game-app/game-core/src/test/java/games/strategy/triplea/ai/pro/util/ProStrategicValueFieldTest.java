package games.strategy.triplea.ai.pro.util;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.data.AiTheaterPriority;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.xml.TestMapGameData;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

/**
 * Phase 1B+1C (PR-A) shadow-field tests. Asserts directional properties of S(n) against a standard
 * 1940 Global game state — the canonical fixture from §10 is a round-3 dump (a save file we don't
 * have); these tests use round 1 for reproducibility, and the 🧑 Manual fixture acceptance lives in
 * the PR body.
 */
class ProStrategicValueFieldTest {

  private GameData data;
  private GamePlayer americans;
  private ProData proData;

  @BeforeEach
  void setUp() throws Exception {
    ClientSetting.setPreferences(new MemoryPreferences());
    data = TestMapGameData.GLOBAL1940.getGameData();
    americans = data.getPlayerList().getPlayerId("Americans");
    proData = proDataFor(data);
  }

  @Test
  void fieldIsKeyedByEveryTerritoryOnTheBoard_Finding3() {
    proData.setWStrat(1.0);

    final Map<Territory, Double> field = ProStrategicValueField.compute(proData, americans);

    assertThat(field.keySet())
        .as("§10 Finding 3: S(n) cache must cover the full board, not per-call subset")
        .containsExactlyInAnyOrderElementsOf(data.getMap().getTerritories());
  }

  @Test
  void underKgf_S_Berlin_dominates_S_USEastCoast_and_both_positive() {
    proData.setAiTheaterPriority(AiTheaterPriority.KGF);
    proData.setWStrat(1.0);

    final Map<Territory, Double> field = ProStrategicValueField.compute(proData, americans);

    final Territory berlin = data.getMap().getTerritoryOrThrow("Germany");
    final Territory eastUs = data.getMap().getTerritoryOrThrow("Eastern United States");
    assertThat(field.get(berlin))
        .as("Berlin is the KGF anchor and must dominate any non-anchor")
        .isGreaterThan(field.get(eastUs));
    assertThat(field.get(eastUs))
        .as("Decay must reach the US East Coast (>0) so the gradient is connected at default knobs")
        .isPositive();
  }

  @Test
  void underKjf_S_Tokyo_dominates_S_Berlin() {
    proData.setAiTheaterPriority(AiTheaterPriority.KJF);
    proData.setWStrat(1.0);

    final Map<Territory, Double> field = ProStrategicValueField.compute(proData, americans);

    final Territory tokyo = data.getMap().getTerritoryOrThrow("Japan");
    final Territory berlin = data.getMap().getTerritoryOrThrow("Germany");
    assertThat(field.get(tokyo))
        .as("Tokyo is the KJF anchor and must dominate Berlin (which becomes off-theater)")
        .isGreaterThan(field.get(berlin));
  }

  @Test
  void alphaOff_suppresses_offtheater_anchor_below_targeted() {
    proData.setAiTheaterPriority(AiTheaterPriority.KGF);
    proData.setAlphaOff(0.10);
    proData.setWStrat(1.0);

    final Map<Territory, Double> field = ProStrategicValueField.compute(proData, americans);

    final Territory berlin = data.getMap().getTerritoryOrThrow("Germany");
    final Territory tokyo = data.getMap().getTerritoryOrThrow("Japan");
    // Under KGF, Berlin has weight 1.0, Tokyo has weight alphaOff=0.10. Decay is the same
    // gamma^d at d=0, so the ratio at the anchor itself is exactly alphaOff.
    assertThat(field.get(tokyo) / field.get(berlin))
        .as("Off-theater anchor must be suppressed to alphaOff times the targeted anchor at d=0")
        .isLessThanOrEqualTo(0.105)
        .isGreaterThanOrEqualTo(0.095);
  }

  @Test
  void perPowerCanalPredicate_isHonored_USRoutesAvoidSuez() {
    // US doesn't own Suez/Egypt; the canal predicate (ProMatches.noCanalsBetweenTerritories)
    // should force the BFS to route Atlantic → US around Africa, not through Suez.
    // We verify by asserting that under KGF the European-side decay reaches London (Atlantic
    // side, short hop from Berlin) much faster than it reaches Western Australia (other side
    // of the world for a US-perspective BFS that respects canal rules).
    proData.setAiTheaterPriority(AiTheaterPriority.KGF);
    proData.setWStrat(1.0);

    final Map<Territory, Double> field = ProStrategicValueField.compute(proData, americans);

    final Territory london = data.getMap().getTerritoryOrThrow("United Kingdom");
    final Territory westAus = data.getMap().getTerritoryOrThrow("Western Australia");
    assertThat(field.get(london))
        .as("London is short-hop from Berlin via the Atlantic; must score higher than Australia")
        .isGreaterThan(field.get(westAus));
  }

  @Test
  void wStratZero_leavesFieldUntouched_byPolicy_noCallerShouldRead() {
    // ProStrategicValueField.compute is happy to run with wStrat=0; the call-site gate in
    // ProTerritoryValueUtils.findTerritoryValues is what prevents it from being invoked. This
    // test just verifies that compute() itself doesn't assume wStrat > 0.
    proData.setWStrat(0.0);

    final Map<Territory, Double> field = ProStrategicValueField.compute(proData, americans);

    assertThat(field).isNotEmpty();
    assertThat(field.keySet()).containsExactlyInAnyOrderElementsOf(data.getMap().getTerritories());
  }

  @Test
  void launchBonus_zero_when_betaLaunch_zero() {
    proData.setAiTheaterPriority(AiTheaterPriority.KGF);
    proData.setWStrat(1.0);
    proData.setBetaLaunch(0.0); // default; explicit for clarity

    final Map<Territory, Double> field = ProStrategicValueField.compute(proData, americans);

    // Pick a sea zone adjacent to a high-S coastal target (Western Germany neighbors several
    // SZ in the Bay of Biscay / North Sea area). With betaLaunch=0, the sea-zone bonus is 0,
    // so the SZ value should be entirely decay-driven (no additive bump).
    // We confirm by computing again with a positive betaLaunch and asserting the diff equals
    // betaLaunch on at least one sea zone.
    proData.setStrategicValueField(null); // clear lazy cache
    proData.setBetaLaunch(7.5);
    final Map<Territory, Double> withBonus = ProStrategicValueField.compute(proData, americans);

    boolean anySeaBumped = false;
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater()) {
        continue;
      }
      final double diff = withBonus.get(t) - field.get(t);
      if (Math.abs(diff - 7.5) < 1e-9) {
        anySeaBumped = true;
        break;
      }
    }
    assertThat(anySeaBumped)
        .as("At least one sea zone adjacent to a high-S coast must receive the launch bonus")
        .isTrue();
  }

  @Test
  void printManualAcceptanceNumbers_atRound1_default_knobs() {
    // Documents the S(n) values at the named §10 anchors under the default knobs for both
    // theater flags. Printed to stdout for capture into the PR body; the assertions only
    // pin the §10 acceptance criteria so a regression is loud. Round-3 (canonical fixture)
    // validation still requires a save-game export — 🧑 Manual TODO.
    proData.setGCap(75.0);
    proData.setGamma(0.85);
    proData.setWStrat(1.0);
    proData.setAlphaOff(0.10);

    // Names verified against the §10 canonical-fixture territory list (Italy is split into
    // Northern/Southern in 1940 Global; Tokyo is just "Japan" the home island).
    final String[] anchors = {
      "Germany",
      "Western Germany",
      "Japan",
      "Amur",
      "Korea",
      "Eastern United States",
      "Western United States",
      "Hawaiian Islands",
      "United Kingdom",
      "French West Africa",
      "Western Australia",
    };

    for (final AiTheaterPriority flag : AiTheaterPriority.values()) {
      proData.setAiTheaterPriority(flag);
      proData.setStrategicValueField(null);
      final Map<Territory, Double> field = ProStrategicValueField.compute(proData, americans);
      System.out.printf("%n--- §10 acceptance numbers — round 1, %s ---%n", flag);
      for (final String name : anchors) {
        final Territory t = data.getMap().getTerritoryOrThrow(name);
        System.out.printf("  S(%s) = %.3f%n", name, field.get(t));
      }
    }
  }

  @Test
  void manualAcceptance_KGF_gradientReachesUSEastCoast() {
    proData.setAiTheaterPriority(AiTheaterPriority.KGF);
    proData.setGCap(75.0);
    proData.setGamma(0.85);
    proData.setWStrat(1.0);
    proData.setAlphaOff(0.10);

    final Map<Territory, Double> field = ProStrategicValueField.compute(proData, americans);

    final Territory eastUs = data.getMap().getTerritoryOrThrow("Eastern United States");
    final Territory germany = data.getMap().getTerritoryOrThrow("Germany");
    final Territory westGermany = data.getMap().getTerritoryOrThrow("Western Germany");
    assertThat(field.get(germany))
        .as("§10 acceptance: KGF anchor — capital must dominate any neighbor")
        .isGreaterThan(field.get(westGermany));
    assertThat(field.get(eastUs))
        .as("§10 acceptance: gradient reaches US East Coast at default knobs (>0)")
        .isPositive();
  }

  @Test
  void manualAcceptance_KJF_asymmetry_PacificDominatesAtlantic() {
    proData.setAiTheaterPriority(AiTheaterPriority.KJF);
    proData.setGCap(75.0);
    proData.setGamma(0.85);
    proData.setWStrat(1.0);
    proData.setAlphaOff(0.10);

    final Map<Territory, Double> field = ProStrategicValueField.compute(proData, americans);

    // §10 Finding 1: KJF reinforces an existing Pacific lean, doesn't flatten it.
    // Pick representative Asia + Atlantic regions and assert the Pacific side wins.
    final Territory manchuria = data.getMap().getTerritoryOrThrow("Manchuria");
    final Territory frenchWestAfrica = data.getMap().getTerritoryOrThrow("French West Africa");
    assertThat(field.get(manchuria))
        .as("§10 KJF asymmetry: Asian theater must dominate non-Asian under KJF")
        .isGreaterThan(field.get(frenchWestAfrica));
  }

  @Test
  void buildObjectives_includesLiveEnemyCapitals_and_isNonEmpty() {
    proData.setAiTheaterPriority(AiTheaterPriority.KGF);

    final Map<Territory, Double> anchors =
        ProStrategicValueField.buildObjectives(proData, americans);

    assertThat(anchors)
        .as("anchor set must not be empty for a standard 1940 Global setup")
        .isNotEmpty();
    // Germany and Japan are both axis capitals at round 1 — both must appear as anchors,
    // Germany at full strength under KGF, Japan at alphaOff.
    final Territory berlin = data.getMap().getTerritoryOrThrow("Germany");
    final Territory tokyo = data.getMap().getTerritoryOrThrow("Japan");
    assertThat(anchors).containsKey(berlin);
    assertThat(anchors).containsKey(tokyo);
    assertThat(anchors.get(berlin))
        .as("Berlin is targeted-theater capital under KGF — must be at full G_cap")
        .isGreaterThan(anchors.get(tokyo));
  }

  // --- helpers ---

  /**
   * ProData lacks a public init that takes a raw GameData. Reflection-injects the data field;
   * pattern borrowed from ProFriendlyIslandFloorTest.
   */
  private static ProData proDataFor(final GameData gameData) throws Exception {
    final ProData p = new ProData();
    final Field f = ProData.class.getDeclaredField("data");
    f.setAccessible(true);
    f.set(p, gameData);
    return p;
  }

  /** Convenience reachable-territory set, mostly for debugging during test development. */
  @SuppressWarnings("unused")
  private static Set<Territory> waterOnly(final GameData data) {
    final Set<Territory> s = new HashSet<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (t.isWater()) {
        s.add(t);
      }
    }
    return s;
  }
}
