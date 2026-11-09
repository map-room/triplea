package games.strategy.triplea.ai;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import lombok.experimental.UtilityClass;

/**
 * The random numbers AI decisions draw on.
 *
 * <p>A single-threaded replay tool replaces the process-wide source with {@link #setSource}. A
 * server that runs several matches at once must not: that source is one static for the process, so
 * a swap leaks between matches. It calls {@link #bindRequest} instead, which installs one {@link
 * Random} on the calling thread. {@link #get} prefers that binding.
 */
@UtilityClass
public final class AiRandom {
  private static volatile Supplier<Random> source = ThreadLocalRandom::current;

  /** Per-thread stream installed by {@link #bindRequest}. Not inherited by child threads. */
  private static final ThreadLocal<Random> requestRandom = new ThreadLocal<>();

  public static Random get() {
    final Random bound = requestRandom.get();
    return bound != null ? bound : source.get();
  }

  /** Draws AI random numbers from {@code newSource}; null restores the default. */
  public static void setSource(final @Nullable Supplier<Random> newSource) {
    source = newSource == null ? ThreadLocalRandom::current : newSource;
  }

  /**
   * This thread draws from a fresh {@link Random} seeded with {@code seed} until {@link
   * #clearRequest()}. Other threads are unchanged. A later {@link #get} on this thread advances
   * that one instance.
   */
  public static void bindRequest(final long seed) {
    requestRandom.set(new Random(seed));
  }

  /** Drops this thread's {@link #bindRequest} binding. Other threads are unchanged. */
  public static void clearRequest() {
    requestRandom.remove();
  }
}
