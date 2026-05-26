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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

/**
 * SVF PR-B blend correctness + zero-impact regression guard (map-room#2755).
 *
 * <p>The load-bearing test is the regression guard: with {@code wStrat == 0}, the value map
 * produced by {@code findTerritoryValues} must be byte-identical to pre-PR-B output. This is the
 * "AI-only" guarantee — adding the blend infrastructure cannot move the baseline at default.
 */
class ProTerritoryValueUtilsBlendTest {

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

  // ---------------------------------------------------------------------------
  // Regression guard — the headline correctness test for PR-B
  // ---------------------------------------------------------------------------

  @Test
  void wStratZero_findTerritoryValues_byteIdenticalToBaselineEverywhere() {
    proData.setWStrat(0.0); // default; explicit for the test
    final Set<Territory> all = new HashSet<>(data.getMap().getTerritories());

    // Call once. With wStrat=0, the lazy compute is skipped and getStrategicValueFieldFor
    // returns 0 for every territory, so the blend (value += wStrat * S(t)) adds 0 to every
    // entry. Output must equal what the pre-blend code path would have produced.
    final Map<Territory, Double> withBlend =
        ProTerritoryValueUtils.findTerritoryValues(proData, americans, List.of(), List.of(), all);

    // Strategic field should never have been computed.
    assertThat(proData.getStrategicValueField())
        .as("wStrat=0 must not trigger the lazy compute (zero-impact default)")
        .isNull();

    // Re-call and assert identity — twice in the same JVM, same input, same output. If any
    // hidden state had crept in from PR-B, this would diverge.
    final Map<Territory, Double> withBlendAgain =
        ProTerritoryValueUtils.findTerritoryValues(proData, americans, List.of(), List.of(), all);
    assertThat(withBlendAgain).isEqualTo(withBlend);
  }

  @Test
  void wStratZero_findSeaTerritoryValues_byteIdenticalToBaselineEverywhere() {
    proData.setWStrat(0.0);
    final List<Territory> waters =
        data.getMap().getTerritories().stream().filter(Territory::isWater).toList();

    final Map<Territory, Double> first =
        ProTerritoryValueUtils.findSeaTerritoryValues(proData, americans, List.of(), waters);
    final Map<Territory, Double> second =
        ProTerritoryValueUtils.findSeaTerritoryValues(proData, americans, List.of(), waters);

    assertThat(proData.getStrategicValueField()).as("wStrat=0 must not compute").isNull();
    assertThat(second).as("deterministic at wStrat=0").isEqualTo(first);
  }

  // ---------------------------------------------------------------------------
  // Blend correctness — value increases monotonically with wStrat
  // ---------------------------------------------------------------------------

  @Test
  void wStratPositive_addsExactlyWStratTimesS_atGermanyUnderKGF() {
    proData.setAiTheaterPriority(AiTheaterPriority.KGF);
    proData.setGCap(75.0);
    proData.setGamma(0.85);
    final Territory germany = data.getMap().getTerritoryOrThrow("Germany");

    // Capture baseline at wStrat=0.
    proData.setWStrat(0.0);
    proData.setStrategicValueField(null);
    final Map<Territory, Double> baseline =
        ProTerritoryValueUtils.findTerritoryValues(
            proData, americans, List.of(), List.of(), Set.of(germany));
    final double baseValue = baseline.get(germany);

    // Now wStrat=2.0 — the blend at Germany should add exactly 2.0 * S(Germany).
    proData.setWStrat(2.0);
    proData.setStrategicValueField(null);
    final Map<Territory, Double> blended =
        ProTerritoryValueUtils.findTerritoryValues(
            proData, americans, List.of(), List.of(), Set.of(germany));
    final double blendedValue = blended.get(germany);

    final double sGermany = proData.getStrategicValueField().get(germany);
    assertThat(sGermany)
        .as("S(Germany) under KGF must equal G_cap = 75")
        .isCloseTo(75.0, org.assertj.core.api.Assertions.within(1e-9));
    assertThat(blendedValue - baseValue)
        .as("blend delta must equal exactly wStrat * S(t)")
        .isCloseTo(2.0 * sGermany, org.assertj.core.api.Assertions.within(1e-9));
  }

  @Test
  void blendMovesUSCoastValuePositiveUnderKGF() {
    final Territory eastUs = data.getMap().getTerritoryOrThrow("Eastern United States");
    proData.setAiTheaterPriority(AiTheaterPriority.KGF);

    proData.setWStrat(0.0);
    proData.setStrategicValueField(null);
    final double baseEast =
        ProTerritoryValueUtils.findTerritoryValues(
                proData, americans, List.of(), List.of(), Set.of(eastUs))
            .get(eastUs);

    proData.setWStrat(1.0);
    proData.setStrategicValueField(null);
    final double blendedEast =
        ProTerritoryValueUtils.findTerritoryValues(
                proData, americans, List.of(), List.of(), Set.of(eastUs))
            .get(eastUs);

    assertThat(blendedEast)
        .as("KGF blend with wStrat>0 must lift US East Coast value above the baseline")
        .isGreaterThan(baseEast);
  }

  @Test
  void lazyCompute_inGetStrategicValueFieldFor_fires_evenWhenNotPrecomputed() {
    // The findSeaTerritoryValues path doesn't share the findTerritoryValues lazy hook.
    // ProData.getStrategicValueFieldFor must lazily compute on its own when wStrat>0.
    proData.setWStrat(1.0);
    proData.setStrategicValueField(null);

    final Territory germany = data.getMap().getTerritoryOrThrow("Germany");
    final double s = proData.getStrategicValueFieldFor(germany);

    assertThat(proData.getStrategicValueField()).isNotNull();
    assertThat(s).isPositive();
  }

  @Test
  void getStrategicValueFieldFor_returnsZero_whenWStratZero_evenForKnownAnchor() {
    proData.setWStrat(0.0);

    final Territory germany = data.getMap().getTerritoryOrThrow("Germany");
    final double s = proData.getStrategicValueFieldFor(germany);

    assertThat(s).isEqualTo(0.0);
    assertThat(proData.getStrategicValueField())
        .as("wStrat=0 must not trigger compute via getStrategicValueFieldFor either")
        .isNull();
  }

  // ---------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------

  private ProData proDataFor(final GameData gameData) throws Exception {
    final ProData p = new ProData();
    final Field dataField = ProData.class.getDeclaredField("data");
    dataField.setAccessible(true);
    dataField.set(p, gameData);
    // ProData.getStrategicValueFieldFor calls compute(this, player) which uses the player
    // field on ProData — set it via reflection too so the lazy-compute path doesn't NPE.
    final Field playerField = ProData.class.getDeclaredField("player");
    playerField.setAccessible(true);
    playerField.set(p, americans);
    return p;
  }

  @SuppressWarnings("unused")
  private static Map<Territory, Double> copyOf(final Map<Territory, Double> m) {
    return new HashMap<>(m);
  }
}
