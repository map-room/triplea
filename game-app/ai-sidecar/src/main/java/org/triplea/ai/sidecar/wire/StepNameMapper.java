package org.triplea.ai.sidecar.wire;

import java.util.Map;

/**
 * Maps Map Room phase names (as carried in {@link WireState#phase()}) to the corresponding
 * TripleA XML step display name for {@code GameSequence.setRoundAndStep}. The convention in
 * TripleA's Global 1940 XML is {@code <PlayerName><CamelPhase>}, e.g. {@code GermansPurchase}
 * or {@code GermansCombatMove}. Phases that the sidecar does not receive ({@code tech},
 * {@code intelligence}) are not mapped and will throw.
 *
 * <p>This mapping is a contract between Map Room and the sidecar. Keep it in sync with the
 * phase names in {@code packages/shared/src/engine/game-def.ts}.
 */
public final class StepNameMapper {
  private static final Map<String, String> PHASE_SUFFIX =
      Map.of(
          "purchase", "Purchase",
          "combatMove", "CombatMove",
          "battle", "Battle",
          "nonCombatMove", "NonCombatMove",
          "place", "Place");

  private StepNameMapper() {}

  public static String toJavaStepName(String mapRoomPhase, String playerName) {
    String suffix = PHASE_SUFFIX.get(mapRoomPhase);
    if (suffix == null) {
      throw new IllegalArgumentException("Unmapped Map Room phase: " + mapRoomPhase);
    }
    return playerName + suffix;
  }
}
