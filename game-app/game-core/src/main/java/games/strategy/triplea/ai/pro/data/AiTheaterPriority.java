package games.strategy.triplea.ai.pro.data;

import javax.annotation.Nullable;

/**
 * KGF / KJF flag for the Strategic Value Field (map-room#2755).
 *
 * <p>{@code KGF} (Kill Germany First) pulls US offensive mass toward Berlin / Europe; {@code KJF}
 * (Kill Japan First) pulls toward Tokyo / the Pacific. Per the §10 baseline-run finding, baseline
 * ProAi is naturally KJF-leaning, so {@code KGF} mode must fight an opposing gradient while {@code
 * KJF} mode reinforces an existing one — see plan §5 risk register.
 *
 * <p>Phase 1B/1C (PR-A) stores this enum on {@link
 * games.strategy.triplea.ai.pro.ProData#getAiTheaterPriority()} and reads it from env var {@code
 * AI_THEATER_PRIORITY} (sysprop {@code ai.theater.priority}) at executor boundary. Lobby UI +
 * {@code WireState} plumbing is PR-B.
 */
public enum AiTheaterPriority {
  KGF,
  KJF;

  /**
   * Parses a wire string into a priority. {@code null}, empty, and unparseable values fall back to
   * {@link #KGF} (the documented default — see plan §3 question 3).
   */
  public static AiTheaterPriority fromWire(@Nullable final String s) {
    if (s == null || s.isEmpty()) {
      return KGF;
    }
    try {
      return AiTheaterPriority.valueOf(s.trim().toUpperCase());
    } catch (final IllegalArgumentException e) {
      return KGF;
    }
  }
}
