package mtgbench.config;

import java.nio.file.Path;
import java.util.List;

import mtgbench.stats.Aggregator;

/**
 * A fully resolved benchmark configuration: YAML file (if any) merged with command-line flags and
 * defaults, with all paths absolute. Written next to the results as {@code config.resolved.yaml}.
 */
public record BenchConfig(
        Mode mode,
        Format format,
        int games,
        DeckRef target,
        List<DeckRef> opponents,
        String aiProfile,
        int maxRounds,
        int gameTimeoutSec,
        long seed,
        int threads,
        Path output,
        boolean checkLegality,
        double maxErrorRate,
        boolean gameLogs,
        Shard shard,
        Adaptive adaptive) {

    public enum Mode { GAUNTLET, POD }

    /**
     * Play only part of the run: games whose id modulo {@code count} equals {@code index}. Shards of
     * one run (same seed and decks) can be played on different machines and combined with merge.
     */
    public record Shard(int index, int count) {
        public Shard {
            if (count < 1 || index < 0 || index >= count) {
                throw new IllegalArgumentException("shard must be i/k with 0 <= i < k, got " + index + "/" + count);
            }
        }

        public static Shard parse(String text) {
            String[] parts = text.split("/");
            if (parts.length != 2) {
                throw new IllegalArgumentException("shard must look like 0/4, got " + text);
            }
            try {
                return new Shard(Integer.parseInt(parts[0].strip()), Integer.parseInt(parts[1].strip()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("shard must look like 0/4, got " + text);
            }
        }

        public boolean includes(int gameId) {
            return gameId % count == index;
        }

        @Override
        public String toString() {
            return index + "/" + count;
        }
    }

    /**
     * Adaptive game count ({@code --target-ci}): play {@code games} per matchup first, then keep adding
     * games until every matchup's 95% CI half-width is at most {@code targetCi}, or it has played
     * {@code maxGames}. In pod mode the table's win share is the one "matchup".
     *
     * @param targetCi wanted CI half-width as a share, e.g. 0.05 for ±5 percentage points
     * @param maxGames cap per matchup (pod: in total)
     */
    public record Adaptive(double targetCi, int maxGames) {
        /** Undecided games (timeouts, errors) the default cap leaves room for. */
        static final double DEFAULT_CAP_HEADROOM = 1.25;

        public Adaptive {
            if (!(targetCi > 0 && targetCi < 0.5)) {
                throw new IllegalArgumentException("target CI must be a half-width between 0 and 0.5 (0.05 = ±5 pp), got "
                        + targetCi);
            }
            if (maxGames < 1) {
                throw new IllegalArgumentException("max games must be at least 1");
            }
        }

        /**
         * Default cap: the decided games a 50% matchup needs to reach {@code targetCi} (the hardest case),
         * plus room for 25% undecided games, rounded up to a multiple of {@code step}.
         */
        public static int defaultMaxGames(double targetCi, int step) {
            int needed = (int) Math.ceil(decidedGamesNeeded(0.5, targetCi) * DEFAULT_CAP_HEADROOM);
            return roundUp(needed, step);
        }

        /** Fewest decided games at which a 95% Wilson interval around rate {@code p} is at most ±{@code halfWidth}. */
        public static int decidedGamesNeeded(double p, double halfWidth) {
            int hi = 1;
            while (Aggregator.wilsonHalfWidth(p, hi) > halfWidth) {
                hi *= 2;
            }
            int lo = hi / 2;   // lo fails (or is 0), hi succeeds
            while (hi - lo > 1) {
                int mid = (lo + hi) >>> 1;
                if (mid > 0 && Aggregator.wilsonHalfWidth(p, mid) <= halfWidth) {
                    hi = mid;
                } else {
                    lo = mid;
                }
            }
            return hi;
        }

        public static int roundUp(int n, int step) {
            return ((n + step - 1) / step) * step;
        }
    }

    /** A deck file; {@code weight} is its metagame share for gauntlet scoring (opponents only). */
    public record DeckRef(Path path, double weight) {}

    public static final int DEFAULT_GAMES = 100;
    public static final int DEFAULT_MAX_ROUNDS = 20;
    public static final int DEFAULT_TIMEOUT_CONSTRUCTED_SEC = 120;
    public static final int DEFAULT_TIMEOUT_COMMANDER_SEC = 600;
    public static final double DEFAULT_MAX_ERROR_RATE = 0.05;

    /** The same config with another thread count (resume may use a different machine). */
    public BenchConfig withThreads(int newThreads) {
        return new BenchConfig(mode, format, games, target, opponents, aiProfile, maxRounds, gameTimeoutSec, seed,
                newThreads, output, checkLegality, maxErrorRate, gameLogs, shard, adaptive);
    }

    /** The same config without the shard (a merged run covers every shard). */
    public BenchConfig withoutShard(Path newOutput) {
        return new BenchConfig(mode, format, games, target, opponents, aiProfile, maxRounds, gameTimeoutSec, seed,
                threads, newOutput, checkLegality, maxErrorRate, gameLogs, null, adaptive);
    }

    /** Games per matchup (pod: in total) the schedule can hold: the cap when adaptive, else {@code games}. */
    public int scheduledGames() {
        return adaptive != null ? adaptive.maxGames() : games;
    }

    public BenchConfig {
        opponents = List.copyOf(opponents);
        if (games < 1) {
            throw new IllegalArgumentException("games must be at least 1");
        }
        if (opponents.isEmpty()) {
            throw new IllegalArgumentException("at least one opponent deck is needed");
        }
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be at least 1");
        }
        if (maxRounds < 1 || gameTimeoutSec < 1) {
            throw new IllegalArgumentException("maxRounds and gameTimeoutSec must be positive");
        }
        if (adaptive != null) {
            if (adaptive.maxGames() < games) {
                throw new IllegalArgumentException("max games (" + adaptive.maxGames()
                        + ") must be at least the first batch of " + games + " games");
            }
            if (shard != null) {
                throw new IllegalArgumentException("a target CI can't be combined with a shard: when to stop depends on "
                        + "every game's result");
            }
        }
        for (DeckRef o : opponents) {
            if (!(o.weight() > 0)) {
                throw new IllegalArgumentException("opponent weight must be positive: " + o.path());
            }
        }
    }
}
