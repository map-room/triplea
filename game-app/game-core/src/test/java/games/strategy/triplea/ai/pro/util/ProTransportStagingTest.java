package games.strategy.triplea.ai.pro.util;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.xml.TestMapGameData;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

/**
 * Phase 3D / PR-F (map-room#2755) — offense-intent transport staging tests. Covers the gate
 * (US-only, wStrat>0 active), the scoring (max S of adjacent enemy land beats Caribbean drift), and
 * the no-SVF-field short-circuit.
 */
class ProTransportStagingTest {

  private GameData data;
  private GamePlayer americans;
  private GamePlayer germans;
  private ProData proData;

  @BeforeEach
  void setUp() throws Exception {
    ClientSetting.setPreferences(new MemoryPreferences());
    data = TestMapGameData.GLOBAL1940.getGameData();
    americans = data.getPlayerList().getPlayerId("Americans");
    germans = data.getPlayerList().getPlayerId("Germans");
    proData = proDataFor(data, americans);
  }

  @Test
  void isStagingActive_falseForNonUs() throws Exception {
    final ProData germansData = proDataFor(data, germans);
    germansData.setWStrat(4.0);
    assertThat(ProTransportStaging.isStagingActive(germansData, germans))
        .as("Spec §2: staging is US-only — other AI bypass")
        .isFalse();
  }

  @Test
  void isStagingActive_falseAtDefaultWStrat() {
    // Default wStrat=0 keeps staging dormant. SVF blend off ⇒ staging off.
    assertThat(ProTransportStaging.isStagingActive(proData, americans))
        .as("wStrat=0 means SVF dormant — staging must not fire either")
        .isFalse();
  }

  @Test
  void isStagingActive_trueForUsWithWStrat() {
    proData.setWStrat(4.0);
    assertThat(ProTransportStaging.isStagingActive(proData, americans))
        .as("US + wStrat>0 = active")
        .isTrue();
  }

  @Test
  void scoreSeaZone_picksMaxAdjacentLandValue() {
    // Build a synthetic SVF where Morocco has high S and surrounding non-Morocco neighbors
    // are low. SZ 91 should score by Morocco's value.
    final GameMap map = data.getMap();
    final Territory sz91 = data.getMap().getTerritoryOrThrow("91 Sea Zone");
    final Territory morocco = data.getMap().getTerritoryOrThrow("Morocco");

    final Map<Territory, Double> svf = new HashMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      svf.put(t, t.equals(morocco) ? 250.0 : 0.0);
    }

    final double score = ProTransportStaging.scoreSeaZone(sz91, svf, map, americans);
    assertThat(score)
        .as("SZ 91 should score by Morocco's S (the max-adjacent-land value)")
        .isEqualTo(250.0);
  }

  @Test
  void scoreSeaZone_zeroWhenSeaZoneIsLand() {
    final GameMap map = data.getMap();
    final Territory morocco = data.getMap().getTerritoryOrThrow("Morocco");
    final Map<Territory, Double> svf = new HashMap<>();
    svf.put(morocco, 250.0);
    final double score = ProTransportStaging.scoreSeaZone(morocco, svf, map, americans);
    assertThat(score).as("Land territories return 0 — only sea zones get scored").isEqualTo(0.0);
  }

  @Test
  void stageIdleTransports_returnsZeroWhenInactive() {
    // wStrat=0 by default. Even with transport-move-map containing transports + reachable
    // sea zones, no staging should happen.
    final int staged = ProTransportStaging.stageIdleTransports(proData, americans, new HashMap<>());
    assertThat(staged).as("Inactive staging returns 0 immediately").isZero();
  }

  @Test
  void stageIdleTransports_returnsZeroWhenSvfNull() {
    proData.setWStrat(4.0);
    proData.setStrategicValueField(null);
    final int staged = ProTransportStaging.stageIdleTransports(proData, americans, new HashMap<>());
    assertThat(staged).as("Staging requires a populated SVF; null short-circuits").isZero();
  }

  // --- helpers ---

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
