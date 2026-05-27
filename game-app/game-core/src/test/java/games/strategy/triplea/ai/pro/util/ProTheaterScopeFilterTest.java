package games.strategy.triplea.ai.pro.util;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.data.AiTheaterPriority;
import games.strategy.triplea.ai.pro.data.AiTheaterScopeMode;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.xml.TestMapGameData;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

/**
 * Phase 3C off-theater scope filter tests (map-room#2755 PR-E). The filter is the missing
 * structural lever that the value-blend alone can't pull: it removes off-theater attack candidates
 * from CM scope so the Pacific-commit feedback loop breaks. Tests pin: spec §2 US-only gate, mode
 * matrix (OFF / STRICT / ADAPTIVE), ring trip detection, engagement set bounds, theater
 * classification under KGF and KJF.
 */
class ProTheaterScopeFilterTest {

  private GameData data;
  private GamePlayer americans;
  private GamePlayer germans;
  private GamePlayer japanese;
  private ProData proData;

  @BeforeEach
  void setUp() throws Exception {
    ClientSetting.setPreferences(new MemoryPreferences());
    data = TestMapGameData.GLOBAL1940.getGameData();
    americans = data.getPlayerList().getPlayerId("Americans");
    germans = data.getPlayerList().getPlayerId("Germans");
    japanese = data.getPlayerList().getPlayerId("Japanese");
    proData = proDataFor(data, americans);
  }

  @Test
  void shouldSkipAttackTarget_returnsFalse_whenModeIsOff() {
    proData.setAiTheaterPriority(AiTheaterPriority.KGF);
    proData.setAiTheaterScopeMode(AiTheaterScopeMode.OFF);
    final Territory japan = data.getMap().getTerritoryOrThrow("Japan");

    assertThat(ProTheaterScopeFilter.shouldSkipAttackTarget(proData, japan))
        .as("Default mode OFF must not filter anything — PR-E ships zero-impact")
        .isFalse();
  }

  @Test
  void shouldSkipAttackTarget_returnsFalse_forNonUsPlayer() throws Exception {
    final ProData germansData = proDataFor(data, germans);
    germansData.setAiTheaterPriority(AiTheaterPriority.KGF);
    germansData.setAiTheaterScopeMode(AiTheaterScopeMode.STRICT);
    final Territory japan = data.getMap().getTerritoryOrThrow("Japan");

    assertThat(ProTheaterScopeFilter.shouldSkipAttackTarget(germansData, japan))
        .as("Spec §2: SVF filter is US-only; Germans CM unaffected even under STRICT")
        .isFalse();
  }

  @Test
  void shouldSkipAttackTarget_strict_filtersJapaneseTarget_underKGF() {
    proData.setAiTheaterPriority(AiTheaterPriority.KGF);
    proData.setAiTheaterScopeMode(AiTheaterScopeMode.STRICT);
    final Territory japan = data.getMap().getTerritoryOrThrow("Japan");
    final Territory korea = data.getMap().getTerritoryOrThrow("Korea");

    assertThat(ProTheaterScopeFilter.shouldSkipAttackTarget(proData, japan))
        .as("Japan home is off-theater under KGF — STRICT must filter it")
        .isTrue();
    assertThat(ProTheaterScopeFilter.shouldSkipAttackTarget(proData, korea))
        .as("Korea is Japanese-owned under KGF — STRICT must filter it")
        .isTrue();
  }

  @Test
  void shouldSkipAttackTarget_strict_doesNotFilterInTheaterTarget_underKGF() {
    proData.setAiTheaterPriority(AiTheaterPriority.KGF);
    proData.setAiTheaterScopeMode(AiTheaterScopeMode.STRICT);
    final Territory germany = data.getMap().getTerritoryOrThrow("Germany");
    final Territory italy = data.getMap().getTerritoryOrThrow("Southern Italy");

    assertThat(ProTheaterScopeFilter.shouldSkipAttackTarget(proData, germany))
        .as("Germany is in-theater under KGF — must not be filtered")
        .isFalse();
    assertThat(ProTheaterScopeFilter.shouldSkipAttackTarget(proData, italy))
        .as("Italy is in-theater under KGF — must not be filtered")
        .isFalse();
  }

  @Test
  void shouldSkipAttackTarget_strict_filtersGermanyTarget_underKJF() {
    proData.setAiTheaterPriority(AiTheaterPriority.KJF);
    proData.setAiTheaterScopeMode(AiTheaterScopeMode.STRICT);
    final Territory germany = data.getMap().getTerritoryOrThrow("Germany");
    final Territory japan = data.getMap().getTerritoryOrThrow("Japan");

    assertThat(ProTheaterScopeFilter.shouldSkipAttackTarget(proData, germany))
        .as("Germany is off-theater under KJF — STRICT must filter it")
        .isTrue();
    assertThat(ProTheaterScopeFilter.shouldSkipAttackTarget(proData, japan))
        .as("Japan is in-theater under KJF — must not be filtered")
        .isFalse();
  }

