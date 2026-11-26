package mtgbench.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import mtgbench.stats.GameRecord.PlayerRecord;
import mtgbench.testsupport.ForgeTestSupport;

/** Four-player free-for-all games with real decks. */
class PodGameTest {
    private static ScheduledExecutorService watchdog;
    private static List<LoadedDeck> decks;

    @BeforeAll
    static void setUp(@TempDir Path dir) throws Exception {
        ForgeTestSupport.init();
        watchdog = Executors.newSingleThreadScheduledExecutor();
        DeckLoader loader = new DeckLoader(Format.CONSTRUCTED, true);
        // A fourth deck: the burn list under another name (deck names must differ).
        Path burn2 = dir.resolve("burn-copy.txt");
        Files.copy(Path.of("../examples/decks/mono-red-burn.txt"), burn2);
        decks = List.of(loader.load(Path.of("../examples/decks/mono-red-burn.txt")),
                loader.load(Path.of("../examples/decks/mono-green-stompy.txt")),
                loader.load(Path.of("../examples/decks/mono-white-humans.txt")),
                loader.load(burn2));
    }

    @AfterAll
    static void tearDown() {
        watchdog.shutdownNow();
    }

    @Test
    void fourPlayerGamesForceFirstPlayerAndRecordPlacements() {
        GameRunner runner = new GameRunner("test", "pod", Format.CONSTRUCTED, decks, null, 30, 120, watchdog, null);
        List<GameSpec> specs = Scheduler.pod(3, 8, 21);   // target in every seat twice
        int decided = 0;
        for (GameSpec spec : specs) {
            GameRecord g = runner.play(spec);
            assertNull(g.error(), g.toString());
            assertEquals(4, g.players().size());
            for (PlayerRecord p : g.players()) {
                assertEquals(p.seat() == 0, p.onPlay(), "seat 0 takes the first turn: " + g);
                assertEquals(spec.seatDecks().get(p.seat()) == 0, p.target());
            }
            assertEquals(spec.targetSeat(), g.targetPlayer().seat());
            if (g.winnerSeat() == null) {
                continue;   // draw or timeout: no placements
            }
            decided++;
            List<Integer> places = new ArrayList<>();
            for (PlayerRecord p : g.players()) {
                assertNotNull(p.placement(), "every player placed when there is a winner: " + g);
                places.add(p.placement());
                if (p.seat() == g.winnerSeat()) {
                    assertEquals(1, p.placement());
                    assertNull(p.lossReason());
                } else {
                    assertNotNull(p.lossReason(), g.toString());
                    assertNotNull(p.eliminatedRound(), g.toString());
                }
            }
            assertEquals(List.of(1, 2, 3, 4), places.stream().sorted().toList(), g.toString());
            // Someone placed later can't have been eliminated in an earlier round.
            for (PlayerRecord a : g.players()) {
                for (PlayerRecord b : g.players()) {
                    if (a.eliminatedRound() != null && b.eliminatedRound() != null && a.placement() < b.placement()) {
                        assertTrue(a.eliminatedRound() >= b.eliminatedRound(), g.toString());
                    }
                }
            }
            assertEquals(g.winnerSeat() == g.targetPlayer().seat() ? GameRecord.WIN : GameRecord.LOSS, g.outcome());
        }
        assertTrue(decided >= 6, "most 4-player aggro games should finish: " + decided);
    }
}
