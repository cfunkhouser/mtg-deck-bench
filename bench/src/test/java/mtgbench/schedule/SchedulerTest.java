package mtgbench.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SchedulerTest {
    @Test
    void playDrawIsExactlyBalancedPerMatchup() {
        List<GameSpec> specs = Scheduler.gauntlet(3, 7, 42);
        assertEquals(21, specs.size());
        for (int m = 0; m < 3; m++) {
            final int matchup = m;
            List<GameSpec> mg = specs.stream().filter(s -> s.matchup() == matchup).toList();
            assertEquals(7, mg.size());
            assertEquals(4, mg.stream().filter(GameSpec::targetOnPlay).count(), "ceil(7/2) on the play");
            mg.forEach(s -> assertEquals(List.of(0, matchup + 1), s.seatDecks()));
        }
    }

    @Test
    void matchupsAreInterleavedAndIdsSequential() {
        List<GameSpec> specs = Scheduler.gauntlet(2, 3, 1);
        assertEquals(List.of(0, 1, 0, 1, 0, 1), specs.stream().map(GameSpec::matchup).toList());
        for (int i = 0; i < specs.size(); i++) {
            assertEquals(i, specs.get(i).gameId());
        }
    }

    @Test
    void seedsAreStableAndDistinct() {
        assertEquals(Scheduler.gauntlet(2, 50, 7), Scheduler.gauntlet(2, 50, 7));
        Set<Long> seeds = new HashSet<>();
        Scheduler.gauntlet(2, 50, 7).forEach(s -> seeds.add(s.seed()));
        assertEquals(100, seeds.size());
        assertNotEquals(Scheduler.gameSeed(7, 0), Scheduler.gameSeed(8, 0));
        // Pinned value: changing the seed function would silently change every recorded run.
        assertEquals(-4767286540954276203L, Scheduler.gameSeed(42, 0));
        assertEquals(2949826092126892291L, Scheduler.gameSeed(42, 1));
    }

    @Test
    void podBalancesSeatsAndNeighbours() {
        int opponents = 3, players = 4;
        List<GameSpec> specs = Scheduler.pod(opponents, players * opponents * 2, 11);   // two full blocks
        int[] targetSeats = new int[players];
        int[] after = new int[opponents + 1], before = new int[opponents + 1];
        for (GameSpec s : specs) {
            assertEquals(0, s.startingSeat());
            assertEquals(Set.of(0, 1, 2, 3), new HashSet<>(s.seatDecks()), "every deck seated once");
            int t = s.targetSeat();
            targetSeats[t]++;
            after[s.seatDecks().get((t + 1) % players)]++;
            before[s.seatDecks().get((t + players - 1) % players)]++;
        }
        for (int seat = 0; seat < players; seat++) {
            assertEquals(6, targetSeats[seat], "target in seat " + seat);
        }
        for (int o = 1; o <= opponents; o++) {
            assertEquals(8, after[o], "opponent " + o + " right after the target");
            assertEquals(8, before[o], "opponent " + o + " right before the target");
        }
    }

    @Test
    void podSeatsBalancedInEveryBlockOfTableSize() {
        List<GameSpec> specs = Scheduler.pod(2, 6, 1);
        assertEquals(List.of(0, 1, 2, 0, 1, 2), specs.stream().map(GameSpec::targetSeat).toList());
        assertEquals(9, Scheduler.podGames(2, 7));
        assertEquals(9, Scheduler.podGames(2, 9));
    }
}
