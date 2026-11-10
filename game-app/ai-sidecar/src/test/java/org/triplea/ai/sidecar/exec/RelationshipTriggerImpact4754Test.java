package org.triplea.ai.sidecar.exec;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.Change;
import games.strategy.engine.data.CompositeChange;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MutableProperty;
import games.strategy.engine.data.RelationshipType;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.UnitUtils;
import games.strategy.triplea.ai.pro.util.ProUtils;
import games.strategy.triplea.attachments.FireTriggerParams;
import games.strategy.triplea.attachments.ICondition;
import games.strategy.triplea.attachments.RulesAttachment;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.attachments.TriggerAttachment;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.ui.ObjectiveDummyDelegateBridge;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.dto.PurchaseOrder;
import org.triplea.ai.sidecar.dto.PurchasePlan;
import org.triplea.ai.sidecar.dto.PurchaseRequest;
import org.triplea.ai.sidecar.wire.WirePlayer;
import org.triplea.ai.sidecar.wire.WireRelationship;
import org.triplea.ai.sidecar.wire.WireState;
import org.triplea.ai.sidecar.wire.WireStateApplier;

/**
 * map-room#4754 measurement: the sidecar stands in for 2 of 29 relationship-dependent Global 1940
 * triggers (movement restriction via {@code MovementRestrictionLifter}, tech via {@code
 * WireStateApplier}). This test fires the other families against a cloned G40 board and records
 * whether ProAI actually reads the attachment or relationship they would change.
 *
 * <p>Not a stand-in. Do not promote these helpers into production without an impact decision.
 */
class RelationshipTriggerImpact4754Test {

  private static final String G40 = "ww2global40_2nd_edition";
  private static final FireTriggerParams FIRE_PARAMS =
      new FireTriggerParams(null, null, false, false, false, false);

  private static final List<String> MOVEMENT_RESTRICTION =
      List.of(
          "triggerAttachment_Japanese_Unrestricted_Movement",
          "triggerAttachment_Americans_Unrestricted_Movement");

  private static final List<String> FRIENDLY_WITH_MONGOLIANS =
      List.of(
          "triggerAttachment_Italians_Friendly_With_Mongolians",
          "triggerAttachment_Germans_Friendly_With_Mongolians",
          "triggerAttachment_Japanese_Friendly_With_Mongolians",
          "triggerAttachment_British_Friendly_With_Mongolians",
          "triggerAttachment_French_Friendly_With_Mongolians",
          "triggerAttachment_Russians_Friendly_With_Mongolians",
          "triggerAttachment_Americans_Friendly_With_Mongolians");

  private static final List<String> FRIENDLY_WITH_TRUE_NEUTRALS =
      List.of(
          "triggerAttachment_Germans_Friendly_With_TrueNeutrals",
          "triggerAttachment_Italians_Friendly_With_TrueNeutrals",
          "triggerAttachment_Japanese_Friendly_With_TrueNeutrals",
          "triggerAttachment_British_Friendly_With_TrueNeutrals",
          "triggerAttachment_French_Friendly_With_TrueNeutrals",
          "triggerAttachment_Russians_Friendly_With_TrueNeutrals",
          "triggerAttachment_Americans_Friendly_With_TrueNeutrals");

  private static final List<String> RUSSIANS_ALLIED_AMERICANS =
      List.of(
          "triggerAttachment_Russians_Allied_Americans_1",
          "triggerAttachment_Russians_Allied_Americans_2",
          "triggerAttachment_Russians_Allied_Americans_3",
          "triggerAttachment_Russians_Allied_Americans_4",
          "triggerAttachment_Russians_Allied_Americans_5",
          "triggerAttachment_Russians_Allied_Americans_6");

  private static final List<String> FOLLOW_ON_RELATIONSHIPS =
      List.of(
          "triggerAttachment_Russians_War_TrueNeutral",
          "triggerAttachment_Russians_Friendly_TrueNeutral",
          "triggerAttachment_Russians_Friendly_Mongolians1",
          "triggerAttachment_Russians_Friendly_Mongolians2",
          "triggerAttachment_Americans_War_TrueNeutral",
          "triggerAttachment_Americans_Friendly_TrueNeutral",
          "triggerAttachment_Americans_War_Mongolians");

  private static final List<String> WAR_PRODUCTION =
      List.of(
          "triggerAttachment_Americans_War_Production_Eastern",
          "triggerAttachment_Americans_War_Production_Central",
          "triggerAttachment_Americans_War_Production_Western");

