package games.strategy.triplea.ai.pro.util;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.ai.pro.data.AmphContext;
import games.strategy.triplea.delegate.GameDataTestUtil;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

/**
 * Tests for {@link ProAmphUtils#getAmphReachableTerritories} — the unified reachability primitive.
 *
 * <p>All tests use G40 Pacific geography. American vs Japanese war declared.
 *
 * <p>Map reference (Pacific region):
 *
 * <ul>
 *   <li>Korea (land, adj to 6 Sea Zone) → 6 Sea Zone (adj to Japan, 5 SZ, 7 SZ, 16 SZ, 17 SZ, 18
 *       SZ, 19 SZ)
 *   <li>5 Sea Zone (adj to Soviet Far East, Amur, Siberia, 7 SZ, 4 SZ, 6 SZ)
 *   <li>Japan (land, adj to 6 SZ — initial owner: Japanese)
 * </ul>
 *
 * <p>Resolves: map-room#2716 (NAVAL_MOVE budget), map-room#2717a (already-embarked NCM move),
 * map-room#2712 (AA policy), guards (§7.10), CM 1-hop (§7.11).
 */
public class ProAmphUtilsGetAmphReachableTest {

  private static final String KOREA = "Korea";
  private static final String SEA_ZONE_6 = "6 Sea Zone";
  private static final String SEA_ZONE_5 = "5 Sea Zone";
  private static final String JAPAN = "Japan";

  private GameData data;
  private GamePlayer americans;
  private GamePlayer japanese;

  @BeforeEach
  void setUp() {
    ClientSetting.setPreferences(new MemoryPreferences());
    data = TestMapGameData.GLOBAL1940.getGameData();
    americans = data.getPlayerList().getPlayerId("Americans");
    japanese = data.getPlayerList().getPlayerId("Japanese");
    declareWar(americans, japanese);
    // Clear all units for a stable, predictable map state.
    for (final Territory t : data.getMap().getTerritories()) {
      data.performChange(ChangeFactory.removeUnits(t, t.getUnits()));
    }
  }

  // ─── toggle-off (§7.13) ───────────────────────────────────────────────────

  @Test
  void disabledContextReturnsEmpty() {
    final Territory korea = data.getMap().getTerritoryOrThrow(KOREA);
    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(korea, List.of(infantry)));

    final Map<Territory, Route> result =
        ProAmphUtils.getAmphReachableTerritories(
            infantry, korea, false, AmphContext.DISABLED, americans);

