package org.triplea.ai.sidecar.dto;

/**
 * The ProAi variant a decision request asks for. Wire values: {@code "hard"} (stock Hard, {@code
 * beta=false}) and {@code "hard-beta"}. Omitted or {@code null} means {@code "hard"}.
 */
public final class AiProfile {
  public static final String HARD = "hard";
  public static final String HARD_BETA = "hard-beta";

  private AiProfile() {}

  /**
   * Resolves a wire {@code profile} to ProAi's {@code beta} flag.
   *
   * @throws IllegalArgumentException for any value other than null, {@code "hard"} or {@code
   *     "hard-beta"} (including the empty string). The handler maps this to a 400; it must not fall
   *     back to stock Hard, because that would silently run the wrong arm.
   */
  public static boolean isBeta(final String profile) {
    if (profile == null || HARD.equals(profile)) {
      return false;
    }
    if (HARD_BETA.equals(profile)) {
      return true;
    }
    throw new IllegalArgumentException(
        "Unknown profile: \"" + profile + "\" (expected \"hard\" or \"hard-beta\")");
  }
}