  private static final List<String> US_ICS =
      List.of("Eastern United States", "Central United States", "Western United States");

  private static CanonicalGameData canonical;

  @BeforeAll
  static void init() {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load(G40 + ".xml");
  }

  @Test
  void global1940HasTheTwentySevenRelationshipTriggersPlusTwoMovementLifts() {
    final GameData data = canonical.cloneForSession();
    final Set<String> names = allTriggerNames(data);

    assertThat(names).containsAll(MOVEMENT_RESTRICTION);
    assertThat(names).containsAll(FRIENDLY_WITH_MONGOLIANS);
    assertThat(names).containsAll(FRIENDLY_WITH_TRUE_NEUTRALS);
    assertThat(names).containsAll(RUSSIANS_ALLIED_AMERICANS);
    assertThat(names).containsAll(FOLLOW_ON_RELATIONSHIPS);
    assertThat(names).containsAll(WAR_PRODUCTION);

    final Set<String> relationshipDependent = relationshipDependentTriggerNames(data);
    assertThat(relationshipDependent).containsAll(MOVEMENT_RESTRICTION);
    assertThat(relationshipDependent).containsAll(FRIENDLY_WITH_MONGOLIANS);
    assertThat(relationshipDependent).containsAll(FRIENDLY_WITH_TRUE_NEUTRALS);
    assertThat(relationshipDependent).containsAll(RUSSIANS_ALLIED_AMERICANS);
    assertThat(relationshipDependent).containsAll(FOLLOW_ON_RELATIONSHIPS);
    assertThat(relationshipDependent).containsAll(WAR_PRODUCTION);

    final Set<String> named = new HashSet<>();
    named.addAll(MOVEMENT_RESTRICTION);
    named.addAll(FRIENDLY_WITH_MONGOLIANS);
    named.addAll(FRIENDLY_WITH_TRUE_NEUTRALS);
    named.addAll(RUSSIANS_ALLIED_AMERICANS);
    named.addAll(FOLLOW_ON_RELATIONSHIPS);
    named.addAll(WAR_PRODUCTION);
    final Set<String> extras = new HashSet<>(relationshipDependent);
    extras.removeAll(named);
    // Income / post-battle extras that also carry a relationship condition. Not in the original
    // 27+2, but the scan should keep seeing them so a later stand-in inventory cannot miss them.
    assertThat(extras)
        .contains(
            "triggerAttachment_Germans_5_Swedish_Iron_Ore",
            "triggerAttachment_ANZAC_1_Control_Original_And_Malaya",
            "triggerAttachment_Japanese_Conquer_Russians_Territory_By_Mongolia_1");
  }

  @Test
  void warProductionChangesTheCapacityProPurchaseReads() {
    final GameData data = applied(usAtWarPurchase(52));
    final GamePlayer americans = player(data, "Americans");

    assertThat(usFactoryTypes(data)).containsOnly("factory_minor");
    assertThat(usProduction(data, americans)).isEqualTo(9);

    final Set<String> fired = fireNamed(data, WAR_PRODUCTION);
    assertThat(fired).containsExactlyInAnyOrderElementsOf(WAR_PRODUCTION);

    assertThat(usFactoryTypes(data)).containsOnly("factory_major");
    assertThat(usProduction(data, americans)).isEqualTo(30);
  }

  @Test
  void warProductionChangesTheUsPurchasePlanOnSeed1() {
    final WireState wire = usAtWarPurchase(52);
    final PurchaseRequest request = new PurchaseRequest(wire, 1L, "measure-4754", "hard");

    final GameData without = applied(wire);
    final PurchasePlan minorPlan = new PurchaseExecutor().executeOn(without, request);

    final GameData with = applied(wire);
    fireNamed(with, WAR_PRODUCTION);
    final PurchasePlan majorPlan = new PurchaseExecutor().executeOn(with, request);

    assertThat(summarize(majorPlan))
        .as(
            "seed-1 US purchase with majors vs minors; minor=%s major=%s",
            summarize(minorPlan), summarize(majorPlan))
        .isNotEqualTo(summarize(minorPlan));
    assertThat(countUnits(majorPlan)).isGreaterThan(countUnits(minorPlan));
  }

