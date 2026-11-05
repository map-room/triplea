package org.triplea.ai.sidecar.exec;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.Constants;
import games.strategy.triplea.settings.ClientSetting;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.dto.PurchaseOrder;
import org.triplea.ai.sidecar.dto.PurchasePlan;
import org.triplea.ai.sidecar.dto.PurchaseRequest;
import org.triplea.ai.sidecar.wire.WireState;

/**
 * map-room#4617 item 1: the British purchase plan charged more to the Europe pool than that pool
 * held (42 vs 40, 73 vs 66, 53 vs 47 in the phase-1 run) while the Pacific pool sat underused. Map
 * Room pays each buy from the pool of its placement territory, so the engine refuses the buys the
 * pool cannot cover.
 *
 * <p>The input is the real Global 1940 board Map Room's {@code toWireState} produced (shared with
 * {@code UkPacificCapitalsWireTest}), with the two pools rewritten.
 */
class PurchaseUkPools4617Test {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static JsonNode tree;
  private static CanonicalGameData canonical;

  @BeforeAll
  static void load() throws Exception {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load("ww2global40_2nd_edition.xml");
    try (InputStream in =
        PurchaseUkPools4617Test.class
            .getClassLoader()
            .getResourceAsStream("wire-contract/wire-state-g1940-uk-economies.json")) {
      tree = MAPPER.readTree(in);
    }
  }

  private static WireState withPools(final int europe, final int pacific) throws Exception {
    final JsonNode copy = tree.deepCopy();
    for (final JsonNode p : copy.get("players")) {
      if ("British".equals(p.get("playerId").asText())) {
        ((ObjectNode) p).put("europePus", europe).put("pacificPus", pacific);
        ((ObjectNode) p).put("pus", europe + pacific);
      }
    }
    return MAPPER.treeToValue(copy, WireState.class);
  }

  private static Map<String, Integer> unitCosts(final GameData data) {
    final Map<String, Integer> out = new HashMap<>();
    for (final ProductionRule rule : data.getProductionRuleList().getProductionRules()) {
      if (rule.getResults().size() != 1) {
        continue;
      }
      final var entry = rule.getResults().entrySet().iterator().next();
      if (entry.getValue() != 1 || !(entry.getKey() instanceof UnitType)) {
        continue;
      }
      out.put(
          ((UnitType) entry.getKey()).getName(),
          rule.getCosts().getInt(data.getResourceList().getResourceOrThrow(Constants.PUS)));
    }
    return out;
  }

  private static Map<String, String> economyByTerritory() {
    final Map<String, String> out = new HashMap<>();
    for (final JsonNode t : tree.get("territories")) {
      if (t.hasNonNull("economy")) {
        out.put(t.get("territoryId").asText(), t.get("economy").asText());
      }
    }
    return out;
  }

  @Test
  void noPoolIsCharged_moreThanItHolds() throws Exception {
    final int[][] pools = {{28, 17}, {40, 19}, {66, 12}, {47, 30}, {20, 40}, {59, 0}};
    final Map<String, String> economy = economyByTerritory();
    final List<String> overdrawn = new ArrayList<>();
    for (final int[] p : pools) {
      for (long seed = 1; seed <= 6; seed++) {
        final GameData data = canonical.cloneForSession();
        final Map<String, Integer> cost = unitCosts(data);
        final WireState state = withPools(p[0], p[1]);
        final PurchasePlan plan =
            new PurchaseExecutor().executeOn(data, new PurchaseRequest(state, seed));
        int europe = 0;
        int pacific = 0;
        int unpooled = 0;
        for (final PurchaseOrder o : plan.buys()) {
          final int spend = cost.get(o.unitType()) * o.count();
          final String pool = economy.get(o.placeTerritory());
          if ("pacific".equals(pool)) {
            pacific += spend;
          } else if ("europe".equals(pool)) {
            europe += spend;
          } else {
            unpooled += spend;
          }
        }
        if (europe > p[0] || pacific > p[1] || unpooled > 0) {
          overdrawn.add(
              String.format(
                  "pools=%d/%d seed=%d europe=%d pacific=%d unpooled=%d",
                  p[0], p[1], seed, europe, pacific, unpooled));
        }
      }
    }
    assertThat(overdrawn).isEmpty();
  }
}
