package org.triplea.ai.sidecar.exec;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.UnitUtils;
import games.strategy.triplea.ai.pro.util.WarProductionApplier;
import games.strategy.triplea.settings.ClientSetting;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.dto.PurchasePlan;
import org.triplea.ai.sidecar.dto.PurchaseRequest;
import org.triplea.ai.sidecar.wire.WirePlayer;
import org.triplea.ai.sidecar.wire.WireRelationship;
import org.triplea.ai.sidecar.wire.WireState;
import org.triplea.ai.sidecar.wire.WireStateApplier;
import org.triplea.ai.sidecar.wire.WireTerritory;
import org.triplea.ai.sidecar.wire.WireUnit;

/**
 * map-room#4754: stand-in for {@code triggerAttachment_Americans_War_Production_*}.
 *
 * <p>Deleting {@link WarProductionApplier#apply} from {@code AbstractProAi.purchase} after the
 * simulated politics step must fail {@link #seed1UsAtWarBuysForThirtyCapacity()}.
 */
class WarProductionStandIn4754Test {

  private static final String G40 = "ww2global40_2nd_edition";
  private static final List<String> US_ICS =
      List.of("Eastern United States", "Central United States", "Western United States");

  /** Seed-1 Hard US buy list when the three ICs are majors (30 cap), 52 PU. */
  private static final String SEED1_MAJOR_BUYS =
      "infantry×5 + infantry×5 + artillery×4 + infantry×2";

  private static CanonicalGameData canonical;

  @BeforeAll
  static void init() {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load(G40 + ".xml");
  }

  @Test
  @DisplayName("seed 1 US purchase at war with minor ICs buys the 30-cap plan")
  void seed1UsAtWarBuysForThirtyCapacity() {
    final WireState wire = usPurchase(52, true);
    final PurchaseRequest request = new PurchaseRequest(wire, 1L, "stand-in-4754", "hard");
    final PurchasePlan plan = new PurchaseExecutor().executeOn(applied(wire), request);

    assertThat(summarize(plan))
        .as(
            "Deleting WarProductionApplier.apply after simulated politics in AbstractProAi.purchase"
                + " must fail this assertion (9-cap minors buy bombers+transport instead)")
        .isEqualTo(SEED1_MAJOR_BUYS);
  }

  @Test
  @DisplayName("US at peace, no war declared, stays 9-cap minor ICs")
  void usAtPeaceKeepsNineCapacity() {
    final GameData data = applied(usPurchase(52, false));
    final GamePlayer americans = player(data, "Americans");

    assertThat(usFactoryTypes(data)).containsOnly("factory_minor");
    assertThat(usProduction(data, americans)).isEqualTo(9);

    WarProductionApplier.apply(data);

    assertThat(usFactoryTypes(data))
        .as("peace: War_Production conditions fail, factories stay minor")
        .containsOnly("factory_minor");
    assertThat(usProduction(data, americans)).isEqualTo(9);
  }

  @Test
  @DisplayName("wire already carrying factory_major is a no-op")
  void wireAlreadyMajorIsUnchanged() {
    final GameData data = applied(usPurchase(52, true, true));
    final GamePlayer americans = player(data, "Americans");

    assertThat(usFactoryTypes(data)).containsOnly("factory_major");
    assertThat(usProduction(data, americans)).isEqualTo(30);
    assertThat(usFactoryCount(data, "factory_major")).isEqualTo(3);

    WarProductionApplier.apply(data);

    assertThat(usFactoryTypes(data))
        .as("XML invert-major condition must leave the wire's factory_major units in place")
        .containsOnly("factory_major");
    assertThat(usFactoryCount(data, "factory_major")).isEqualTo(3);
    assertThat(usProduction(data, americans)).isEqualTo(30);
  }

  private static GameData applied(final WireState wire) {
    final GameData data = canonical.cloneForSession();
    WireStateApplier.apply(data, wire, new ConcurrentHashMap<String, UUID>());
    return data;
  }

  private static WireState usPurchase(final int pus, final boolean atWar) {
    return usPurchase(pus, atWar, false);
  }

  private static WireState usPurchase(
      final int pus, final boolean atWar, final boolean majorsOnWire) {
    final List<WireTerritory> territories =
        majorsOnWire
            ? US_ICS.stream()
                .map(
                    name ->
                        new WireTerritory(
                            name,
                            "Americans",
                            List.of(
                                new WireUnit(
                                    name + "-factory", "factory_major", 0, 0, 0, "Americans"))))
                .toList()
            : List.of();
    return new WireState(
        territories,
        List.of(new WirePlayer("Americans", pus, List.of(), false)),
        3,
        "purchase",
        "Americans",
        atWar ? List.of(new WireRelationship("Americans", "Germans", "war")) : List.of(),
        G40);
  }

  private static GamePlayer player(final GameData data, final String name) {
    return data.getPlayerList().getPlayerId(name);
  }

  private static int usProduction(final GameData data, final GamePlayer americans) {
    int total = 0;
    for (final String name : US_ICS) {
      final Territory t = data.getMap().getTerritoryOrThrow(name);
      total += UnitUtils.getProductionPotentialOfTerritory(t.getUnits(), t, americans, true, true);
    }
    return total;
  }

  private static Set<String> usFactoryTypes(final GameData data) {
    final Set<String> types = new HashSet<>();
    for (final String name : US_ICS) {
      for (final Unit unit : data.getMap().getTerritoryOrThrow(name).getUnits()) {
        if (unit.getType().getName().startsWith("factory_")) {
          types.add(unit.getType().getName());
        }
      }
    }
    return types;
  }

  private static int usFactoryCount(final GameData data, final String typeName) {
    int count = 0;
    for (final String name : US_ICS) {
      for (final Unit unit : data.getMap().getTerritoryOrThrow(name).getUnits()) {
        if (typeName.equals(unit.getType().getName())) {
          count++;
        }
      }
    }
    return count;
  }

  private static String summarize(final PurchasePlan plan) {
    return plan.buys().stream()
        .map(order -> order.unitType() + "×" + order.count())
        .collect(Collectors.joining(" + "));
  }
}
