package games.strategy.triplea.ai.pro;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.RelationshipTracker;
import games.strategy.engine.data.RelationshipType;
import games.strategy.triplea.ai.AiPoliticalUtils;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.data.ProTerritoryManager;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.ai.pro.util.ProUtils;
import games.strategy.triplea.attachments.PoliticalActionAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.PoliticsDelegate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.triplea.java.collections.CollectionUtils;

/** Pro politics AI. */
class ProPoliticsAi {

  private final ProOddsCalculator calc;
  private final ProData proData;
  private final Random rng;

  ProPoliticsAi(final AbstractProAi ai) {
    this(ai, ai.getProData().getRng());
  }

  ProPoliticsAi(final AbstractProAi ai, final Random rng) {
    calc = ai.getCalc();
    proData = ai.getProData();
    this.rng = rng;
  }

  List<PoliticalActionAttachment> politicalActions() {
    final GameData data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    final float numPlayers = data.getPlayerList().getPlayers().size();
    final double round = data.getSequence().getRound();
    final ProTerritoryManager territoryManager = new ProTerritoryManager(calc, proData);
    final PoliticsDelegate politicsDelegate = data.getPoliticsDelegate();
    ProLogger.info("Politics for " + player.getName());

    // Find valid war actions
    final List<PoliticalActionAttachment> actionChoicesTowardsWar =
        AiPoliticalUtils.getPoliticalActionsTowardsWar(
            player, politicsDelegate.getTestedConditions(), data);
    ProLogger.trace("War options: " + actionChoicesTowardsWar);
    final List<PoliticalActionAttachment> validWarActions =
        CollectionUtils.getMatches(
            actionChoicesTowardsWar,
            Matches.abstractUserActionAttachmentCanBeAttempted(
                politicsDelegate.getTestedConditions()));
    ProLogger.trace("Valid War options: " + validWarActions);

    // Divide war actions into enemy and neutral
    final Map<PoliticalActionAttachment, List<GamePlayer>> enemyMap = new LinkedHashMap<>();
    final Map<PoliticalActionAttachment, List<GamePlayer>> neutralMap = new LinkedHashMap<>();
    for (final PoliticalActionAttachment action : validWarActions) {
      final List<GamePlayer> warPlayers = new ArrayList<>();
      for (final PoliticalActionAttachment.RelationshipChange relationshipChange :
          action.getRelationshipChanges()) {
        final GamePlayer player1 = relationshipChange.player1;
        final GamePlayer player2 = relationshipChange.player2;
        final RelationshipType oldRelation =
            data.getRelationshipTracker().getRelationshipType(player1, player2);
        final RelationshipType newRelation = relationshipChange.relationshipType;
        if (!oldRelation.equals(newRelation)
            && Matches.relationshipTypeIsAtWar().test(newRelation)
            && (player1.equals(player) || player2.equals(player))) {
          GamePlayer warPlayer = player2;
          if (warPlayer.equals(player)) {
            warPlayer = player1;
          }
          warPlayers.add(warPlayer);
        }
      }
      if (!warPlayers.isEmpty()) {
        if (ProUtils.isNeutralPlayer(warPlayers.get(0))) {
          neutralMap.put(action, warPlayers);
        } else {
          enemyMap.put(action, warPlayers);
        }
      }
    }
    ProLogger.debug("Neutral options: " + neutralMap);
    ProLogger.debug("Enemy options: " + enemyMap);
    final List<PoliticalActionAttachment> results = new ArrayList<>();
    if (!enemyMap.isEmpty()) {

      // Pre-roll: auto-declare on coalition mates of players we are already at war with.
      // When a war globalizes on another player's turn (e.g. Japan attacks the US), the
      // "declare war on Japan" action is no longer in enemyMap and the bystander's
      // attackPercentage for distant axis coalition members hovers near zero, so without
      // this pass the coalition would stay at peace indefinitely (map-room/map-room#2761).
      final List<GamePlayer> existingEnemies = new ArrayList<>();
      for (final GamePlayer other : data.getPlayerList().getPlayers()) {
        if (!other.equals(player) && data.getRelationshipTracker().isAtWar(player, other)) {
          existingEnemies.add(other);
        }
      }
      if (!existingEnemies.isEmpty()) {
        final List<PoliticalActionAttachment> preRollFollowUps =
            findCoalitionFollowUps(existingEnemies, enemyMap, data.getRelationshipTracker());
        for (final PoliticalActionAttachment action : preRollFollowUps) {
          results.add(action);
          ProLogger.debug(
              "---Pre-roll coalition follow-up: declared war on " + enemyMap.get(action));
        }
      }

      // Find all attack options
      territoryManager.populatePotentialAttackOptions();
      final List<ProTerritory> attackOptions =
          territoryManager.removePotentialTerritoriesThatCantBeConquered();
      ProLogger.trace(
          player.getName()
              + ", numAttackOptions="
              + attackOptions.size()
              + ", options="
              + attackOptions);

      // Find attack options per war action — skip actions already claimed by pre-roll.
      final Map<PoliticalActionAttachment, Double> attackPercentageMap = new LinkedHashMap<>();
      for (final PoliticalActionAttachment action : enemyMap.keySet()) {
        if (results.contains(action)) {
          continue;
        }
        int count = 0;
        final List<GamePlayer> enemyPlayers = enemyMap.get(action);
        for (final ProTerritory patd : attackOptions) {
          if (Matches.isTerritoryOwnedByAnyOf(enemyPlayers).test(patd.getTerritory())
              || Matches.territoryHasUnitsThatMatch(Matches.unitIsOwnedByAnyOf(enemyPlayers))
                  .test(patd.getTerritory())) {
            count++;
          }
        }
        final double attackPercentage = count / (attackOptions.size() + 1.0);
        attackPercentageMap.put(action, attackPercentage);
        ProLogger.trace(
            enemyPlayers + ", count=" + count + ", attackPercentage=" + attackPercentage);
      }

      // Decide whether to declare war on an enemy
      final List<PoliticalActionAttachment> options = new ArrayList<>(attackPercentageMap.keySet());
      // Shuffle with the seeded rng so the war-target selection is deterministic given
      // (gamestate, seed). The default Collections.shuffle(list) overload uses an unseeded
      // ThreadLocalRandom and would leak process entropy here (map-room/map-room#2376 / #2377).
      Collections.shuffle(options, rng);
      for (final PoliticalActionAttachment action : options) {
        final double roundFactor = (round - 1) * .05; // 0, .05, .1, .15, etc
        final double warChance =
            roundFactor + attackPercentageMap.get(action) * (1 + 10 * roundFactor);
        final double random = rng.nextDouble();
        ProLogger.trace(enemyMap.get(action) + ", warChance=" + warChance + ", random=" + random);
        if (random <= warChance) {
          results.add(action);
          ProLogger.debug("---Declared war on " + enemyMap.get(action));
          // Post-roll coalition follow-up: also declare on the primary target's coalition
          // (in case any aren't already in results from the pre-roll pass).
          final List<PoliticalActionAttachment> followUps =
              findCoalitionFollowUps(enemyMap.get(action), enemyMap, data.getRelationshipTracker());
          for (final PoliticalActionAttachment followUp : followUps) {
            if (!results.contains(followUp)) {
              results.add(followUp);
              ProLogger.debug(
                  "---Coalition follow-up: also declared war on " + enemyMap.get(followUp));
            }
          }
          break;
        }
      }
    } else if (!neutralMap.isEmpty()) {

      // Decide whether to declare war on a neutral
      final List<PoliticalActionAttachment> options = new ArrayList<>(neutralMap.keySet());
      Collections.shuffle(options, rng);
      final double random = rng.nextDouble();
      final double warChance = .01;
      ProLogger.debug("warChance=" + warChance + ", random=" + random);
      if (random <= warChance) {
        results.add(options.get(0));
        ProLogger.debug("Declared war on " + enemyMap.get(options.get(0)));
      }
    }

    // Old code used for non-war actions
    if (rng.nextDouble() < .5) {
      final List<PoliticalActionAttachment> actionChoicesOther =
          AiPoliticalUtils.getPoliticalActionsOther(
              player, politicsDelegate.getTestedConditions(), data);
      if (!actionChoicesOther.isEmpty()) {
        Collections.shuffle(actionChoicesOther, rng);
        int i = 0;
        final double random = rng.nextDouble();
        final int maxOtherActionsPerTurn =
            (random < .3
                ? 0
                : (random < .6 ? 1 : (random < .9 ? 2 : (random < .99 ? 3 : (int) numPlayers))));
        final Iterator<PoliticalActionAttachment> actionOtherIter = actionChoicesOther.iterator();
        while (actionOtherIter.hasNext() && maxOtherActionsPerTurn > 0) {
          final PoliticalActionAttachment action = actionOtherIter.next();
          if (!Matches.abstractUserActionAttachmentCanBeAttempted(
                  politicsDelegate.getTestedConditions())
              .test(action)) {
            continue;
          }
          if (!player.getResources().has(action.getCostResources())) {
            continue;
          }
          i++;
          if (i > maxOtherActionsPerTurn) {
            break;
          }
          results.add(action);
        }
      }
    }
    doActions(results);
    return results;
  }

