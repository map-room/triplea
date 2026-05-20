package games.strategy.triplea.ai.pro;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.ai.pro.util.ProTerritoryValueUtils;
import games.strategy.triplea.xml.TestMapGameData;
import java.lang.reflect.Field;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the base-bonus infrastructure in {@link ProTerritoryValueUtils}.
 *
 * <p>The {@code AIRFIELD_BONUS} and {@code NAVAL_BASE_BONUS} constants are currently 0.0 — bonuses
 * are disabled while we observe AI behavior. The infrastructure ({@link
 * ProTerritoryValueUtils#computeBaseBonus}, the production gate, all three call sites) is kept
 * intact so the values can be tuned back up when needed.
 *
 * <p>These tests verify:
 *
 * <ol>
 *   <li>Base detection is correct per territory (airfield/harbour presence, production gate).
 *   <li>{@code computeBaseBonus} returns a value consistent with the current constants.
 *   <li>Production still dominates territory scoring when bonuses are zero.
 * </ol>
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

  /** Midway (prod=0, airfield only) → base contribution = AIRFIELD_BONUS. */
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
   * NAVAL_BASE_BONUS.
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
   * Gate boundary (inclusive): prod=1 is within the threshold ({@code > 1} means threshold
   * exclusive), so the bonus formula runs. With current constants both bonuses are 0.0.
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

  /** Production dominates: Borneo (prod=4, no base) must outscore Caroline Islands (prod=0). */
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

  private static ProData proDataFor(final GameData gameData) throws Exception {
    final ProData proData = new ProData();
    final Field f = ProData.class.getDeclaredField("data");
    f.setAccessible(true);
    f.set(proData, gameData);
    return proData;
  }
}
