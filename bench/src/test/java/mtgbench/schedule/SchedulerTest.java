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
}
