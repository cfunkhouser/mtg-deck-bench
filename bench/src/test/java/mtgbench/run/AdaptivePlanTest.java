package mtgbench.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.IntFunction;

import org.junit.jupiter.api.Test;

import mtgbench.config.BenchConfig;
import mtgbench.config.BenchConfig.Adaptive;
import mtgbench.config.BenchConfig.DeckRef;
import mtgbench.config.Format;
import mtgbench.schedule.GameSpec;
import mtgbench.schedule.Scheduler;
import mtgbench.stats.Aggregator;
import mtgbench.stats.GameRecord;
import mtgbench.stats.GameRecord.PlayerRecord;

/** The adaptive planner, driven with scripted game outcomes; nothing here touches Forge. */
class AdaptivePlanTest {
    private static BenchConfig config(BenchConfig.Mode mode, int opponents, int games, Adaptive adaptive) {
        List<DeckRef> opps = new ArrayList<>();
        for (int i = 0; i < opponents; i++) {
            opps.add(new DeckRef(Path.of("/decks/o" + i + ".txt"), 1.0));
        }
        return new BenchConfig(mode, Format.CONSTRUCTED, games, new DeckRef(Path.of("/decks/t.txt"), 1.0), opps,
                null, 20, 120, 42, 1, Path.of("/results"), true, 0.05, false, null, adaptive);
    }

    private static GameRecord record(GameSpec spec, String outcome) {
        List<PlayerRecord> players = new ArrayList<>();
        for (int seat = 0; seat < spec.seatDecks().size(); seat++) {
            boolean target = spec.seatDecks().get(seat) == 0;
            players.add(new PlayerRecord(seat, target ? "T" : "B" + spec.seatDecks().get(seat), target,
                    seat == spec.startingSeat(), 0, 20, null));
        }
        return new GameRecord("r", spec.gameId(), "gauntlet", "constructed", spec.matchup(), "m", spec.seed(), players,
                null, outcome, null, 10, 100, "x", null);
    }

    /** Plays the run to the end like BenchRun.execute, with {@code outcome} deciding every game. */
    private static List<GameRecord> playOut(BenchConfig config, List<GameRecord> start, IntFunction<String> outcome) {
        List<GameRecord> records = new ArrayList<>(start);
        for (int batches = 0; ; batches++) {
            assertTrue(batches < 100, "the run must end");
            List<GameSpec> todo = AdaptivePlan.remaining(config, records);
            if (todo.isEmpty()) {
                return records;
            }
            todo.forEach(s -> records.add(record(s, outcome.apply(s.gameId()))));
        }
    }

    /** Win with probability {@code p}, the same answer for a game id every time. */
    private static IntFunction<String> coin(double p, long seed) {
        // A hash, not new Random(seed + id): the first draws of consecutively seeded Randoms are correlated.
        return id -> (Scheduler.gameSeed(seed, id) >>> 11) * 0x1.0p-53 < p ? GameRecord.WIN : GameRecord.LOSS;
    }

    private static List<GameRecord> ofMatchup(List<GameRecord> records, int m) {
        return records.stream().filter(r -> r.matchup() == m).toList();
    }

    @Test
    void decidedGamesNeededMatchesTheWilsonInterval() {
        int n = Adaptive.decidedGamesNeeded(0.5, 0.05);
        assertTrue(Aggregator.wilsonHalfWidth(0.5, n) <= 0.05);
        assertTrue(Aggregator.wilsonHalfWidth(0.5, n - 1) > 0.05);
        assertTrue(n > 370 && n < 390, "about 384 at 50%, got " + n);
        assertTrue(Adaptive.decidedGamesNeeded(0.9, 0.05) < n, "lopsided matchups need fewer games");
        assertEquals(Adaptive.roundUp((int) Math.ceil(n * 1.25), 2), Adaptive.defaultMaxGames(0.05, 2));
    }

    @Test
    void lopsidedMatchupStopsEarlyAndCloseOneKeepsGoing() {
        BenchConfig config = config(BenchConfig.Mode.GAUNTLET, 2, 40, new Adaptive(0.08, 400));
        // Matchup 0 is a near-sure win, matchup 1 a coin flip.
        IntFunction<String> outcome = id -> id % 2 == 0 ? (id % 40 == 0 ? GameRecord.LOSS : GameRecord.WIN)
                : coin(0.5, 1).apply(id);
        List<GameRecord> records = playOut(config, List.of(), outcome);
        List<AdaptivePlan.ChainStatus> status = AdaptivePlan.status(config, records);

        int easy = ofMatchup(records, 0).size(), close = ofMatchup(records, 1).size();
        assertTrue(status.get(0).finished() && status.get(1).finished());
        assertFalse(status.get(0).capped() || status.get(1).capped());
        assertTrue(status.get(0).halfWidth() <= 0.08 && status.get(1).halfWidth() <= 0.08);
        assertTrue(easy < close, "the lopsided matchup needs fewer games: " + easy + " vs " + close);
        assertTrue(close >= Adaptive.decidedGamesNeeded(0.6, 0.08), "a coin flip needs ~150 games at ±8%");
        assertEquals(0, close % 2, "stages after the first are play/draw pairs");
        long onPlay = ofMatchup(records, 1).stream().filter(r -> r.targetPlayer().onPlay()).count();
        assertEquals(close / 2, onPlay, "play/draw stays exactly balanced");
    }