    assertThat(result).as("AmphContext.DISABLED must return empty for any unit/position").isEmpty();
  }

  // ─── non-embarked, combat move (§6.1 D) ──────────────────────────────────

  @Test
  void nonEmbarkedCombatMoveReturnsEmpty() {
    // (D) non-embarked land unit in CM has no amphib destinations.
    final Territory korea = data.getMap().getTerritoryOrThrow(KOREA);
    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(korea, List.of(infantry)));
    final AmphContext ctx = new AmphContext(true, Set.of(), Set.of());

    final Map<Territory, Route> result =
        ProAmphUtils.getAmphReachableTerritories(infantry, korea, true, ctx, americans);

    assertThat(result)
        .as("Non-embarked land unit in CM has no amphib destinations (case D)")
        .isEmpty();
  }

  // ─── hasMoved guard (§7.10) ───────────────────────────────────────────────

  @Test
  void hasMovedGuardReturnsEmptyForNonEmbarked() {
    // A land unit that has already moved this turn has no further NCM destinations.
    final Territory korea = data.getMap().getTerritoryOrThrow(KOREA);
    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(korea, List.of(infantry)));
    // Mark unit as having moved (alreadyMoved > 0)
    data.performChange(ChangeFactory.markNoMovementChange(List.of(infantry)));
    final AmphContext ctx = new AmphContext(true, Set.of(), Set.of());

    final Map<Territory, Route> result =
        ProAmphUtils.getAmphReachableTerritories(infantry, korea, false, ctx, americans);

    assertThat(result)
        .as("Unit with hasMoved()=true must yield empty NCM reachable set (mirrors :1473)")
        .isEmpty();
  }

  // ─── #2716: NAVAL_MOVE budget — non-embarked unit reaches beyond adjacent SZ ───

  /**
   * Regression for map-room#2716: a non-embarked infantry (land move = 1) must be able to transit
   * to sea zones beyond the first adjacent SZ using the NAVAL_MOVE = 2 budget.
   *
   * <p>Before the fix: {@code maxHops = u.getMovementLeft() = 1} → only 6 Sea Zone (adjacent).
   * After the fix: sea BFS runs with budget = {@code NAVAL_MOVE = 2} from each adjacent SZ, so 5
   * Sea Zone (1 sea hop from 6 SZ) is also reachable.
   */
  @Test
  void infantryInKoreaReachesAdjacentAndTransitSeaZones_issue2716() {
    final Territory korea = data.getMap().getTerritoryOrThrow(KOREA);
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);
    final Territory sz5 = data.getMap().getTerritoryOrThrow(SEA_ZONE_5);
    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(korea, List.of(infantry)));
    final AmphContext ctx = new AmphContext(true, Set.of(), Set.of());

    final Map<Territory, Route> result =
        ProAmphUtils.getAmphReachableTerritories(infantry, korea, false, ctx, americans);

    assertThat(result)
        .as("6 Sea Zone (adjacent, free embark) must be in reachable set")
        .containsKey(sz6);
    assertThat(result)
        .as(
            "5 Sea Zone (1 sea hop from 6 SZ, within NAVAL_MOVE=2) must be in reachable set. "
                + "#2716: pre-fix code used land movement (1) as maxHops → only 6 SZ reachable.")
        .containsKey(sz5);
  }

  @Test
  void nonEmbarkedUnitSeaDestinationsAreAllWaterTerritories() {
    final Territory korea = data.getMap().getTerritoryOrThrow(KOREA);
    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(korea, List.of(infantry)));
    final AmphContext ctx = new AmphContext(true, Set.of(), Set.of());

    final Map<Territory, Route> result =
        ProAmphUtils.getAmphReachableTerritories(infantry, korea, false, ctx, americans);

    // Case A: non-embarked NCM destinations are all sea zones (no disembark)
    assertThat(result.keySet())
        .as("Non-embarked NCM: all destinations must be sea zones (no disembark in same turn)")
        .allMatch(Territory::isWater);
  }

  // ─── #2717a: already-embarked gets NCM move ───────────────────────────────

  /**
   * Regression for map-room#2717a: an already-embarked unit (prior turn) must receive a non-empty
   * NCM reachable set. The pre-fix {@code !isEmbarked} filter in {@code calculateAmphEmbarkMoves}
   * caused all staged units to be silently skipped.
   */
  @Test
  void alreadyEmbarkedInfantryGetsNcmTransitDestinations_issue2717a() {
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);
    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(sz6, List.of(infantry)));
    // Unit is embarked from a prior turn, NOT embarked this turn.
    final AmphContext ctx = new AmphContext(true, Set.of(infantry.getId()), Set.of());

    final Map<Territory, Route> result =
        ProAmphUtils.getAmphReachableTerritories(infantry, sz6, false, ctx, americans);

    assertThat(result)
        .as("Already-embarked unit must get a non-empty NCM reachable set")
        .isNotEmpty();
    assertThat(result)
        .as("5 Sea Zone (adjacent to 6 SZ, clear transit) must be reachable for embarked NCM")
        .containsKey(data.getMap().getTerritoryOrThrow(SEA_ZONE_5));
  }

  @Test
  void alreadyEmbarkedTransitDestinationsAreAllSeaZones() {
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);
    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(sz6, List.of(infantry)));
    final AmphContext ctx = new AmphContext(true, Set.of(infantry.getId()), Set.of());

    final Map<Territory, Route> result =
        ProAmphUtils.getAmphReachableTerritories(infantry, sz6, false, ctx, americans);

    // With Japan hostile and Korea also hostile (default G40), all adjacent land is enemy.
    // So no friendly-coast disembark target here — all destinations are sea zones.
    // (Korea is Japanese-owned in default setup; Japan is Japanese.)
    final long friendlyLandCount =
        result.keySet().stream()
            .filter(t -> !t.isWater())
            .filter(t -> Matches.isTerritoryFriendly(americans).test(t))
            .count();
    final long seaCount = result.keySet().stream().filter(Territory::isWater).count();
    assertThat(seaCount)
        .as("At least the adjacent SZs should be reachable transit targets")
        .isPositive();
    // Friendly land disembark check - zero in default setup (all adjacent land is Japanese)
    assertThat(friendlyLandCount)
        .as("No friendly land adjacent to 6 SZ in default G40 setup → 0 friendly disembark targets")
        .isEqualTo(0);
  }

  // ─── embarked NCM — disembark to friendly coast ───────────────────────────

  @Test
  void embarkedUnitDisembarksToFriendlyAdjacentCoast() {
    // Transfer Korea to Americans so it becomes a friendly disembark target adjacent to 6 SZ.
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);
    final Territory korea = data.getMap().getTerritoryOrThrow(KOREA);
    data.performChange(ChangeFactory.changeOwner(korea, americans));

    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(sz6, List.of(infantry)));
    // Embarked from prior turn, NOT embarked this turn.
    final AmphContext ctx = new AmphContext(true, Set.of(infantry.getId()), Set.of());

    final Map<Territory, Route> result =
        ProAmphUtils.getAmphReachableTerritories(infantry, sz6, false, ctx, americans);

    assertThat(result)
        .as(
            "Korea (now American-owned, adjacent to 6 SZ) must be a friendly disembark target "
                + "for an embarked infantry doing NCM")
        .containsKey(korea);
    final Route disembarkRoute = result.get(korea);
    assertThat(disembarkRoute.getStart())
        .as("Disembark route must start at the staging sea zone (6 SZ)")
        .isEqualTo(sz6);
    assertThat(disembarkRoute.getEnd())
        .as("Disembark route must end at the friendly coast (Korea)")
        .isEqualTo(korea);
  }

  // ─── embarkedThisTurn guard — no disembark (§7.10) ───────────────────────

  @Test
  void freshlyEmbarkedUnitHasNoNcmDisembarkTargets() {
    // Transfer Korea to Americans so it would be a disembark target — but embarkedThisTurn
    // must block it.
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);
    final Territory korea = data.getMap().getTerritoryOrThrow(KOREA);
    data.performChange(ChangeFactory.changeOwner(korea, americans));

    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(sz6, List.of(infantry)));
    // Embarked this turn → blocks disembark
    final AmphContext ctx =
        new AmphContext(true, Set.of(infantry.getId()), Set.of(infantry.getId()));

    final Map<Territory, Route> result =
        ProAmphUtils.getAmphReachableTerritories(infantry, sz6, false, ctx, americans);

    assertThat(result)
        .as("Freshly-embarked unit (embarkedThisTurn) must NOT disembark in the same NCM turn")
        .doesNotContainKey(korea);
    // But sea-zone transit IS allowed (embarkedThisTurn only blocks disembark, not transit)
    assertThat(result.keySet())
        .as("Freshly-embarked unit may still transit to other sea zones in NCM")
        .anyMatch(Territory::isWater);
  }

  // ─── CM assault — 1-hop hostile coasts only (§7.11) ──────────────────────

  @Test
  void embarkedInfantryCombatMovReachesAdjacentHostileCoastOnly() {
    // In 6 Sea Zone: Japan (hostile) is adjacent. Korea is also adjacent but needs ownership check.
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);
    final Territory japan = data.getMap().getTerritoryOrThrow(JAPAN);
    // Japan stays Japanese-owned (hostile).
    data.performChange(ChangeFactory.changeOwner(japan, japanese));

    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(sz6, List.of(infantry)));
    // Embarked from prior turn, NOT this turn.
    final AmphContext ctx = new AmphContext(true, Set.of(infantry.getId()), Set.of());

    final Map<Territory, Route> result =
        ProAmphUtils.getAmphReachableTerritories(infantry, sz6, true, ctx, americans);

    assertThat(result)
        .as("Japan (hostile, 1 hop from 6 SZ) must be a CM assault target")
        .containsKey(japan);
    assertThat(result.keySet())
        .as("CM assault destinations must all be land territories (no sea-zone destinations)")
        .noneMatch(Territory::isWater);
    for (final Territory dest : result.keySet()) {
      assertThat(data.getMap().getNeighbors(sz6))
          .as("CM assault destination %s must be exactly 1 hop from staging SZ", dest.getName())
          .contains(dest);
    }
  }

  @Test
  void embarkedThisTurnBlocksCombatAssault() {
    // embarkedThisTurn → no CM assault (same-turn guard).
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);
    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(sz6, List.of(infantry)));
    // Embarked this turn
    final AmphContext ctx =
        new AmphContext(true, Set.of(infantry.getId()), Set.of(infantry.getId()));

    final Map<Territory, Route> result =
        ProAmphUtils.getAmphReachableTerritories(infantry, sz6, true, ctx, americans);

    assertThat(result)
        .as(
            "embarkedThisTurn must block CM assault (mirrors TS engine :1296): "
                + "same-turn embark+assault not allowed")
        .isEmpty();
  }

  // ─── #2712: AA / attack-0 excluded from CM assault (§7.5d) ───────────────

  /**
   * Regression for map-room#2712: an AA gun (attack=0) must return empty CM destinations.
   *
   * <p>AA guns can embark and NCM-disembark freely, but must never appear in CM assault candidates
   * — they cannot contribute to a land battle and would occupy a staging SZ uselessly.
   */
  @Test
  void aaGunEmbarkedCombatMoveReturnsEmpty_issue2712d() {
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);
    final Unit aaGun = GameDataTestUtil.aaGun(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(sz6, List.of(aaGun)));
    // Embarked from prior turn
    final AmphContext ctx = new AmphContext(true, Set.of(aaGun.getId()), Set.of());

    final Map<Territory, Route> result =
        ProAmphUtils.getAmphReachableTerritories(aaGun, sz6, true, ctx, americans);

    assertThat(result)
        .as(
            "AA gun (attack=0) must return empty CM destinations — "
                + "AA cannot contribute to assault and must not appear in CM candidates (#2712)")
        .isEmpty();
  }

  @Test
  void aaGunEmbarkedNcmHasTransitDestinations() {
    // AA should still be allowed to transit in NCM (only CM assault is excluded).
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);
    final Unit aaGun = GameDataTestUtil.aaGun(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(sz6, List.of(aaGun)));
    // Embarked from prior turn
    final AmphContext ctx = new AmphContext(true, Set.of(aaGun.getId()), Set.of());

    final Map<Territory, Route> result =
        ProAmphUtils.getAmphReachableTerritories(aaGun, sz6, false, ctx, americans);

    assertThat(result)
        .as("AA gun should still have NCM transit destinations (excluded only from CM assault)")
        .isNotEmpty();
  }

  // ─── route structure ─────────────────────────────────────────────────────

  @Test
  void routeStartIsFromAndEndIsDestination() {
    final Territory korea = data.getMap().getTerritoryOrThrow(KOREA);
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);
    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(korea, List.of(infantry)));
    final AmphContext ctx = new AmphContext(true, Set.of(), Set.of());

    final Map<Territory, Route> result =
        ProAmphUtils.getAmphReachableTerritories(infantry, korea, false, ctx, americans);

    assertThat(result).containsKey(sz6);
    final Route route = result.get(sz6);
    assertThat(route.getStart()).as("Route start must equal 'from' (Korea)").isEqualTo(korea);
    assertThat(route.getEnd()).as("Route end must equal the destination (6 SZ)").isEqualTo(sz6);
  }

  // ─── contested SZ excluded ────────────────────────────────────────────────

  @Test
  void contestedSeaZoneIsExcludedFromReachable() {
    // Place an enemy destroyer in 6 SZ — makes it contested.
    final Territory korea = data.getMap().getTerritoryOrThrow(KOREA);
    final Territory sz6 = data.getMap().getTerritoryOrThrow(SEA_ZONE_6);
    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(korea, List.of(infantry)));
    final Unit destroyer = GameDataTestUtil.destroyer(data).create(1, japanese).get(0);
    data.performChange(ChangeFactory.addUnits(sz6, List.of(destroyer)));
    final AmphContext ctx = new AmphContext(true, Set.of(), Set.of());

    final Map<Territory, Route> result =
        ProAmphUtils.getAmphReachableTerritories(infantry, korea, false, ctx, americans);

    assertThat(result)
        .as("Contested sea zone (enemy warship present) must be excluded from reachable set")
        .doesNotContainKey(sz6);
  }

  // ─── NAVAL_MOVE constant ─────────────────────────────────────────────────

  @Test
  void navalMoveConstantIsTwo() {
    assertThat(ProAmphUtils.NAVAL_MOVE)
        .as("NAVAL_MOVE must equal 2 (matches TS engine tech-effects.ts:6)")
        .isEqualTo(2);
  }

  // ─── helpers ─────────────────────────────────────────────────────────────

  private void declareWar(final GamePlayer p1, final GamePlayer p2) {
    data.performChange(
        ChangeFactory.relationshipChange(
            p1,
            p2,
            data.getRelationshipTracker().getRelationshipType(p1, p2),
            data.getRelationshipTypeList().getRelationshipType("War")));
  }
}
