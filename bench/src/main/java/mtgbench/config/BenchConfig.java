package mtgbench.config;

import java.nio.file.Path;
import java.util.List;

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
        Shard shard) {

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
                newThreads, output, checkLegality, maxErrorRate, gameLogs, shard);
    }

    /** The same config without the shard (a merged run covers every shard). */
    public BenchConfig withoutShard(Path newOutput) {
        return new BenchConfig(mode, format, games, target, opponents, aiProfile, maxRounds, gameTimeoutSec, seed,
                threads, newOutput, checkLegality, maxErrorRate, gameLogs, null);
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
        for (DeckRef o : opponents) {
            if (!(o.weight() > 0)) {
                throw new IllegalArgumentException("opponent weight must be positive: " + o.path());
            }
        }
    }
}
