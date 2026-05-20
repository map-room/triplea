package games.strategy.triplea.ai.pro;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.ai.pro.util.ProTerritoryValueUtils;
import games.strategy.triplea.xml.TestMapGameData;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for #2633 (base detection) and calibration fix in #2637.
 *
 * <p>#2633 root cause: {@code findTerritoryAttackValue} and {@code findLandValue} ignored airfields
 * and naval bases, causing the planner to deprioritise base-bearing islands entirely.
 *
 * <p>#2637 calibration: bonuses reduced from 5.0 → 2.0 each and gated behind a production threshold
 * ({@code production ≤ 1}). At 5.0 the bonuses added noise to higher-IPC mainland scoring; the gate
 * restricts them to marginal low-IPC territories where base presence is the primary discriminator.
 *
 * <p>All three formula call sites share a single helper {@link
 * ProTerritoryValueUtils#computeBaseBonus} so gate logic and constants stay in sync.
 *
 * <p>Test anchor: G40 Pacific island cluster + mainland sanity checks.
 *
 * <ul>
 *   <li>Midway (prod=0, airfield): base contribution = 2.0
 *   <li>Caroline Islands (prod=0, airfield + harbour): base contribution = 4.0
 *   <li>Iwo Jima (prod=1, no base): base contribution = 0.0
 *   <li>Hawaiian Islands (prod=1, airfield + harbour): base contribution = 4.0 (gate boundary —
 *       prod=1 IS within threshold)
 *   <li>Philippines (prod=2, airfield + harbour): base contribution = 0.0 (gate boundary — prod=2
 *       EXCEEDS threshold)
 *   <li>Eastern United States (prod=20, airfield + harbour): base contribution = 0.0 (high-IPC
 *       mainland)
 * </ul>
 */
public class ProBaseValueTest {

  private static final String CAROLINE_ISLANDS = "Caroline Islands";
  private static final String IWO_JIMA = "Iwo Jima";
  private static final String MIDWAY = "Midway";
  private static final String BORNEO = "Borneo";
  private static final String HAWAIIAN_ISLANDS = "Hawaiian Islands";
  private static final String PHILIPPINES = "Philippines";
  private static final String EASTERN_UNITED_STATES = "Eastern United States";

  private GameData data;
  private GamePlayer americans;
  private ProData proData;

  @BeforeEach
  void setUp() throws Exception {
    data = TestMapGameData.GLOBAL1940.getGameData();
    americans = data.getPlayerList().getPlayerId("Americans");
    proData = proDataFor(data);
  }

  /**
   * Midway (prod=0, airfield only) → base contribution = AIRFIELD_BONUS = 2.0.
   *
   * <p>Attack value = 3×prod×factory_mult + baseBonus = 0 + 2.0 = 2.0.
   */
  @Test
  void midwayBaseContributionMatchesAirfieldBonus() {
    final Territory midway = data.getMap().getTerritoryOrThrow(MIDWAY);

    assertThat(ProTerritoryValueUtils.computeBaseBonus(midway))
        .as("Midway (prod=0, airfield) base contribution must equal AIRFIELD_BONUS")
        .isEqualTo(ProTerritoryValueUtils.AIRFIELD_BONUS);

    assertThat(ProTerritoryValueUtils.findTerritoryAttackValue(proData, americans, midway))
        .as("Midway attack value = 0 (prod) + AIRFIELD_BONUS (base)")
        .isEqualTo(ProTerritoryValueUtils.AIRFIELD_BONUS);
  }

  /**
   * Caroline Islands (prod=0, airfield + harbour) → base contribution = AIRFIELD_BONUS +
   * NAVAL_BASE_BONUS = 4.0.
   */
  @Test
  void carolineIslandsBaseContributionMatchesBothBonuses() {
    final Territory carolineIslands = data.getMap().getTerritoryOrThrow(CAROLINE_ISLANDS);

    assertThat(ProTerritoryValueUtils.computeBaseBonus(carolineIslands))
        .as("Caroline Islands (prod=0, airfield+harbour) base contribution must equal both bonuses")
        .isEqualTo(ProTerritoryValueUtils.AIRFIELD_BONUS + ProTerritoryValueUtils.NAVAL_BASE_BONUS);

    assertThat(ProTerritoryValueUtils.findTerritoryAttackValue(proData, americans, carolineIslands))
        .as("Caroline Islands attack value = 0 (prod) + AIRFIELD_BONUS + NAVAL_BASE_BONUS")
        .isEqualTo(ProTerritoryValueUtils.AIRFIELD_BONUS + ProTerritoryValueUtils.NAVAL_BASE_BONUS);
  }

  /** Iwo Jima (prod=1, no base) → base contribution = 0. Attack value = 3.0. */
  @Test
  void iwoJimaHasNoBaseContribution() {
    final Territory iwoJima = data.getMap().getTerritoryOrThrow(IWO_JIMA);

    assertThat(ProTerritoryValueUtils.computeBaseBonus(iwoJima))
        .as("Iwo Jima (prod=1, no base) must have zero base contribution")
        .isEqualTo(0.0);
  }

  /**
   * Gate boundary (inclusive): Hawaiian Islands has prod=1 and both airfield + harbour. Production
   * threshold is {@code > 1}, so prod=1 IS within the gate — the bonus must be applied.
   *
   * <p>Expected: computeBaseBonus = AIRFIELD_BONUS + NAVAL_BASE_BONUS = 4.0.
   */
  @Test
  void prod1WithBothBasesGetsFullBonus() {
    final Territory hawaii = data.getMap().getTerritoryOrThrow(HAWAIIAN_ISLANDS);

    assertThat(ProTerritoryValueUtils.computeBaseBonus(hawaii))
        .as(
            "Hawaiian Islands (prod=1, airfield+harbour) must receive full bonus — prod=1 is at"
                + " the gate boundary (threshold is >1, not >=1)")
        .isEqualTo(ProTerritoryValueUtils.AIRFIELD_BONUS + ProTerritoryValueUtils.NAVAL_BASE_BONUS);
  }

  /**
   * Gate boundary (exclusive): Philippines has prod=2 and both airfield + harbour. Production
   * exceeds the threshold — base bonus must NOT be applied.
   *
   * <p>Expected: computeBaseBonus = 0.0.
   */
  @Test
  void prod2WithBothBasesGetsNoBonus() {
    final Territory philippines = data.getMap().getTerritoryOrThrow(PHILIPPINES);

    assertThat(ProTerritoryValueUtils.computeBaseBonus(philippines))
        .as(
            "Philippines (prod=2, airfield+harbour) must receive zero bonus — production exceeds"
                + " the gate threshold (>1)")
        .isEqualTo(0.0);
  }

  /**
   * High-IPC mainland sanity: Eastern United States (prod=20, airfield + harbour) receives zero
   * base contribution. Confirms the gate suppresses the bonus across all mainland territories.
   */
  @Test
  void highIpcMainlandGetsNoBaseBonus() {
    final Territory easternUs = data.getMap().getTerritoryOrThrow(EASTERN_UNITED_STATES);

    assertThat(ProTerritoryValueUtils.computeBaseBonus(easternUs))
        .as("Eastern United States (prod=20) must have zero base contribution")
        .isEqualTo(0.0);
  }

  /**
   * Counter-regression: substantially higher production must still dominate over base-only islands.
   *
   * <p>Borneo (prod=4, no base) → attack value = 12.0. Caroline Islands (prod=0, both bases) →
   * attack value = 4.0. Production wins decisively.
   */
  @Test
  void counterRegression_substantialProductionBeatsBaseWithoutProduction() {
    final Territory borneo = data.getMap().getTerritoryOrThrow(BORNEO);
    final Territory carolineIslands = data.getMap().getTerritoryOrThrow(CAROLINE_ISLANDS);

    final double borneoValue =
        ProTerritoryValueUtils.findTerritoryAttackValue(proData, americans, borneo);
    final double carolineValue =
        ProTerritoryValueUtils.findTerritoryAttackValue(proData, americans, carolineIslands);

    assertThat(borneoValue)
        .as(
            "Borneo (prod=4, no base) must outscore Caroline Islands (prod=0, both bases)"
                + " — base bonus is a nudge for marginal islands, not a production override")
        .isGreaterThan(carolineValue);
  }

  /**
   * Land value (hold-value) test: Caroline Islands scores higher than Iwo Jima via {@code
   * findTerritoryValues}, confirming the base bonus is applied to the land-value formula too.
   *
   * <p>Caroline Islands (prod=0, airfield+harbour) → land value ≈ 4.0. Iwo Jima (prod=1, no base) →
   * land value ≈ 0.5 (island floor). Caroline Islands must rank higher.
   */
  @Test
  void landValueBonusAppliedToBaseIslands() {
    final Set<Territory> toCheck =
        Set.of(
            data.getMap().getTerritoryOrThrow(CAROLINE_ISLANDS),
            data.getMap().getTerritoryOrThrow(IWO_JIMA));

    final Map<Territory, Double> values =
        ProTerritoryValueUtils.findTerritoryValues(
            proData, americans, List.of(), List.of(), toCheck);

    final double carolineValue = values.get(data.getMap().getTerritoryOrThrow(CAROLINE_ISLANDS));
    final double iwoJimaValue = values.get(data.getMap().getTerritoryOrThrow(IWO_JIMA));

    assertThat(carolineValue)
        .as(
            "Caroline Islands (airfield+harbour, prod=0) land value must exceed Iwo Jima (no base,"
                + " prod=1)")
        .isGreaterThan(iwoJimaValue);
  }

  private static ProData proDataFor(final GameData gameData) throws Exception {
    final ProData proData = new ProData();
    final Field f = ProData.class.getDeclaredField("data");
    f.setAccessible(true);
    f.set(proData, gameData);
    return proData;
  }
}
