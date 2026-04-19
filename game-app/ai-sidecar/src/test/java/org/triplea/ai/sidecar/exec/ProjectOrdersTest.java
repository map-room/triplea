package org.triplea.ai.sidecar.exec;

import static games.strategy.triplea.delegate.GameDataTestUtil.bomber;
import static games.strategy.triplea.delegate.GameDataTestUtil.carrier;
import static games.strategy.triplea.delegate.GameDataTestUtil.fighter;
import static games.strategy.triplea.delegate.GameDataTestUtil.infantry;
import static games.strategy.triplea.delegate.GameDataTestUtil.transport;
import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.settings.ClientSetting;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.dto.CombatMoveOrder;

/**
 * Unit tests for {@link ExecutorSupport#projectOrders}.
 *
 * <p>Uses real {@link GameData} from {@link CanonicalGameData} to obtain real unit types so that
 * {@link games.strategy.triplea.attachments.UnitAttachment#isAir()} returns meaningful values.
 * Territories are resolved from the canonical map so that {@link Route#isLoad()} and {@link
 * Route#isUnload()} behave correctly (land→water = load, water→land = unload).
 */
class ProjectOrdersTest {

  private static CanonicalGameData canonical;
  private static GameData data;

  /** A land territory (not water). */
  private static Territory landTerr;

  /** A water territory adjacent to {@link #landTerr}. */
  private static Territory seaTerr;

  private static GamePlayer germans;

  @BeforeAll
  static void init() {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load();
    data = canonical.cloneForSession();
    germans = data.getPlayerList().getPlayerId("Germans");

    // Find a land territory that has at least one water neighbour, so we can form
    // a valid load route (land → water) and unload route (water → land).
    for (final Territory t : data.getMap().getTerritories()) {
      if (t.isWater()) {
        continue;
      }
      for (final Territory neighbour : data.getMap().getNeighbors(t)) {
        if (neighbour.isWater()) {
          landTerr = t;
          seaTerr = neighbour;
          break;
        }
      }
      if (landTerr != null) {
        break;
      }
    }
    assertThat(landTerr).as("must find a land territory adjacent to water").isNotNull();
    assertThat(seaTerr).as("must find a water territory adjacent to land").isNotNull();
  }

  // -----------------------------------------------------------------------
  // Helper: build a wire ID map for a collection of units.
  // -----------------------------------------------------------------------

  private static Map<UUID, String> wireMap(final List<Unit> units) {
    final Map<UUID, String> map = new HashMap<>();
    int i = 0;
    for (final Unit u : units) {
      map.put(u.getId(), "wire-" + i++);
    }
    return map;
  }

  private static List<String> wireIds(final List<Unit> units, final Map<UUID, String> wm) {
    return units.stream().map(u -> wm.get(u.getId())).toList();
  }

  private static Unit unit(final UnitType type) {
    return type.create(germans);
  }

  // -----------------------------------------------------------------------
  // Load branch tests
  // -----------------------------------------------------------------------

  /**
   * Test 1 — Mixed cargo on load: infantry go into a load order; fighter emits as plain move.
   *
   * <p>The move carries 2 infantry (via transport) and 1 fighter. The transport map has
   * {inf1→transport, inf2→transport}. Fighter is not in the transport map.
   */
  @Test
  void loadBranch_mixedCargo_airEmittedAsPlainMove() {
    final Unit inf1 = unit(infantry(data));
    final Unit inf2 = unit(infantry(data));
    final Unit fighter1 = unit(fighter(data));
    final Unit sea = unit(transport(data));

    final List<Unit> allUnits = List.of(inf1, inf2, fighter1, sea);
    final Map<UUID, String> wm = wireMap(allUnits);

    final Route route = new Route(landTerr, seaTerr);
    assertThat(route.isLoad()).as("route must be a load route").isTrue();

    // Only ground units in the transport map.
    final Map<Unit, Unit> seaTransportMap = Map.of(inf1, sea, inf2, sea);
    final MoveDescription move = new MoveDescription(allUnits, route, seaTransportMap);

    final List<CombatMoveOrder> orders = ExecutorSupport.projectOrders(move, wm);

    assertThat(orders).hasSize(2);

    final CombatMoveOrder loadOrder =
        orders.stream().filter(o -> "load".equals(o.kind())).findFirst().orElse(null);
    assertThat(loadOrder).as("must have a load order").isNotNull();
    assertThat(loadOrder.unitIds())
        .containsExactlyInAnyOrder(wm.get(inf1.getId()), wm.get(inf2.getId()));
    assertThat(loadOrder.transportId()).isEqualTo(wm.get(sea.getId()));

    final CombatMoveOrder airOrder =
        orders.stream().filter(o -> "move".equals(o.kind())).findFirst().orElse(null);
    assertThat(airOrder).as("must have a plain-move order for aircraft").isNotNull();
    assertThat(airOrder.unitIds()).containsExactly(wm.get(fighter1.getId()));
    assertThat(airOrder.transportId()).isNull();
  }