  @Test
  void trueNeutralCaptureListIsWhatCascadeCostAndCombatScoringRead() {
    final GameData data = canonical.cloneForSession();
    final GamePlayer germans = player(data, "Germans");
    final Territory turkey = data.getMap().getTerritoryOrThrow("Turkey");
    final Territory spain = data.getMap().getTerritoryOrThrow("Spain");

    assertThat(captureOnEnter(spain)).isEmpty();
    final double before = ProUtils.computeTrueNeutralCascadeCost(data, germans, turkey);
    assertThat(before).isPositive();

    setRelationship(data, "British", "Neutral_True", "Friendly_Neutral");
    final Set<String> fired = fireNamed(data, FRIENDLY_WITH_TRUE_NEUTRALS);
    assertThat(fired).contains("triggerAttachment_British_Friendly_With_TrueNeutrals");

    assertThat(captureOnEnter(spain).stream().map(GamePlayer::getName).toList())
        .contains("British", "Americans", "Russians");
    // The XML stamps only 12 True Neutral territories. Remaining cascade cost is Mongolia and
    // other neutrals outside that list (isNeutralLand, not Neutral_True-only).
    final double after = ProUtils.computeTrueNeutralCascadeCost(data, germans, turkey);
    assertThat(after).isLessThan(before).isPositive();
  }

  @Test
  void mongolianCaptureListIsSetByTheActivateTriggerFamily() {
    final GameData data = canonical.cloneForSession();
    final Territory ulaanbaatar = data.getMap().getTerritoryOrThrow("Ulaanbaatar");
    assertThat(captureOnEnter(ulaanbaatar)).isEmpty();

    setRelationship(data, "Germans", "Mongolians", "Friendly_Neutral");
    final Set<String> fired = fireNamed(data, FRIENDLY_WITH_MONGOLIANS);
    assertThat(fired).contains("triggerAttachment_Germans_Friendly_With_Mongolians");

    assertThat(captureOnEnter(ulaanbaatar).stream().map(GamePlayer::getName).toList())
        .contains("Germans", "Italians", "Japanese");
  }

  @Test
  void russiansAlliedAmericansIsWhatIsAlliedReadsAndSharedEnemyAlreadyCoversCombatGrouping() {
    final GameData data =
        applied(
            new WireState(
                List.of(),
                List.of(),
                2,
                "purchase",
                "Japanese",
                List.of(
                    new WireRelationship("Americans", "Germans", "war"),
                    new WireRelationship("Germans", "Russians", "war")),
                G40));
    final GamePlayer russians = player(data, "Russians");
    final GamePlayer americans = player(data, "Americans");
    final GamePlayer germans = player(data, "Germans");

    assertThat(data.getRelationshipTracker().isAllied(russians, americans)).isFalse();
    assertThat(ProUtils.getOpposingTeamPlayersInTurnOrder(germans)).contains(russians, americans);

    final Set<String> fired = fireNamed(data, RUSSIANS_ALLIED_AMERICANS);
    assertThat(fired).isNotEmpty();
    assertThat(data.getRelationshipTracker().isAllied(russians, americans)).isTrue();
    assertThat(ProUtils.getOpposingTeamPlayersInTurnOrder(germans)).contains(russians, americans);
  }

  @Test
  void japanDeclaringWarOnTheUsDoesNotSatisfyRussiansAlliedAmericans() {
    final GameData data =
        applied(
            new WireState(
                List.of(),
                List.of(),
                1,
                "purchase",
                "Japanese",
                List.of(new WireRelationship("Americans", "Japanese", "war")),
                G40));

    assertThat(fireNamed(data, RUSSIANS_ALLIED_AMERICANS)).isEmpty();
    assertThat(
            data.getRelationshipTracker()
                .isAllied(player(data, "Russians"), player(data, "Americans")))
        .isFalse();
  }

  @Test
  void followOnWarWithTrueNeutralsIsWhatRelationshipTrackerReads() {
    final GameData data =
        applied(
            new WireState(
                List.of(),
                List.of(),
                2,
                "purchase",
                "Russians",
                List.of(
                    new WireRelationship("British", "Neutral_True", "war"),
                    new WireRelationship("Germans", "Russians", "war")),
                G40));
    final GamePlayer russians = player(data, "Russians");
    final GamePlayer trueNeutrals = player(data, "Neutral_True");

    assertThat(data.getRelationshipTracker().isAtWar(russians, trueNeutrals)).isFalse();

    final Set<String> fired =
        fireNamed(data, List.of("triggerAttachment_Russians_War_TrueNeutral"));
    assertThat(fired).containsExactly("triggerAttachment_Russians_War_TrueNeutral");
    assertThat(data.getRelationshipTracker().isAtWar(russians, trueNeutrals)).isTrue();
  }

  // --- helpers -------------------------------------------------------------------------------