    @Test
    void stagesGrowWithinBoundsAndStopAtTheCap() {
        BenchConfig config = config(BenchConfig.Mode.GAUNTLET, 1, 20, new Adaptive(0.01, 101));
        List<GameRecord> records = playOut(config, List.of(), coin(0.5, 2));
        assertEquals(101, records.size(), "an unreachable target plays exactly up to the cap");
        AdaptivePlan.ChainStatus st = AdaptivePlan.status(config, records).get(0);
        assertTrue(st.finished() && st.capped());

        assertEquals(40, AdaptivePlan.nextStage(20, 20, 10, 0.01, 2, 1000), "at most doubles");
        assertEquals(26, AdaptivePlan.nextStage(20, 20, 19, 0.2, 2, 1000), "grows by at least a quarter, even");
        assertEquals(40, AdaptivePlan.nextStage(20, 0, 0, 0.05, 2, 1000), "nothing decided: double");
        assertEquals(30, AdaptivePlan.nextStage(20, 20, 10, 0.01, 2, 30), "never past the cap");
        assertEquals(24, AdaptivePlan.nextStage(16, 16, 15, 0.2, 6, 1000), "rounded up to the step (pod table size)");
    }

    @Test
    void timeoutsDontCountTowardTheCi() {
        BenchConfig config = config(BenchConfig.Mode.GAUNTLET, 1, 10, new Adaptive(0.15, 400));
        // Every other game times out: the CI only narrows with decided games, so twice as many are played.
        IntFunction<String> outcome = id -> id % 2 == 1 ? GameRecord.TIMEOUT : coin(0.5, 3).apply(id);
        List<GameRecord> records = playOut(config, List.of(), outcome);
        List<GameRecord> decided = records.stream().filter(r -> !r.outcome().equals(GameRecord.TIMEOUT)).toList();
        double wins = decided.stream().filter(r -> r.outcome().equals(GameRecord.WIN)).count();
        AdaptivePlan.ChainStatus st = AdaptivePlan.status(config, records).get(0);
        assertEquals(Aggregator.wilsonHalfWidth(wins / decided.size(), decided.size()), st.halfWidth(), 1e-12);
        assertTrue(st.halfWidth() <= 0.15);
        assertTrue(Aggregator.wilsonHalfWidth(wins / decided.size(), records.size()) < st.halfWidth(),
                "counting timeouts would have claimed a narrower CI");
        assertEquals(records.size() / 2, decided.size());
    }

    @Test
    void resumeFromAnyInterruptionEndsWithTheSameGames() {
        BenchConfig config = config(BenchConfig.Mode.GAUNTLET, 3, 24, new Adaptive(0.07, 600));
        IntFunction<String> outcome = id -> coin(0.35 + 0.15 * (id % 3), 4).apply(id);
        List<GameRecord> full = playOut(config, List.of(), outcome);
        List<Integer> fullIds = full.stream().map(GameRecord::gameId).sorted().toList();

        Random cut = new Random(5);
        for (int trial = 0; trial < 25; trial++) {
            // Keep a random subset of the finished run, as if games had finished in any order before a crash.
            List<GameRecord> partial = new ArrayList<>();
            int keepBelow = cut.nextInt(full.size());
            for (GameRecord r : full) {
                if (r.gameId() < keepBelow && cut.nextDouble() < 0.8) {
                    partial.add(r);
                }
            }
            List<Integer> resumed = playOut(config, partial, outcome).stream().map(GameRecord::gameId).sorted().toList();
            assertEquals(fullIds, resumed, "trial " + trial);
        }
        assertTrue(AdaptivePlan.remaining(config, full).isEmpty());
    }

    @Test
    void podStopsOnWholeSeatRotations() {
        BenchConfig config = config(BenchConfig.Mode.POD, 3, 8, new Adaptive(0.1, 400));
        List<GameRecord> records = playOut(config, List.of(), coin(0.25, 6));
        assertEquals(0, records.size() % 4, "a multiple of the table size: " + records.size());
        assertEquals(1, AdaptivePlan.status(config, records).size());
        long seat0 = records.stream().filter(r -> r.targetPlayer().seat() == 0).count();
        assertEquals(records.size() / 4, seat0, "the target sits in every seat equally often");
    }

    @Test
    void statusBeforeAnyGameAndFixedRunsAreRejected() {
        BenchConfig config = config(BenchConfig.Mode.GAUNTLET, 2, 10, new Adaptive(0.05, 100));
        assertEquals(20, AdaptivePlan.remaining(config, List.of()).size(), "the first stage for both matchups");
        AdaptivePlan.ChainStatus st = AdaptivePlan.status(config, List.of()).get(0);
        assertEquals(10, st.planned());
        assertNull(st.halfWidth());
        assertThrows(IllegalArgumentException.class,
                () -> AdaptivePlan.status(config(BenchConfig.Mode.GAUNTLET, 1, 10, null), List.of()));
    }

    @Test
    void configRejectsBadCombinations() {
        assertThrows(IllegalArgumentException.class, () -> new Adaptive(0.6, 100));
        assertThrows(IllegalArgumentException.class, () -> new Adaptive(0, 100));
        assertThrows(IllegalArgumentException.class,
                () -> config(BenchConfig.Mode.GAUNTLET, 1, 200, new Adaptive(0.05, 100)), "cap below first batch");
        BenchConfig ok = config(BenchConfig.Mode.GAUNTLET, 1, 10, new Adaptive(0.05, 100));
        assertEquals(100, ok.scheduledGames());
        assertEquals(100, BenchRun.fullSchedule(ok).size(), "the schedule holds every game up to the cap");
    }
}