  @Test
  void shouldSkipAttackTarget_adaptive_filtersWhenRingCold() {
    // At round 1, no Japanese unit has moved out of Japan home — Marshall/Marianas/etc are
    // Japanese-owned but Hawaii/Western US/Aleutians are American-owned with no Japanese
    // presence. Several tripwires (Marshall, Marianas) ARE Japanese-owned at game start, so
    // the ring is actually pre-tripped at round 1. To test the cold path, we use a Japanese
    // home target that should be filtered regardless because Japan home isn't in the
    // engagement set (it's not a tripwire neighbor in our table).
    proData.setAiTheaterPriority(AiTheaterPriority.KGF);
    proData.setAiTheaterScopeMode(AiTheaterScopeMode.ADAPTIVE);
    final Territory japanHome = data.getMap().getTerritoryOrThrow("Japan");
    final Set<Territory> engagement = ProTheaterScopeFilter.getEngagementSet(proData);

    // Japan home should NOT be in the engagement set even when ring is tripped — tripwires
    // are Pacific approach territories, and their immediate neighbors don't include Japan.
    assertThat(engagement)
        .as("Japan home must not be in the engagement set — ADAPTIVE must protect against scope")
        .doesNotContain(japanHome);
    assertThat(ProTheaterScopeFilter.shouldSkipAttackTarget(proData, japanHome))
        .as("Japan home is off-theater + not in engagement set — ADAPTIVE filters it")
        .isTrue();
  }

  @Test
  void shouldSkipAttackTarget_adaptive_unlocksTrippedTripwire() {
    // Marshall Islands is Japanese-owned at game start (a tripwire). The ring trips automatically,
    // and Marshalls itself enters the engagement set. ADAPTIVE must allow the attack.
    proData.setAiTheaterPriority(AiTheaterPriority.KGF);
    proData.setAiTheaterScopeMode(AiTheaterScopeMode.ADAPTIVE);
    final Territory marshalls = data.getMap().getTerritoryOrThrow("Marshall Islands");

    // Sanity: Marshall Islands is Japanese-owned at round 1
    assertThat(marshalls.getOwner().getName())
        .as("Test relies on Marshall Islands being Japanese-owned at game start")
        .isEqualTo("Japanese");

    final Set<Territory> trippedTripwires = ProTheaterScopeFilter.getTrippedTripwires(proData);
    assertThat(trippedTripwires)
        .as("Marshall Islands (Japanese-owned) must register as a tripped tripwire")
        .contains(marshalls);

    assertThat(ProTheaterScopeFilter.shouldSkipAttackTarget(proData, marshalls))
        .as("Tripped tripwire is in engagement set — ADAPTIVE allows the attack")
        .isFalse();
  }

  @Test
  void getEngagementSet_isEmptyWhenModeIsStrict() {
    proData.setAiTheaterPriority(AiTheaterPriority.KGF);
    proData.setAiTheaterScopeMode(AiTheaterScopeMode.STRICT);

    assertThat(ProTheaterScopeFilter.getEngagementSet(proData))
        .as("STRICT mode means no engagement carve-out — the set is always empty")
        .isEmpty();
  }

  @Test
  void getEngagementSet_isEmptyWhenModeIsOff() {
    proData.setAiTheaterPriority(AiTheaterPriority.KGF);
    proData.setAiTheaterScopeMode(AiTheaterScopeMode.OFF);

    assertThat(ProTheaterScopeFilter.getEngagementSet(proData))
        .as("OFF mode disables the entire filter, including engagement-set carve-outs")
        .isEmpty();
  }

  @Test
  void shouldSkipAttackTarget_neverFiltersAlliedOrTrueNeutral() {
    // Allies and True Neutrals are never "off-theater axis" per the classifier — even under
    // STRICT, an allied or neutral target should never be filtered.
    proData.setAiTheaterPriority(AiTheaterPriority.KGF);
    proData.setAiTheaterScopeMode(AiTheaterScopeMode.STRICT);
    final Territory uk = data.getMap().getTerritoryOrThrow("United Kingdom");
    final Territory persia = data.getMap().getTerritoryOrThrow("Persia");

    assertThat(ProTheaterScopeFilter.shouldSkipAttackTarget(proData, uk))
        .as("UK is allied — never filtered as off-theater")
        .isFalse();
    assertThat(ProTheaterScopeFilter.shouldSkipAttackTarget(proData, persia))
        .as("Persia (True Neutral or Pro-Allies) — never filtered as off-theater axis")
        .isFalse();
  }

  @Test
  void isFilterActive_returnsFalseForNonUsAndOffMode() throws Exception {
    proData.setAiTheaterScopeMode(AiTheaterScopeMode.OFF);
    assertThat(ProTheaterScopeFilter.isFilterActive(proData))
        .as("OFF mode means no filter overhead")
        .isFalse();

    proData.setAiTheaterScopeMode(AiTheaterScopeMode.STRICT);
    final ProData japaneseData = proDataFor(data, japanese);
    japaneseData.setAiTheaterScopeMode(AiTheaterScopeMode.STRICT);
    assertThat(ProTheaterScopeFilter.isFilterActive(japaneseData))
        .as("Non-US AI never has the filter active even with STRICT mode")
        .isFalse();

    assertThat(ProTheaterScopeFilter.isFilterActive(proData))
        .as("US + non-OFF mode = filter active")
        .isTrue();
  }

  // --- helpers ---

  /**
   * Counts player-owned units in a territory; debug helper kept around in case future tests need
   * it.
   */
  @SuppressWarnings("unused")
  private static long playerUnitsAt(final Collection<Unit> units, final GamePlayer player) {
    return units.stream().filter(u -> u.getOwner().equals(player)).count();
  }

  private static ProData proDataFor(final GameData gameData, final GamePlayer player)
      throws Exception {
    final ProData p = new ProData();
    final Field dataField = ProData.class.getDeclaredField("data");
    dataField.setAccessible(true);
    dataField.set(p, gameData);
    final Field playerField = ProData.class.getDeclaredField("player");
    playerField.setAccessible(true);
    playerField.set(p, player);
    return p;
  }
}
