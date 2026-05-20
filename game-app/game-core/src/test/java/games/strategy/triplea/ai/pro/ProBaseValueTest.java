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
 * Regression tests for #2633: ProTerritoryValueUtils ignores airfields and naval bases.
 *
 * <p>Root cause: {@code findTerritoryAttackValue} and {@code findLandValue} used only IPC
 * production to score territories, ignoring the strategic value of airfields (extend fighter/bomber
 * range) and naval bases (extend fleet range, enable repairs). This caused the planner to
 * deprioritise or discard base-bearing islands entirely.
 *
 * <p>Fix: add {@code AIRFIELD_BONUS = 5.0} and {@code NAVAL_BASE_BONUS = 5.0} to both value
 * functions. Bases are detected via unit presence ({@code UnitAttachment.isAirBase()} for
 * airfields; non-empty {@code UnitAttachment.getGivesMovement()} for harbours), covering the G40
 * unit-model and also maps that use territory-attachment flags.
 *
 * <p>Test anchor: G40 Pacific island cluster.
 *
 * <ul>
 *   <li>Midway: production=0, airfield only → value=5 (bonus exceeds Iwo Jima production=1,
 *       value=3)
 *   <li>Caroline Islands: production=0, airfield + harbour → value=10 (bonus well above Iwo Jima)
 *   <li>Borneo: production=4, no base → value=12 (substantially higher IPC still wins over Caroline
 *       Islands=10 — base is a nudge, not an override)
 * </ul>
 */
public class ProBaseValueTest {

  private static final String CAROLINE_ISLANDS = "Caroline Islands";
  private static final String IWO_JIMA = "Iwo Jima";
  private static final String MIDWAY = "Midway";
  private static final String BORNEO = "Borneo";

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
   * Acceptance criterion 1: airfield bonus must outweigh a modest production advantage.
   *
   * <p>Midway (production=0, airfield) → {@code findTerritoryAttackValue} = 5.0 Iwo Jima
   * (production=1, no base) → {@code findTerritoryAttackValue} = 3.0 Midway must score higher.
   */
  @Test
  void airfieldBonusOutweighsLowerProduction() {
    final Territory midway = data.getMap().getTerritoryOrThrow(MIDWAY);
    final Territory iwoJima = data.getMap().getTerritoryOrThrow(IWO_JIMA);

    final double midwayValue =
        ProTerritoryValueUtils.findTerritoryAttackValue(proData, americans, midway);
    final double iwoJimaValue =
        ProTerritoryValueUtils.findTerritoryAttackValue(proData, americans, iwoJima);

    assertThat(midwayValue)
        .as("Midway (airfield, prod=0) must outvalue Iwo Jima (no base, prod=1)")
        .isGreaterThan(iwoJimaValue);
    assertThat(midwayValue)
        .as("Midway attack value must include the AIRFIELD_BONUS")
        .isEqualTo(ProTerritoryValueUtils.AIRFIELD_BONUS);
  }

  /**
   * Acceptance criterion 2: both-base bonus must clearly outweigh a 1-production territory.
   *
   * <p>Caroline Islands (production=0, airfield + harbour) → value = 10.0 Iwo Jima (production=1,
   * no base) → value = 3.0
   */
  @Test
  void bothBasesBonusClearlyOutweighsLowProduction() {
    final Territory carolineIslands = data.getMap().getTerritoryOrThrow(CAROLINE_ISLANDS);
    final Territory iwoJima = data.getMap().getTerritoryOrThrow(IWO_JIMA);

    final double carolineValue =
        ProTerritoryValueUtils.findTerritoryAttackValue(proData, americans, carolineIslands);
    final double iwoJimaValue =
        ProTerritoryValueUtils.findTerritoryAttackValue(proData, americans, iwoJima);

    assertThat(carolineValue)
        .as("Caroline Islands (airfield+harbour, prod=0) must outscore Iwo Jima (no base, prod=1)")
        .isGreaterThan(iwoJimaValue);
    assertThat(carolineValue)
        .as("Caroline Islands value must include both base bonuses")
        .isEqualTo(ProTerritoryValueUtils.AIRFIELD_BONUS + ProTerritoryValueUtils.NAVAL_BASE_BONUS);
  }

  /**
   * Counter-regression: substantially higher production must still dominate even with no base.
   *
   * <p>Borneo (production=4, no base) → value = 12.0 Caroline Islands (production=0,
   * airfield+harbour) → value = 10.0 High-production territory without a base must still win.
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
            "Borneo (prod=4, no base, value=12) must outscore Caroline Islands (prod=0, both"
                + " bases, value=10) — base bonus is a nudge, not an override")
        .isGreaterThan(carolineValue);
  }

  /**
   * Land value (hold-value) test: base-bearing islands score higher than bare islands via {@code
   * findTerritoryValues}.
   *
   * <p>Caroline Islands (prod=0, airfield+harbour) island-floor=0 → land value = 0 + 10 = 10.0 Iwo
   * Jima (prod=1, no base) island-floor=0.5 → land value = 0.5 Caroline Islands must rank higher.
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