  /**
   * Test 2 — Air-in-cargoByTransport (the bug pattern): move contains 1 fighter, and TripleA's
   * internal representation puts the fighter in {@code getUnitsToSeaTransports()} paired with a
   * carrier. Expect 1 plain move order for the fighter (no load, no carrier ID).
   */
  @Test
  void loadBranch_aircraftInTransportMap_emittedAsPlainMoveNotLoad() {
    final Unit fighter1 = unit(fighter(data));
    final Unit carrierUnit = unit(carrier(data));

    final List<Unit> allUnits = List.of(fighter1, carrierUnit);
    final Map<UUID, String> wm = wireMap(allUnits);

    final Route route = new Route(landTerr, seaTerr);
    assertThat(route.isLoad()).as("route must be a load route").isTrue();

    // Simulates TripleA including an air→carrier pair in the map.
    final Map<Unit, Unit> seaTransportMap = Map.of(fighter1, carrierUnit);
    final MoveDescription move = new MoveDescription(allUnits, route, seaTransportMap);

    final List<CombatMoveOrder> orders = ExecutorSupport.projectOrders(move, wm);

    // No load orders — carrier pairing must be filtered out.
    assertThat(orders.stream().anyMatch(o -> "load".equals(o.kind())))
        .as("no load orders — aircraft must not be treated as transport cargo")
        .isFalse();

    // One plain-move order for the aircraft.
    assertThat(orders).hasSize(1);
    final CombatMoveOrder airOrder = orders.get(0);
    assertThat(airOrder.kind()).isEqualTo("move");
    assertThat(airOrder.unitIds()).containsExactly(wm.get(fighter1.getId()));
    assertThat(airOrder.transportId()).isNull();
  }

  /**
   * Test 3 — Existing load grouping regression: 3 infantry across 2 transports. Expect exactly 2
   * load orders (one per transport), no air order.
   */
  @Test
  void loadBranch_multipleTransports_oneOrderPerTransport() {
    final Unit inf1 = unit(infantry(data));
    final Unit inf2 = unit(infantry(data));
    final Unit inf3 = unit(infantry(data));
    final Unit sea1 = unit(transport(data));
    final Unit sea2 = unit(transport(data));

    final List<Unit> allUnits = List.of(inf1, inf2, inf3, sea1, sea2);
    final Map<UUID, String> wm = wireMap(allUnits);

    final Route route = new Route(landTerr, seaTerr);
    assertThat(route.isLoad()).isTrue();

    final Map<Unit, Unit> seaTransportMap = Map.of(inf1, sea1, inf2, sea1, inf3, sea2);
    final MoveDescription move = new MoveDescription(allUnits, route, seaTransportMap);

    final List<CombatMoveOrder> orders = ExecutorSupport.projectOrders(move, wm);

    final List<CombatMoveOrder> loadOrders =
        orders.stream().filter(o -> "load".equals(o.kind())).toList();
    assertThat(loadOrders).as("must produce 2 load orders (one per transport)").hasSize(2);
    assertThat(orders.stream().anyMatch(o -> "move".equals(o.kind())))
        .as("no plain-move orders — no aircraft in this move")
        .isFalse();

    // Verify cargo assignment by transport.
    final CombatMoveOrder orderForSea1 =
        loadOrders.stream()
            .filter(o -> wm.get(sea1.getId()).equals(o.transportId()))
            .findFirst()
            .orElse(null);
    assertThat(orderForSea1).as("load order for sea1 must exist").isNotNull();
    assertThat(orderForSea1.unitIds())
        .containsExactlyInAnyOrder(wm.get(inf1.getId()), wm.get(inf2.getId()));

    final CombatMoveOrder orderForSea2 =
        loadOrders.stream()
            .filter(o -> wm.get(sea2.getId()).equals(o.transportId()))
            .findFirst()
            .orElse(null);
    assertThat(orderForSea2).as("load order for sea2 must exist").isNotNull();
    assertThat(orderForSea2.unitIds()).containsExactly(wm.get(inf3.getId()));
  }

  // -----------------------------------------------------------------------
  // Unload branch tests
  // -----------------------------------------------------------------------

