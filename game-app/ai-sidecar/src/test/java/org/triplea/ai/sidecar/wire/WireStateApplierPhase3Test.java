package org.triplea.ai.sidecar.wire;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.delegate.battle.BattleTracker;
import games.strategy.triplea.settings.ClientSetting;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;

/**
 * Phase 3, Task 5: verifies the three new {@link WireStateApplier} branches — round/step,
 * conqueredThisTurn, and factory operational damage.
 */
class WireStateApplierPhase3Test {

  private static CanonicalGameData canonical;

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load();
  }

  private GameData fresh() {
    return canonical.cloneForSession();
  }

  private ConcurrentMap<String, UUID> freshIdMap() {
    return new ConcurrentHashMap<>();
  }

  @Test
  void appliesRoundAndStep() {
    final GameData gd = fresh();
    final WireState wire = new WireState(List.of(), List.of(), 3, "purchase", "Germans", List.of());
    WireStateApplier.apply(gd, wire, freshIdMap());
    assertThat(gd.getSequence().getRound()).isEqualTo(3);
    // Uniquely identify the step via its XML name attribute + player. GameStep.getDisplayName
    // in Global 1940 resolves to the delegate's display ("Purchase Units") and is shared
    // across players, so the name attribute ("germansPurchase") is the real key.
    assertThat(gd.getSequence().getStep().getName()).isEqualToIgnoringCase("GermansPurchase");
    assertThat(gd.getSequence().getStep().getPlayerId().getName()).isEqualTo("Germans");
  }

  @Test
  void marksConqueredTerritory() {
    final GameData gd = fresh();
    final WireTerritory egypt = new WireTerritory("Egypt", "Germans", List.of(), true);
    final WireState wire =
        new WireState(List.of(egypt), List.of(), 3, "purchase", "Germans", List.of());
    WireStateApplier.apply(gd, wire, freshIdMap());

    final Territory egyptT = gd.getMap().getTerritoryOrThrow("Egypt");
    final BattleTracker tracker = gd.getBattleDelegate().getBattleTracker();
    assertThat(tracker.wasConquered(egyptT)).isTrue();
    assertThat(egyptT.getOwner().getName()).isEqualTo("Germans");
  }

  @Test
  void appliesOperationalDamageToFactory() {
    final GameData gd = fresh();
    final ConcurrentMap<String, UUID> idMap = freshIdMap();
    // Germany starts with a factory_major in Global 1940 + infantry etc. To keep the sync
    // minimal and deterministic, send just the factory via the wire and assert the freshly-
    // placed unit ends up with the bombing damage applied.
    final WireUnit wu = new WireUnit("u-factory-1", "factory_major", 0, 0, 2);
    final WireTerritory wt = new WireTerritory("Germany", "Germans", List.of(wu), false);
    final WireState wire =
        new WireState(List.of(wt), List.of(), 3, "purchase", "Germans", List.of());
    WireStateApplier.apply(gd, wire, idMap);

    final Territory germany = gd.getMap().getTerritoryOrThrow("Germany");
    final Unit factory =
        germany.getUnits().stream()
            .filter(u -> u.getType().getName().startsWith("factory"))
            .findFirst()
            .orElseThrow();
    assertThat(factory.getUnitDamage()).isEqualTo(2);
  }
}
