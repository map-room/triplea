package games.strategy.triplea.ai.pro.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.Constants;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.triplea.java.collections.IntegerMap;

final class ProSplitResourceTrackerTest {

  private GameData data;
  private Resource pus;
  private Territory tEurope;
  private Territory tPacific;
  private Territory tUnmapped;
  private Map<Territory, ProSplitResourceTracker.Pool> poolByTerritory;

  @BeforeEach
  void setUp() {
    data = TestMapGameData.WW2V3_1941.getGameData();
    pus = data.getResourceList().getResourceOrThrow(Constants.PUS);
    tEurope = data.getMap().getTerritoryOrThrow("United Kingdom");
    tPacific = data.getMap().getTerritoryOrThrow("India");
    tUnmapped = data.getMap().getTerritoryOrThrow("Germany");
    poolByTerritory = new HashMap<>();
    poolByTerritory.put(tEurope, ProSplitResourceTracker.Pool.EUROPE);
    poolByTerritory.put(tPacific, ProSplitResourceTracker.Pool.PACIFIC);
  }

  private ProPurchaseOption ppoCost(final int cost) {
    final IntegerMap<Resource> costs = new IntegerMap<>();
    costs.add(pus, cost);
    final ProPurchaseOption ppo = mock(ProPurchaseOption.class);
    when(ppo.getCosts()).thenReturn(costs);
    return ppo;
  }

  @Test
  void hasEnoughRoutesToEuropePoolForEuropeTerritory() {
    final ProSplitResourceTracker t = new ProSplitResourceTracker(10, 0, poolByTerritory, data);
    assertThat(t.hasEnough(ppoCost(5), tEurope)).isTrue();
    assertThat(t.hasEnough(ppoCost(10), tEurope)).isTrue();
    assertThat(t.hasEnough(ppoCost(11), tEurope)).isFalse();
  }

  @Test
  void hasEnoughRoutesToPacificPoolForPacificTerritory() {
    final ProSplitResourceTracker t = new ProSplitResourceTracker(0, 10, poolByTerritory, data);
    assertThat(t.hasEnough(ppoCost(5), tPacific)).isTrue();
    assertThat(t.hasEnough(ppoCost(10), tPacific)).isTrue();
    assertThat(t.hasEnough(ppoCost(11), tPacific)).isFalse();
  }

  @Test
  void hasEnoughRejectsWhenCorrectPoolIsShort() {
    final ProSplitResourceTracker t = new ProSplitResourceTracker(4, 4, poolByTerritory, data);
    assertThat(t.hasEnough(ppoCost(5), tEurope)).isFalse();
    assertThat(t.hasEnough(ppoCost(5), tPacific)).isFalse();
  }

  @Test
  void unmappedTerritoryDefaultsToEurope() {
    final ProSplitResourceTracker t = new ProSplitResourceTracker(10, 0, poolByTerritory, data);
    assertThat(t.hasEnough(ppoCost(5), tUnmapped)).isTrue(); // europe has 10
    final ProSplitResourceTracker t2 = new ProSplitResourceTracker(0, 10, poolByTerritory, data);
    assertThat(t2.hasEnough(ppoCost(5), tUnmapped)).isFalse(); // europe pool is 0
  }

  @Test
  void purchaseDeductsFromCorrectPool() {
    final ProSplitResourceTracker t = new ProSplitResourceTracker(10, 10, poolByTerritory, data);
    t.purchase(ppoCost(5), tEurope);
    assertThat(t.hasEnough(ppoCost(5), tEurope)).isTrue(); // 5 left
    assertThat(t.hasEnough(ppoCost(6), tEurope)).isFalse();
    assertThat(t.hasEnough(ppoCost(10), tPacific)).isTrue(); // Pacific untouched
  }

  @Test
  void noTerritoryHasEnoughReturnsTrueIfEitherPoolHasEnough() {
    final ProSplitResourceTracker t = new ProSplitResourceTracker(10, 0, poolByTerritory, data);
    assertThat(t.hasEnough(ppoCost(5))).isTrue();
    assertThat(t.hasEnough(ppoCost(10))).isTrue();
  }

  @Test
  void noTerritoryHasEnoughReturnsFalseIfBothPoolsShort() {
    final ProSplitResourceTracker t = new ProSplitResourceTracker(4, 4, poolByTerritory, data);
    assertThat(t.hasEnough(ppoCost(5))).isFalse();
  }

  @Test
  void tempPurchaseConfirmAppliesPerPool() {
    final ProSplitResourceTracker t = new ProSplitResourceTracker(20, 20, poolByTerritory, data);
    t.tempPurchase(ppoCost(5), tEurope);
    t.tempPurchase(ppoCost(10), tPacific);
    t.confirmTempPurchases();
    assertThat(t.hasEnough(ppoCost(15), tEurope)).isTrue(); // 15 left
    assertThat(t.hasEnough(ppoCost(16), tEurope)).isFalse();
    assertThat(t.hasEnough(ppoCost(10), tPacific)).isTrue(); // 10 left
    assertThat(t.hasEnough(ppoCost(11), tPacific)).isFalse();
  }

  @Test
  void tempPurchaseClearReversesWithoutAffectingPools() {
    final ProSplitResourceTracker t = new ProSplitResourceTracker(20, 20, poolByTerritory, data);
    t.tempPurchase(ppoCost(5), tEurope);
    t.tempPurchase(ppoCost(10), tPacific);
    t.clearTempPurchases();
    assertThat(t.hasEnough(ppoCost(20), tEurope)).isTrue();
    assertThat(t.hasEnough(ppoCost(20), tPacific)).isTrue();
  }

  @Test
  void isEmptyOnlyWhenBothPoolsDepleted() {
    final ProSplitResourceTracker t = new ProSplitResourceTracker(10, 0, poolByTerritory, data);
    assertThat(t.isEmpty()).isFalse();
    t.purchase(ppoCost(10), tEurope);
    assertThat(t.isEmpty()).isTrue();
  }

  @Test
  void getTempPUsReturnsCombinedAcrossBothPools() {
    final ProSplitResourceTracker t = new ProSplitResourceTracker(20, 20, poolByTerritory, data);
    t.tempPurchase(ppoCost(5), tEurope);
    t.tempPurchase(ppoCost(10), tPacific);
    assertThat(t.getTempPUs(data)).isEqualTo(15);
  }

  @Test
  void toStringIncludesBothPools() {
    final ProSplitResourceTracker t = new ProSplitResourceTracker(10, 20, poolByTerritory, data);
    final String s = t.toString();
    assertThat(s).contains("europe");
    assertThat(s).contains("pacific");
  }
}
