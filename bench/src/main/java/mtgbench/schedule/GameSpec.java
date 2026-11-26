package mtgbench.schedule;

import java.util.List;

/**
 * One game to play.
 *
 * @param gameId       unique within a run; also the order games are started in
 * @param matchup      index of the matchup (gauntlet: which opponent, 0-based)
 * @param seatDecks    deck index sitting in each seat (0 = target deck, 1.. = opponents in config order)
 * @param startingSeat seat that takes the first turn
 * @param seed         seed for this game's random stream
 */
public record GameSpec(int gameId, int matchup, List<Integer> seatDecks, int startingSeat, long seed) {
    public GameSpec {
        seatDecks = List.copyOf(seatDecks);
        if (startingSeat < 0 || startingSeat >= seatDecks.size()) {
            throw new IllegalArgumentException("startingSeat out of range");
        }
    }

    /** Seat of the target deck. */
    public int targetSeat() {
        return seatDecks.indexOf(0);
    }

    public boolean targetOnPlay() {
        return startingSeat == targetSeat();
    }
}
