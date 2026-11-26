package mtgbench.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import mtgbench.config.Format;
import mtgbench.deck.DeckLoader;
import mtgbench.deck.LoadedDeck;
import mtgbench.schedule.GameSpec;
import mtgbench.schedule.Scheduler;
import mtgbench.stats.GameRecord;
import mtgbench.testsupport.ForgeTestSupport;

class GameRunnerTest {
    private static ScheduledExecutorService watchdog;
    private static List<LoadedDeck> aggro;
    private static List<LoadedDeck> landsOnly;

    @BeforeAll
    static void setUp(@TempDir Path dir) throws Exception {
        ForgeTestSupport.init();
        watchdog = Executors.newSingleThreadScheduledExecutor();
        DeckLoader loader = new DeckLoader(Format.CONSTRUCTED, true);
        aggro = List.of(loader.load(Path.of("../examples/decks/mono-red-burn.txt")),
                loader.load(Path.of("../examples/decks/mono-green-stompy.txt")));
        // Two decks that can only end by decking out, around turn 110.
        landsOnly = List.of(loader.load(write(dir, "forests.txt", "60 Forest\n")),
                loader.load(write(dir, "islands.txt", "60 Island\n")));
    }

    @AfterAll
    static void tearDown() {
        watchdog.shutdownNow();
    }

    private static Path write(Path dir, String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    private static GameRunner runner(List<LoadedDeck> decks, int maxRounds, int timeoutSec) {
        return new GameRunner("test", "gauntlet", Format.CONSTRUCTED, decks, null, maxRounds, timeoutSec,
                watchdog, null);
    }

    @Test
    void forcedStartingPlayerAndCompleteRecords() {
        GameRunner r = runner(aggro, 20, 120);
        for (GameSpec spec : Scheduler.gauntlet(1, 6, 5)) {
            GameRecord g = r.play(spec);
            assertNull(g.error(), g.toString());
            assertEquals(spec.targetOnPlay(), g.targetPlayer().onPlay(), "game " + spec.gameId());
            assertTrue(g.outcome().equals(GameRecord.WIN) || g.outcome().equals(GameRecord.LOSS)
                    || g.outcome().equals(GameRecord.DRAW), g.toString());
            if (!g.outcome().equals(GameRecord.DRAW)) {
                int loser = 1 - g.winnerSeat();
                assertEquals("LifeReachedZero", g.players().get(loser).lossReason(), g.toString());
                assertNull(g.players().get(g.winnerSeat()).lossReason());
            }
            assertEquals("mono-red-burn vs mono-green-stompy", g.matchupName());
        }
    }

    @Test
    void sameSeedSameGame() {
        GameRunner r = runner(aggro, 20, 120);
        GameSpec spec = Scheduler.gauntlet(1, 3, 99).get(2);
        GameRecord a = r.play(spec);
        GameRecord b = r.play(spec);
        assertEquals(a.outcome(), b.outcome());
        assertEquals(a.turns(), b.turns());
        assertEquals(a.players(), b.players());
    }

    @Test
    void roundCapEndsGameAsTimeoutWithoutWinner() {
        GameRecord g = runner(landsOnly, 3, 120).play(Scheduler.gauntlet(1, 1, 1).get(0));
        assertEquals(GameRecord.TIMEOUT, g.outcome(), g.toString());
        assertEquals("turnCap", g.stopReason());
        assertNull(g.winnerSeat());
        assertEquals(7, g.turns(), "3 rounds x 2 players, stopped when turn 7 began");
        g.players().forEach(p -> assertNull(p.lossReason(), "nobody lost: " + g));
    }

    @Test
    void wallClockLimitEndsGameAsTimeout() {
        GameRecord g = runner(landsOnly, 1000, 1).play(Scheduler.gauntlet(1, 1, 1).get(0));
        assertEquals(GameRecord.TIMEOUT, g.outcome(), g.toString());
        assertEquals("timeout", g.stopReason());
        assertNull(g.winnerSeat());
        assertTrue(g.durationMs() < 10_000, "stopped promptly: " + g.durationMs() + " ms");
    }

    @Test
    void naturalEndByDecking() {
        GameRecord g = runner(landsOnly, 1000, 120).play(Scheduler.gauntlet(1, 1, 1).get(0));
        assertTrue(g.outcome().equals(GameRecord.WIN) || g.outcome().equals(GameRecord.LOSS), g.toString());
        assertEquals("Milled", g.players().get(1 - g.winnerSeat()).lossReason(), g.toString());
    }
}
