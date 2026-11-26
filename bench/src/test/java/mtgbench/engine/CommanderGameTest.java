package mtgbench.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import mtgbench.schedule.Scheduler;
import mtgbench.stats.GameRecord;
import mtgbench.stats.GameRecord.PlayerRecord;
import mtgbench.testsupport.ForgeTestSupport;

/** Commander rules come from Forge; these tests check the harness actually switches them on. */
class CommanderGameTest {
    private static ScheduledExecutorService watchdog;

    @BeforeAll
    static void setUp() {
        ForgeTestSupport.init();
        watchdog = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterAll
    static void tearDown() {
        watchdog.shutdownNow();
    }

    private static GameRunner runner(List<LoadedDeck> decks, int maxRounds) {
        return new GameRunner("test", "gauntlet", Format.COMMANDER, decks, null, maxRounds, 120, watchdog, null);
    }

    /**
     * Stacked game: Isamaru (a 2/2 for W) and 99 Plains against Progenitus and 99 Islands. Progenitus
     * can never be cast from Islands, so nothing blocks Isamaru. It needs 11 hits for 21 commander
     * damage; by then the opponent has taken 22 of their 40 life. A loss by commander damage at 18 life
     * shows: commanders start in the command zone and get cast, life starts at 40, and the 21-damage
     * rule is in force.
     */
    @Test
    void commanderDamageKillsAtFortyLife(@TempDir Path dir) throws Exception {
        Path attacker = dir.resolve("isamaru.txt");
        Files.writeString(attacker, "Commander\n1 Isamaru, Hound of Konda\n\nDeck\n99 Plains\n");
        Path blank = dir.resolve("progenitus.txt");
        Files.writeString(blank, "Commander\n1 Progenitus\n\nDeck\n99 Island\n");
        // The Progenitus deck breaks colour identity on purpose, so legality checks are off here.
        DeckLoader loader = new DeckLoader(Format.COMMANDER, false);
        List<LoadedDeck> decks = List.of(loader.load(attacker), loader.load(blank));

        GameRecord g = runner(decks, 40).play(Scheduler.gauntlet(1, 1, 3).get(0));
        assertNull(g.error(), g.toString());
        assertEquals(GameRecord.WIN, g.outcome(), g.toString());
        PlayerRecord target = g.players().get(0);
        PlayerRecord opponent = g.players().get(1);
        assertEquals("CommanderDamage", opponent.lossReason(), g.toString());
        assertEquals(18, opponent.finalLife(), "40 starting life minus 11 hits of 2: " + g);
        assertEquals(22, opponent.commanderDamageTaken(), g.toString());
        assertEquals(1, target.commanderCasts(), g.toString());
        assertEquals(1, target.firstCommanderCastRound(), "Isamaru costs one mana: " + g);
        assertEquals(0, opponent.commanderCasts(), g.toString());
        assertNull(opponent.firstCommanderCastRound());
    }

    @Test
    void exampleCommanderDecksPlayLegalGames() throws Exception {
        DeckLoader loader = new DeckLoader(Format.COMMANDER, true);
        List<LoadedDeck> decks = List.of(
                loader.load(Path.of("../examples/decks/commander/krenko-goblins.txt")),
                loader.load(Path.of("../examples/decks/commander/ezuri-elves.txt")));
        GameRunner r = runner(decks, 25);
        for (var spec : Scheduler.gauntlet(1, 2, 8)) {
            GameRecord g = r.play(spec);
            assertNull(g.error(), g.toString());
            assertEquals(spec.targetOnPlay(), g.targetPlayer().onPlay());
            assertEquals("commander", g.format());
            for (PlayerRecord p : g.players()) {
                assertNotNull(p.commanderCasts(), g.toString());
                assertNotNull(p.commanderDamageTaken(), g.toString());
            }
            assertTrue(g.players().stream().anyMatch(p -> p.commanderCasts() > 0), "someone cast a commander: " + g);
        }
    }
}