  void doActions(final List<PoliticalActionAttachment> actions) {
    final GameData data = proData.getData();
    final PoliticsDelegate politicsDelegate = data.getPoliticsDelegate();
    for (final PoliticalActionAttachment action : actions) {
      ProLogger.debug("Performing action: " + action);
      politicsDelegate.attemptAction(action);
    }
  }

  /**
   * Returns enemy war actions (from {@code enemyMap}) whose targets are allied per the in-game
   * {@link RelationshipTracker} with at least one player in {@code anchorPlayers}. Pure lookup —
   * the caller is responsible for skipping the anchor's own actions and deduplicating against any
   * already-claimed results. Used both pre-roll (anchor = current enemies) and post-roll (anchor =
   * primary target's players) to propagate war declarations across coalitions.
   */
  static List<PoliticalActionAttachment> findCoalitionFollowUps(
      final List<GamePlayer> anchorPlayers,
      final Map<PoliticalActionAttachment, List<GamePlayer>> enemyMap,
      final RelationshipTracker relationshipTracker) {
    final List<PoliticalActionAttachment> followUps = new ArrayList<>();
    for (final Map.Entry<PoliticalActionAttachment, List<GamePlayer>> entry : enemyMap.entrySet()) {
      if (sharesAlliance(anchorPlayers, entry.getValue(), relationshipTracker)) {
        followUps.add(entry.getKey());
      }
    }
    return followUps;
  }

  private static boolean sharesAlliance(
      final List<GamePlayer> a,
      final List<GamePlayer> b,
      final RelationshipTracker relationshipTracker) {
    for (final GamePlayer p1 : a) {
      for (final GamePlayer p2 : b) {
        if (relationshipTracker.isAllied(p1, p2)) {
          return true;
        }
      }
    }
    return false;
  }
}
