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

    /**
     * Pod: the target deck and all opponents at one free-for-all table, {@code games} times.
     *
     * <p>Seat 0 always takes the first turn, so a deck's seat is its position in turn order. The
     * target's seat cycles fastest (game g: seat g mod P), so the target sits in every turn position
     * equally often in every block of P games. The opponents fill the other seats clockwise after the
     * target, with their order rotated by one every P games, so over P×(P−1) games each opponent sits
     * immediately after the target (and immediately before it) equally often.
     *
     * @param games total games; callers should round up to a multiple of the table size ({@link #podGames})
     */
    public static List<GameSpec> pod(int opponents, int games, long runSeed) {
        if (opponents < 2 || games < 1) {
            throw new IllegalArgumentException("a pod needs at least two opponents and one game");
        }
        int players = opponents + 1;
        List<GameSpec> specs = new ArrayList<>(games);
        for (int g = 0; g < games; g++) {
            int targetSeat = g % players;
            int rotation = (g / players) % opponents;
            Integer[] seats = new Integer[players];
            seats[targetSeat] = 0;
            for (int k = 1; k < players; k++) {
                int seat = (targetSeat + k) % players;
                int opponent = (k - 1 + rotation) % opponents;   // 0-based opponent index
                seats[seat] = opponent + 1;                     // deck index (0 = target)
            }
            specs.add(new GameSpec(g, 0, List.of(seats), 0, gameSeed(runSeed, g)));
        }
        return specs;
    }

    /** Games rounded up to a whole number of seat rotations (a multiple of the table size). */
    public static int podGames(int opponents, int requested) {
        int players = opponents + 1;
        return ((requested + players - 1) / players) * players;
    }

    /** SplitMix64 of (runSeed, gameId): well-spread, stable across JVMs and versions. */
    public static long gameSeed(long runSeed, int gameId) {
        long z = runSeed + 0x9E3779B97F4A7C15L * (gameId + 1L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
