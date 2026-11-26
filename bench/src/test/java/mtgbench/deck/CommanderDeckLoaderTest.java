package mtgbench.deck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import forge.item.PaperCard;
import mtgbench.config.Format;
import mtgbench.testsupport.ForgeTestSupport;

/**
 * Commander deck inputs. The export styles here are written from the formats' conventions, not copied
 * from live exports; add real exports from the deck sites you use as fixtures when you have them.
 */
class CommanderDeckLoaderTest {
    @TempDir
    Path dir;

    /** Legality off: these tests are about parsing; legality has its own test. */
    private final DeckLoader loader = new DeckLoader(Format.COMMANDER, false);

    @BeforeAll
    static void forge() {
        ForgeTestSupport.init();
    }

    private Path write(String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    private static List<String> commanders(LoadedDeck d) {
        return d.deck().getCommanders().stream().map(PaperCard::getName).toList();
    }

    @Test
    void arenaStyleCommanderAndDeckHeaders() throws Exception {
        LoadedDeck d = loader.load(write("arena.txt", """
                Commander
                1 Krenko, Mob Boss (DDT) 52

                Deck
                1 Sol Ring
                98 Mountain
                """));
        assertEquals(List.of("Krenko, Mob Boss"), commanders(d));
        assertEquals(99, d.deck().getMain().countAll());
    }

    @Test
    void commanderHeaderThenBlankLineWithoutDeckHeader() throws Exception {
        LoadedDeck d = loader.load(write("noheader.txt", """
                Commander
                1 Krenko, Mob Boss

                1 Sol Ring
                98 Mountain
                """));
        assertEquals(List.of("Krenko, Mob Boss"), commanders(d));
        assertEquals(99, d.deck().getMain().countAll());
    }

    @Test
    void cmdrMarker() throws Exception {
        LoadedDeck d = loader.load(write("mtgo.txt", """
                1 Sol Ring
                1 Krenko, Mob Boss *CMDR*

                98 Mountain
                """));
        assertEquals(List.of("Krenko, Mob Boss"), commanders(d));
        assertEquals(99, d.deck().getMain().countAll(), "blank lines don't start a sideboard in Commander");
    }

    @Test
    void archidektCategoryTags() throws Exception {
        LoadedDeck d = loader.load(write("archidekt.txt", """
                1x Krenko, Mob Boss (ddt) 52 [Commander{top}]
                1x Sol Ring (cmm) 400 [Ramp] ^Have,#37d67a^
                98x Mountain (dmu) 269 [Land]
                """));
        assertEquals(List.of("Krenko, Mob Boss"), commanders(d));
        assertEquals(99, d.deck().getMain().countAll());
    }

    @Test
    void partnerCommanders() throws Exception {
        LoadedDeck d = loader.load(write("partners.txt", """
                Commander
                1 Thrasios, Triton Hero
                1 Tymna the Weaver

                Deck
                1 Sol Ring
                97 Island
                """));
        assertEquals(2, commanders(d).size());
    }

    @Test
    void forgeDeckFileCommanderSection() throws Exception {
        LoadedDeck d = loader.load(write("krenko.dck", """
                [metadata]
                Name=Krenko
                [Commander]
                1 Krenko, Mob Boss
                [Main]
                1 Sol Ring
                98 Mountain
                """));
        assertEquals(List.of("Krenko, Mob Boss"), commanders(d));
    }

    @Test
    void missingCommanderIsAnErrorNotAGuess() throws Exception {
        Path p = write("nocmdr.txt", "1 Krenko, Mob Boss\n1 Sol Ring\n98 Mountain\n");
        DeckException e = assertThrows(DeckException.class, () -> loader.load(p));
        assertTrue(e.getMessage().contains("no commander"), e.getMessage());
    }

    @Test
    void legalityChecksSizeSingletonAndColourIdentity() throws Exception {
        DeckLoader strict = new DeckLoader(Format.COMMANDER, true);
        Path small = write("small.txt", "Commander\n1 Krenko, Mob Boss\n\nDeck\n1 Sol Ring\n50 Mountain\n");
        assertTrue(assertThrows(DeckException.class, () -> strict.load(small)).getMessage().contains("at least"));

        Path dupes = write("dupes.txt", "Commander\n1 Krenko, Mob Boss\n\nDeck\n2 Sol Ring\n97 Mountain\n");
        assertTrue(assertThrows(DeckException.class, () -> strict.load(dupes)).getMessage().contains("copies"));

        Path colour = write("colour.txt", "Commander\n1 Krenko, Mob Boss\n\nDeck\n1 Counterspell\n98 Mountain\n");
        assertTrue(assertThrows(DeckException.class, () -> strict.load(colour)).getMessage().contains("color identity"));
    }

    @Test
    void bannedCardIsRejected() throws Exception {
        DeckLoader strict = new DeckLoader(Format.COMMANDER, true);
        Path p = write("banned.txt", "Commander\n1 Krenko, Mob Boss\n\nDeck\n1 Sol Ring\n1 Mana Crypt\n97 Mountain\n");
        DeckException e = assertThrows(DeckException.class, () -> strict.load(p));
        assertTrue(e.getMessage().contains("Mana Crypt"), e.getMessage());
    }
}
