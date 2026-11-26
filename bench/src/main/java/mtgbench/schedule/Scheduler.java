package mtgbench.schedule;

import java.util.ArrayList;
import java.util.List;

/**
 * Expands a run into the exact list of games to play. Deterministic: the same inputs always give
 * the same specs, which is what makes seeds meaningful.
 */
public final class Scheduler {
    private Scheduler() {}

    /**
     * Gauntlet: the target deck plays {@code gamesPerMatchup} games against each opponent.
     *
     * <p>Play/draw is balanced by construction: in each matchup the target is on the play in games
     * 0, 2, 4, ... and on the draw in 1, 3, 5, ... (so ⌈N/2⌉ on the play). Games are interleaved
     * across matchups, so a run stopped early still has comparable data for every opponent.
     */
    public static List<GameSpec> gauntlet(int opponents, int gamesPerMatchup, long runSeed) {
        if (opponents < 1 || gamesPerMatchup < 1) {
            throw new IllegalArgumentException("need at least one opponent and one game");
        }
        List<GameSpec> specs = new ArrayList<>(opponents * gamesPerMatchup);
        int gameId = 0;
        for (int k = 0; k < gamesPerMatchup; k++) {
            int startingSeat = k % 2 == 0 ? 0 : 1;
            for (int m = 0; m < opponents; m++) {
                specs.add(new GameSpec(gameId, m, List.of(0, m + 1), startingSeat, gameSeed(runSeed, gameId)));
                gameId++;
            }
        }
        return specs;
    }

    /** SplitMix64 of (runSeed, gameId): well-spread, stable across JVMs and versions. */
    public static long gameSeed(long runSeed, int gameId) {
        long z = runSeed + 0x9E3779B97F4A7C15L * (gameId + 1L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
