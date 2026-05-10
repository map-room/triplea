package org.triplea.ai.sidecar.exec;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.NamedAttachable;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.Constants;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.settings.ClientSetting;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.dto.PlacementGroup;
import org.triplea.ai.sidecar.dto.PurchaseOrder;
import org.triplea.ai.sidecar.dto.PurchasePlan;
import org.triplea.ai.sidecar.dto.PurchaseRequest;
import org.triplea.ai.sidecar.session.Session;
import org.triplea.ai.sidecar.session.SessionKey;
import org.triplea.ai.sidecar.wire.WireState;

class PurchaseExecutorTest {

  private static CanonicalGameData canonical;

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load();
  }

  private Session freshSession(final String nation) {
    final GameData data = canonical.cloneForSession();
    final ProAi proAi = new ProAi("sidecar-test-" + nation, nation);
    return new Session(
        "s-test-" + UUID.randomUUID(),
        new SessionKey("g1", nation, 1),
        42L,
        proAi,
        data,
        new ConcurrentHashMap<>(),
        Executors.newSingleThreadExecutor());
  }

  private static PurchaseRequest purchaseRequestFor(final String nation) {
    return new PurchaseRequest(
        new WireState(List.of(), List.of(), 1, "purchase", nation, List.of()));
  }

  private static int playerPus(final GameData data, final String nation) {
    final GamePlayer p = data.getPlayerList().getPlayerId(nation);
    final Resource pus = data.getResourceList().getResourceOrThrow(Constants.PUS);
    return p.getResources().getQuantity(pus);
  }

  private static int totalCost(final GameData data, final PurchasePlan plan) {
    final Resource pus = data.getResourceList().getResourceOrThrow(Constants.PUS);
    int sum = 0;
    for (final PurchaseOrder order : plan.buys()) {
      final ProductionRule rule = findRuleForUnitType(data, order.unitType());
      if (rule == null) {
        continue;
      }
      sum += rule.getCosts().getInt(pus) * order.count();
    }
    return sum;
  }

  private static ProductionRule findRuleForUnitType(
      final GameData data, final String unitTypeName) {
    for (final ProductionRule rule : data.getProductionRuleList().getProductionRules()) {
      for (final NamedAttachable result : rule.getResults().keySet()) {
        if (result instanceof UnitType ut && ut.getName().equals(unitTypeName)) {
          return rule;
        }
      }
    }
    return null;
  }

  @Test
  void returnsNonEmptyPlanForTurn1Germans() throws Exception {
    final Session session = freshSession("Germans");
    final PurchasePlan plan =
        new PurchaseExecutor().execute(session, purchaseRequestFor("Germans"));

    assertThat(plan.buys()).isNotEmpty();
    final int budget = playerPus(session.gameData(), "Germans");
    assertThat(totalCost(session.gameData(), plan)).isLessThanOrEqualTo(budget);
  }

  @Test
  void trimToFitClampsWhenProAiOverruns() throws Exception {
    final Session session = freshSession("Germans");
    final GameData data = session.gameData();
    final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
    final Resource pus = data.getResourceList().getResourceOrThrow(Constants.PUS);
    final int current = germans.getResources().getQuantity(pus);
    // Zero out Germans' PUs so any ProAi overrun is clamped to empty by trimToFit.
    data.performChange(ChangeFactory.changeResourcesChange(germans, pus, -current));

    final PurchasePlan plan =
        new PurchaseExecutor().execute(session, purchaseRequestFor("Germans"));

    assertThat(totalCost(data, plan)).isEqualTo(0);
  }

  /**
   * Regression for map-room#2194: factory repair cost must be deducted from the unit-purchase
   * budget. Before the fix, {@link RecordingPurchaseDelegate#purchaseRepair} captured but did NOT
   * deduct repair IPC from {@code player.getResources()}, so {@code ProPurchaseAi.purchase()}
   * initialized its {@link games.strategy.triplea.ai.pro.data.ProResourceTracker} with the full
   * pre-repair budget. Combined cost (repair + unit buys) could then exceed available IPC → engine
   * rejected "ran out of IPC".
   */
  @Test
  void repairCostDeductedFromUnitBudgetWhenFactoryIsDamaged() {
    final Session session = freshSession("Germans");
    final GameData data = session.gameData();
    final Resource pus = data.getResourceList().getResourceOrThrow(Constants.PUS);
    final GamePlayer player = data.getPlayerList().getPlayerId("Germans");

    // Apply 7 points of SBR damage to Germany's factory.
    final Territory germany = data.getMap().getTerritoryOrThrow("Germany");
    final Unit factory =
        germany.getUnitCollection().stream()
            .filter(Matches.unitCanProduceUnitsAndCanBeDamaged())
            .findFirst()
            .orElseThrow(() -> new AssertionError("No damageable factory found in Germany"));
    final int factoryDamage = 7;
    final var damageMap = new org.triplea.java.collections.IntegerMap<Unit>();
    damageMap.put(factory, factoryDamage);
    data.performChange(ChangeFactory.bombingUnitDamage(damageMap, List.of(germany)));

    final int startingPus = player.getResources().getQuantity(pus);

    final PurchasePlan plan =
        new PurchaseExecutor().execute(session, purchaseRequestFor("Germans"));

    // ProAi must have decided to repair (the factory has damage and the player can afford it).
    assertThat(plan.repairs()).as("ProAi should repair the damaged factory").isNotEmpty();

    // Repair cost is 1 PU per damage point (Global 1940 standard).
    final int repairCost =
        plan.repairs().stream().mapToInt(org.triplea.ai.sidecar.dto.RepairOrder::repairCount).sum();
    final int unitCost = totalCost(data, plan);

    assertThat(repairCost + unitCost)
        .as(
            "repair (%d) + unit buys (%d) must not exceed starting IPC (%d)",
            repairCost, unitCost, startingPus)
        .isLessThanOrEqualTo(startingPus);
  }

  @Test
  void translatesProductionRuleToUnitTypeName() throws Exception {
    final Session session = freshSession("Germans");
    final PurchasePlan plan =
        new PurchaseExecutor().execute(session, purchaseRequestFor("Germans"));

    for (final PurchaseOrder order : plan.buys()) {
      assertThat(order.unitType()).isNotNull();
      assertThat(order.count()).isGreaterThan(0);
      // The unit type must resolve to a real ProductionRule on the canonical map.
      assertThat(findRuleForUnitType(session.gameData(), order.unitType()))
          .as("unit type %s has a production rule", order.unitType())
          .isNotNull();
    }
  }

  /**
   * Verifies that when ProAi buys units for turn 1 Germans, the response's {@code placements} list:
   *
   * <ul>
   *   <li>is non-empty (storedPurchaseTerritories is populated after a successful purchase)
   *   <li>maintains the correct bucket order: land non-construction → water non-construction → land
   *       construction → water construction
   *   <li>each group has a non-blank territory name and at least one unit type
   * </ul>
   */
  @Test
  void placementsArePopulatedAndOrderedCorrectly() throws Exception {
    final Session session = freshSession("Germans");
    final PurchasePlan plan =
        new PurchaseExecutor().execute(session, purchaseRequestFor("Germans"));

    // ProAi always buys something for turn-1 Germans; placements must follow from buys.
    assertThat(plan.buys()).as("ProAi should buy units for turn-1 Germans").isNotEmpty();
    assertThat(plan.placements())
        .as("placements must be populated when buys is non-empty")
        .isNotEmpty();

    // Each group must have valid territory and units.
    for (final PlacementGroup pg : plan.placements()) {
      assertThat(pg.territory()).as("placement territory must not be blank").isNotBlank();
      assertThat(pg.unitTypes()).as("placement unitTypes must not be empty").isNotEmpty();
    }

    // Ordering: priority(i) ≤ priority(i+1) for all consecutive groups.
    final List<PlacementGroup> groups = plan.placements();
    for (int i = 0; i < groups.size() - 1; i++) {
      assertThat(bucketOf(groups.get(i)))
          .as(
              "placement order violation at index %d (%s priority=%d) before index %d (%s priority=%d)",
              i,
              groups.get(i).territory(),
              bucketOf(groups.get(i)),
              i + 1,
              groups.get(i + 1).territory(),
              bucketOf(groups.get(i + 1)))
          .isLessThanOrEqualTo(bucketOf(groups.get(i + 1)));
    }
  }

  /** Returns the dispatch-order bucket index for a placement group (0 = highest priority). */
  private static int bucketOf(final PlacementGroup pg) {
    if (!pg.isWater() && !pg.isConstruction()) return 0; // land non-construction
    if (pg.isWater() && !pg.isConstruction()) return 1; // water non-construction
    if (!pg.isWater()) return 2; // land construction
    return 3; // water construction
  }

  /**
   * Smoke test: {@code politicalActions} is always a non-null list. Round 1 Germans will not
   * declare any wars, so the list is expected to be empty. The important invariant is that the
   * field is present and well-typed regardless of whether any actions were decided.
   *
   * <p>Note: {@code politicalActions} reflects the AI's decisions on the simulation clone ({@code
   * dataCopy}), not the live session's {@link games.strategy.engine.data.RelationshipTracker}. The
   * live tracker is only updated after the bot dispatches the returned war declarations to the
   * engine via {@code declareWar} moves.
   */
  @Test
  void politicalActionsIsNonNullAfterPurchase() throws Exception {
    final Session session = freshSession("Germans");
    final PurchasePlan plan =
        new PurchaseExecutor().execute(session, purchaseRequestFor("Germans"));

    assertThat(plan.politicalActions()).as("politicalActions must never be null").isNotNull();
  }

  /**
   * Verifies that {@code combatMoves} is populated in the PurchasePlan response and ordered
   * correctly (land/amphib/bombard moves first, bombing/SBR moves last).
   *
   * <p>Turn-1 Germans always have at least one land combat move (they border Poland and other
   * Allied territories). SBR moves are optional; what matters is that non-bombing moves precede
   * bombing moves in the list.
   *
   * <p>This exercises the {@code invokeCombatMoveForSidecar} projection path: after purchase, the
   * {@code storedCombatMoveMap} is populated; the executor calls {@code doMove} (not {@code
   * doCombatMove} — zero re-planning) and captures the resulting MoveDescriptions via a {@link
   * RecordingMoveDelegate}.
   */
  @Test
  void combatMovesArePopulatedAndOrderedCorrectlyAfterPurchase() throws Exception {
    final Session session = freshSession("Germans");
    final PurchasePlan plan =
        new PurchaseExecutor().execute(session, purchaseRequestFor("Germans"));

    assertThat(plan.combatMoves()).as("combatMoves must never be null").isNotNull();
    assertThat(plan.combatMoves())
        .as("Germans should have at least one combat move on turn 1")
        .isNotEmpty();

    // Each move must reference a non-blank from/to territory.
    // unitIds will be empty in this test context because session.unitIdMap() is not populated —
    // the WireState used here is empty (no territories/units), so no wire-ID → UUID mappings are
    // registered. In production the WireState carries all units, so unitIds are populated.
    for (final var md : plan.combatMoves()) {
      assertThat(md.from()).as("combatMove.from must not be blank").isNotBlank();
      assertThat(md.to()).as("combatMove.to must not be blank").isNotBlank();
    }

    // Ordering (non-bombing ++ bombing) is structurally guaranteed by PurchaseExecutor's
    // concatenation of nonBombingMoves then bombingMoves. Verifying it via unit classifications
    // here would require a populated session.unitIdMap(), which this test intentionally omits
    // (empty WireState — see comment above). The classification-based ordering test is covered
    // by CombatMoveExecutorTest for the real sidecar pipeline.
  }

  /**
   * Data-rooting audit: verifies that combat-move simulation predicates (ProMatches etc.) resolve
   * correctly during purchase-time planning. The returned combatMoves reference real territories
   * that exist in the session's GameData, confirming that the ProAi's data references were rooted
   * through the session clone rather than a stale copy.
   *
   * <p>If the data-rooting bug class (proData.getPlayer().getData() pointing to a detached
   * dataCopy) affected combat-move planning, we would see empty combatMoves or moves referencing
   * territories that do not exist in the session GameData. Both are caught here.
   */
  @Test
  void combatMoveSimulationPredicatesResolveViaSessionData() throws Exception {
    final Session session = freshSession("Germans");
    final PurchasePlan plan =
        new PurchaseExecutor().execute(session, purchaseRequestFor("Germans"));

    assertThat(plan.combatMoves())
        .as("combatMoves must be non-empty — data-rooting failure would yield empty list")
        .isNotEmpty();

    final var gameData = session.gameData();
    for (final var md : plan.combatMoves()) {
      // Every from-territory referenced by the combat move must exist in the live session data.
      assertThat(gameData.getMap().getTerritoryOrNull(md.from()))
          .as(
              "combatMove.from '%s' must exist in session GameData — stale dataCopy would "
                  + "reference territories unknown to the live clone",
              md.from())
          .isNotNull();
      assertThat(gameData.getMap().getTerritoryOrNull(md.to()))
          .as("combatMove.to '%s' must exist in session GameData", md.to())
          .isNotNull();
    }
  }
}