  private static GameData applied(final WireState wire) {
    final GameData data = canonical.cloneForSession();
    WireStateApplier.apply(data, wire, new ConcurrentHashMap<String, UUID>());
    return data;
  }

  private static WireState usAtWarPurchase(final int pus) {
    return new WireState(
        List.of(),
        List.of(new WirePlayer("Americans", pus, List.of(), false)),
        3,
        "purchase",
        "Americans",
        List.of(new WireRelationship("Americans", "Germans", "war")),
        G40);
  }

  private static Set<String> fireNamed(final GameData data, final List<String> names) {
    final Set<String> nameSet = new HashSet<>(names);
    return fireMatching(data, t -> nameSet.contains(t.getName()));
  }

  private static Set<String> fireMatching(
      final GameData data, final Predicate<TriggerAttachment> match) {
    // cloneForSession strips the battle delegate; triggerChangeOwnership always looks it up.
    ExecutorSupport.ensureBattleDelegate(data);
    final Set<TriggerAttachment> candidates =
        TriggerAttachment.collectForAllTriggersMatching(
            new HashSet<>(data.getPlayerList().getPlayers()), match);
    if (candidates.isEmpty()) {
      return Set.of();
    }
    final CollectingBridge bridge = new CollectingBridge(data);
    final Map<ICondition, Boolean> tested =
        TriggerAttachment.collectTestsForAllTriggers(candidates, bridge);
    final Set<TriggerAttachment> satisfied =
        candidates.stream().filter(t -> t.isSatisfied(tested)).collect(Collectors.toSet());
    if (satisfied.isEmpty()) {
      return Set.of();
    }
    TriggerAttachment.fireTriggers(satisfied, tested, bridge, FIRE_PARAMS);
    if (!bridge.changes.isEmpty()) {
      data.performChange(bridge.changes);
    }
    return satisfied.stream().map(TriggerAttachment::getName).collect(Collectors.toSet());
  }

  private static void setRelationship(
      final GameData data, final String a, final String b, final String typeName) {
    final RelationshipType type = data.getRelationshipTypeList().getRelationshipType(typeName);
    data.getRelationshipTracker().setRelationship(player(data, a), player(data, b), type);
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

  private static List<GamePlayer> captureOnEnter(final Territory t) {
    return TerritoryAttachment.get(t)
        .map(TerritoryAttachment::getCaptureUnitOnEnteringBy)
        .orElse(List.of());
  }

  private static int countUnits(final PurchasePlan plan) {
    return plan.buys().stream().mapToInt(PurchaseOrder::count).sum();
  }

  private static String summarize(final PurchasePlan plan) {
    return plan.buys().stream()
        .map(order -> order.unitType() + "×" + order.count())
        .collect(Collectors.joining(" + "));
  }

  private static Set<String> allTriggerNames(final GameData data) {
    return TriggerAttachment.collectForAllTriggersMatching(
            new HashSet<>(data.getPlayerList().getPlayers()), t -> true)
        .stream()
        .map(TriggerAttachment::getName)
        .collect(Collectors.toSet());
  }

  private static Set<String> relationshipDependentTriggerNames(final GameData data) {
    return TriggerAttachment.collectForAllTriggersMatching(
            new HashSet<>(data.getPlayerList().getPlayers()),
            RelationshipTriggerImpact4754Test::dependsOnRelationship)
        .stream()
        .map(TriggerAttachment::getName)
        .collect(Collectors.toSet());
  }

  private static boolean dependsOnRelationship(final TriggerAttachment trigger) {
    return dependsOnRelationship(trigger.getConditions());
  }

  private static boolean dependsOnRelationship(final List<RulesAttachment> conditions) {
    for (final RulesAttachment condition : conditions) {
      if (hasRelationshipField(condition) || dependsOnRelationship(condition.getConditions())) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasRelationshipField(final RulesAttachment condition) {
    return nonempty(condition, "relationship") || nonempty(condition, "atWarPlayers");
  }

  private static boolean nonempty(final RulesAttachment condition, final String property) {
    return condition
        .getPropertyOrEmpty(property)
        .map(MutableProperty::getValue)
        .filter(Collection.class::isInstance)
        .map(Collection.class::cast)
        .filter(collection -> !collection.isEmpty())
        .isPresent();
  }

  private static final class CollectingBridge extends ObjectiveDummyDelegateBridge {
    private final CompositeChange changes = new CompositeChange();

    CollectingBridge(final GameData data) {
      super(data);
    }

    @Override
    public void addChange(final Change change) {
      changes.add(change);
    }
  }
}
