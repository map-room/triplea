package org.triplea.ai.sidecar.wire;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Resolves Map Room phase names (as carried in {@link WireState#phase()}) to the corresponding
 * TripleA {@link GameStep} on the loaded map XML.
 *
 * <p><b>Why resolve against {@link GameData}, not construct names:</b> step-name conventions are
 * per-XML. Global 1940 uses the plural player name ({@code germansPurchase}, {@code
 * americansCombatMove}); WW2v3-1941 uses the singular adjective ({@code germanPurchase}, {@code
 * americanCombatMove}). British placement is {@code britishNoAirCheckPlace} on G40 and {@code
 * britishPlace} on WW2v3. Hardcoding {@code playerName + suffix} only works for one edition —
 * matching against the sequence of the already-selected {@link GameData} (chosen via {@code
 * gameDataKey}) is the per-edition pattern, same seam as {@link
 * org.triplea.ai.sidecar.CanonicalGameDataRegistry}.
 *
 * <p><b>Fail closed:</b> zero matches or more than one match after filtering throws. A silent miss
 * leaves the sequence parked on {@code gameInitDelegate} and surfaces three layers later as {@code
 * Cannot determine combat or not: gameInitDelegate} (map-room#3535).
 *
 * <p>This mapping is a contract between Map Room and the sidecar. Keep phase names in sync with
 * {@code packages/shared/src/engine/game-def.ts}.
 */
public final class StepNameMapper {

  /**
   * Known Map Room phases the sidecar wires. Values are the lowercase name-suffix used for matching
   * against XML step names (e.g. {@code purchase} matches {@code germansPurchase} and {@code
   * germanPurchase}).
   */
  private static final Map<String, String> PHASE_SUFFIX =
      Map.of(
          "purchase", "purchase",
          "combatMove", "combatmove",
          "battle", "battle",
          "nonCombatMove", "noncombatmove",
          "place", "place");

  private StepNameMapper() {}

  /**
   * Resolve the unique {@link GameStep} for {@code (mapRoomPhase, playerName)} against {@code
   * gameData}'s sequence.
   *
   * @throws IllegalArgumentException if the phase is unmapped or the player is unknown
   * @throws IllegalStateException if zero or multiple steps match (fail closed)
   */
  public static GameStep resolve(
      final GameData gameData, final String mapRoomPhase, final String playerName) {
    final String suffix = PHASE_SUFFIX.get(mapRoomPhase);
    if (suffix == null) {
      throw new IllegalArgumentException("Unmapped Map Room phase: " + mapRoomPhase);
    }
    final GamePlayer player = gameData.getPlayerList().getPlayerId(playerName);
    if (player == null) {
      throw new IllegalArgumentException("Unknown player: " + playerName);
    }

    final List<GameStep> matches = new ArrayList<>();
    for (final GameStep step : gameData.getSequence().getSteps()) {
      if (player.equals(step.getPlayerId()) && matchesPhase(step.getName(), mapRoomPhase, suffix)) {
        matches.add(step);
      }
    }

    if (matches.isEmpty()) {
      throw new IllegalStateException(
          "No GameStep matched phase '"
              + mapRoomPhase
              + "' (suffix '"
              + suffix
              + "') for player "
              + playerName
              + " on map "
              + describeMap(gameData)
              + "; available steps for player: "
              + stepsForPlayer(gameData, player));
    }
    if (matches.size() > 1) {
      throw new IllegalStateException(
          "Ambiguous GameStep match for phase '"
              + mapRoomPhase
              + "' player "
              + playerName
              + " on map "
              + describeMap(gameData)
              + ": "
              + matches.stream().map(GameStep::getName).collect(Collectors.joining(", ")));
    }
    return matches.get(0);
  }

  /**
   * Construct the historical G40-style step name ({@code GermansPurchase}). Retained only for unit
   * tests that assert the legacy construction form; production code must use {@link #resolve}.
   *
   * @deprecated Prefer {@link #resolve(GameData, String, String)} which is per-edition.
   */
  @Deprecated
  public static String toJavaStepName(final String mapRoomPhase, final String playerName) {
    return playerName + toLegacyCamelSuffix(mapRoomPhase);
  }

  private static String toLegacyCamelSuffix(final String mapRoomPhase) {
    return switch (mapRoomPhase) {
      case "purchase" -> "Purchase";
      case "combatMove" -> "CombatMove";
      case "battle" -> "Battle";
      case "nonCombatMove" -> "NonCombatMove";
      case "place" -> "Place";
      default -> throw new IllegalArgumentException("Unmapped Map Room phase: " + mapRoomPhase);
    };
  }

  /**
   * Phase match against an XML step name. Case-insensitive. Bid steps and airborne-special steps
   * are excluded so the regular combat/place step is unique.
   */
  static boolean matchesPhase(
      final String stepName, final String mapRoomPhase, final String suffix) {
    if (stepName == null) {
      return false;
    }
    final String n = stepName.toLowerCase(Locale.ROOT);
    // Bid steps (germanBidPlace, britishBid, …) and airborne specials are not the wired phases.
    if (n.contains("bid") || n.contains("airborne")) {
      return false;
    }
    return switch (mapRoomPhase) {
      case "nonCombatMove" -> n.endsWith("noncombatmove");
      // combatMove must not also match NonCombatMove (which ends with "combatmove").
      case "combatMove" -> n.endsWith("combatmove") && !n.endsWith("noncombatmove");
      case "purchase", "battle", "place" -> n.endsWith(suffix);
      default -> false;
    };
  }

  private static String describeMap(final GameData gameData) {
    // GameData has no stable edition id; the sequence's first named player-step is enough context.
    return gameData.getGameName() != null ? gameData.getGameName() : "(unnamed)";
  }

  private static String stepsForPlayer(final GameData gameData, final GamePlayer player) {
    final List<String> names = new ArrayList<>();
    for (final GameStep step : gameData.getSequence().getSteps()) {
      if (player.equals(step.getPlayerId()) && step.getName() != null) {
        names.add(step.getName());
      }
    }
    return names.toString();
  }
}