  /**
   * Test 4 — Mixed cargo unload: 2 infantry + 2 bombers. Expect 2 orders: one {@code kind="unload"}
   * for infantry, one plain move for bombers.
   */
  @Test
  void unloadBranch_mixedCargo_airEmittedAsPlainMove() {
    final Unit inf1 = unit(infantry(data));
    final Unit inf2 = unit(infantry(data));
    final Unit bomber1 = unit(bomber(data));
    final Unit bomber2 = unit(bomber(data));
    final Unit sea = unit(transport(data));

    final List<Unit> allUnits = List.of(inf1, inf2, bomber1, bomber2, sea);
    final Map<UUID, String> wm = wireMap(allUnits);

    final Route route = new Route(seaTerr, landTerr);
    assertThat(route.isUnload()).as("route must be an unload route").isTrue();

    final MoveDescription move = new MoveDescription(allUnits, route, Map.of());

    final List<CombatMoveOrder> orders = ExecutorSupport.projectOrders(move, wm);

    assertThat(orders).hasSize(2);

    final CombatMoveOrder unloadOrder =
        orders.stream().filter(o -> "unload".equals(o.kind())).findFirst().orElse(null);
    assertThat(unloadOrder).as("must have an unload order").isNotNull();
    assertThat(unloadOrder.unitIds())
        .containsExactlyInAnyOrder(wm.get(inf1.getId()), wm.get(inf2.getId()));

    final CombatMoveOrder airOrder =
        orders.stream().filter(o -> "move".equals(o.kind())).findFirst().orElse(null);
    assertThat(airOrder).as("must have a plain-move order for aircraft").isNotNull();
    assertThat(airOrder.unitIds())
        .containsExactlyInAnyOrder(wm.get(bomber1.getId()), wm.get(bomber2.getId()));
    assertThat(airOrder.transportId()).isNull();
  }

  /**
   * Test 5 — Air-only unload (from german-turn.log 2026-04-19): move contains only aircraft,
   * route.isUnload()=true. Expect 1 plain move order; no unload order emitted.
   */
  @Test
  void unloadBranch_airOnly_noUnloadOrderEmitted() {
    final Unit fighter1 = unit(fighter(data));
    final Unit bomber1 = unit(bomber(data));

    final List<Unit> allUnits = List.of(fighter1, bomber1);
    final Map<UUID, String> wm = wireMap(allUnits);

    final Route route = new Route(seaTerr, landTerr);
    assertThat(route.isUnload()).as("route must be an unload route").isTrue();

    final MoveDescription move = new MoveDescription(allUnits, route, Map.of());

    final List<CombatMoveOrder> orders = ExecutorSupport.projectOrders(move, wm);

    assertThat(orders.stream().anyMatch(o -> "unload".equals(o.kind())))
        .as("no unload order — aircraft are not cargo")
        .isFalse();

    assertThat(orders).hasSize(1);
    final CombatMoveOrder airOrder = orders.get(0);
    assertThat(airOrder.kind()).isEqualTo("move");
    assertThat(airOrder.unitIds())
        .containsExactlyInAnyOrder(wm.get(fighter1.getId()), wm.get(bomber1.getId()));
  }

  // -----------------------------------------------------------------------
  // Plain-move branch (unchanged)
  // -----------------------------------------------------------------------

  /**
   * Test 6 — Pure plain move: route.isLoad()=false, route.isUnload()=false. Unchanged behavior:
   * single order with all units, kind="move".
   */
  @Test
  void plainMove_allUnitsInSingleOrder() {
    // Pick two land territories to get a plain-move route.
    final Territory t1 =
        data.getMap().getTerritories().stream().filter(t -> !t.isWater()).findFirst().orElseThrow();
    // Find a second land territory adjacent to t1 (or any land territory if none adjacent).
    final Territory t2 =
        data.getMap().getNeighbors(t1).stream()
            .filter(t -> !t.isWater())
            .findFirst()
            .orElseGet(
                () ->
                    data.getMap().getTerritories().stream()
                        .filter(t -> !t.isWater() && !t.equals(t1))
                        .findFirst()
                        .orElseThrow());

    final Unit inf1 = unit(infantry(data));
    final Unit inf2 = unit(infantry(data));
    final List<Unit> allUnits = List.of(inf1, inf2);
    final Map<UUID, String> wm = wireMap(allUnits);

    // Build a plain route (land → land); isLoad and isUnload must both be false.
    final Route route = new Route(t1, t2);
    assertThat(route.isLoad()).isFalse();
    assertThat(route.isUnload()).isFalse();

    final MoveDescription move = new MoveDescription(allUnits, route, Map.of());

    final List<CombatMoveOrder> orders = ExecutorSupport.projectOrders(move, wm);

    assertThat(orders).hasSize(1);
    final CombatMoveOrder order = orders.get(0);
    assertThat(order.kind()).isEqualTo("move");
    assertThat(order.unitIds())
        .containsExactlyInAnyOrder(wm.get(inf1.getId()), wm.get(inf2.getId()));
    assertThat(order.transportId()).isNull();
  }
}
