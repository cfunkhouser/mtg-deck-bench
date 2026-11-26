package mtgbench.deck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import forge.deck.DeckSection;
import mtgbench.config.Format;
import mtgbench.testsupport.ForgeTestSupport;

class DeckLoaderTest {
    @TempDir
    Path dir;

    private final DeckLoader loader = new DeckLoader(Format.CONSTRUCTED, true);

    @BeforeAll
    static void forge() {
        ForgeTestSupport.init();
    }

    private Path write(String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    private static int count(LoadedDeck d, DeckSection s) {
        return d.deck().has(s) ? d.deck().get(s).countAll() : 0;
    }

    @Test
    void plainListWithSideboardHeader() throws Exception {
        LoadedDeck d = loader.load(write("burn.txt", """
                4 Lightning Bolt
                4x Chain Lightning
                52 Mountain

                Sideboard
                3 Smash to Smithereens
                """));
        assertEquals("burn", d.name());
        assertEquals(60, count(d, DeckSection.Main));
        assertEquals(3, count(d, DeckSection.Sideboard));
        assertEquals(64, d.sha256().length());
    }

    @Test
    void arenaExportWithSetCodesAndDeckHeader() throws Exception {
        LoadedDeck d = loader.load(write("arena.txt", """
                Deck
                4 Lightning Bolt (M11) 149
                56 Mountain (M21) 272

                Sideboard
                2 Abrade (HOU) 83
                """));
        assertEquals(60, count(d, DeckSection.Main));
        assertEquals(2, count(d, DeckSection.Sideboard));
        assertEquals(java.util.List.of(), d.warnings());
    }

    @Test
    void unknownPrintingFallsBackToCardNameWithWarning() throws Exception {
        LoadedDeck d = loader.load(write("newprint.txt", """
                4 Lightning Bolt (ZZZ) 999
                56 Mountain
                """));
        assertEquals(60, count(d, DeckSection.Main));
        assertEquals(1, d.warnings().size(), d.warnings().toString());
        assertTrue(d.warnings().get(0).contains("printing not found"), d.warnings().toString());
    }

    @Test
    void mtgoExportBlankLineStartsSideboard() throws Exception {
        LoadedDeck d = loader.load(write("mtgo.txt", """
                4 Lightning Bolt
                56 Mountain

                2 Abrade
                """));
        assertEquals(60, count(d, DeckSection.Main));
        assertEquals(2, count(d, DeckSection.Sideboard));
    }

    @Test
    void reportsEveryUnknownCardWithLineNumbers() throws Exception {
        Path p = write("typos.txt", """
                4 Lightning Bolt
                4 Notacard McFakerson
                48 Mountain
                4 Lightnig Bolt
                """);
        DeckException e = assertThrows(DeckException.class, () -> loader.load(p));
        assertEquals(2, e.problems().size(), e.getMessage());
        assertTrue(e.problems().get(0).startsWith("line 2"), e.getMessage());
        assertTrue(e.problems().get(1).startsWith("line 4"), e.getMessage());
    }

    @Test
    void unknownCardInForgeDeckFileIsRejected() throws Exception {
        Path p = write("bad.dck", """
                [metadata]
                Name=Bad
                [main]
                4 Notacard McFakerson
                56 Mountain
                """);
        DeckException e = assertThrows(DeckException.class, () -> loader.load(p));
        assertTrue(e.getMessage().contains("Notacard McFakerson"), e.getMessage());
    }

    @Test
    void forgeDeckFileLoads() throws Exception {
        LoadedDeck d = loader.load(write("good.dck", """
                [metadata]
                Name=Red Deck Wins
                [main]
                4 Lightning Bolt|M11
                56 Mountain
                [sideboard]
                2 Abrade
                """));
        assertEquals("Red Deck Wins", d.name());
        assertEquals(60, count(d, DeckSection.Main));
        assertEquals(2, count(d, DeckSection.Sideboard));
    }

    @Test
    void illegalDeckRejectedUnlessCheckDisabled() throws Exception {
        Path p = write("small.txt", "8 Lightning Bolt\n20 Mountain\n");
        DeckException e = assertThrows(DeckException.class, () -> loader.load(p));
        assertTrue(e.getMessage().contains("not legal"), e.getMessage());

        LoadedDeck d = new DeckLoader(Format.CONSTRUCTED, false).load(p);
        assertEquals(28, count(d, DeckSection.Main));
    }

    @Test
    void exampleDecksLoad() throws Exception {
        for (String name : new String[] {"mono-red-burn.txt", "mono-green-stompy.txt"}) {
            LoadedDeck d = loader.load(Path.of("../examples/decks", name));
            assertEquals(60, count(d, DeckSection.Main), name);
        }
    }
}
