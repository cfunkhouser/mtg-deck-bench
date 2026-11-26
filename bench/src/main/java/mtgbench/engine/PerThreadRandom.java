package mtgbench.engine;

import java.util.Random;

/**
 * Forge draws all game randomness from one global {@code MyRandom.getRandom()}. With several games
 * running on different threads, a single seeded Random would be shared and interleaved
 * nondeterministically. Installing this as the global Random gives each thread its own stream,
 * which we reseed at the start of every game.
 */
public final class PerThreadRandom extends Random {
    private static final InheritableThreadLocal<Random> CURRENT = new InheritableThreadLocal<>() {
        @Override protected Random initialValue() { return new Random(); }
    };

    public static void reseedCurrentThread(long seed) {
        CURRENT.set(new Random(seed));
    }

    @Override
    protected int next(int bits) {
        return CURRENT.get().nextInt() >>> (32 - bits);
    }

    @Override
    public void setSeed(long seed) {
        // Called by Random's constructor before CURRENT exists, and by anyone trying to reseed the
        // shared instance. Reseed only this thread's stream.
        if (CURRENT != null) {
            reseedCurrentThread(seed);
        }
    }
}
