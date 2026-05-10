package games.strategy.engine.random;

import static com.google.common.base.Preconditions.checkArgument;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import org.apache.commons.math3.random.MersenneTwister;
import org.apache.commons.math3.random.RandomGenerator;

/** A source of random numbers that uses a pseudorandom number generator. */
@ThreadSafe
public final class PlainRandomSource implements IRandomSource {
  private final Object lock = new Object();

  @GuardedBy("lock")
  private final RandomGenerator random;

  /** Default constructor: nondeterministic seeding (current behaviour). */
  public PlainRandomSource() {
    this.random = new MersenneTwister();
  }

  /**
   * Seeded constructor: produces a deterministic sequence given the same {@code seed}. Used by the
   * AI sidecar's deterministic battle-calculator path so {@code (gamestate, seed) → wire-response}
   * is a pure function (see map-room/map-room#2376 / #2377).
   */
  public PlainRandomSource(final long seed) {
    this.random = new MersenneTwister(seed);
  }

  @Override
  public int[] getRandom(final int max, final int count, final String annotation) {
    checkArgument(max > 0, "max must be > 0 (%s)", annotation);
    checkArgument(count > 0, "count must be > 0 (%s)", annotation);

    final int[] numbers = new int[count];
    for (int i = 0; i < count; i++) {
      numbers[i] = getRandom(max, annotation);
    }
    return numbers;
  }

  @Override
  public int getRandom(final int max, final String annotation) {
    checkArgument(max > 0, "max must be > 0 (%s)", annotation);

    synchronized (lock) {
      return random.nextInt(max);
    }
  }
}
