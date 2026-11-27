package mtgbench.fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import mtgbench.config.Format;

/** Sorting Archidekt's JSON into deck sections, and the text written. No network, no Forge. */
class FetchedDeckTest {
    private static FetchedDeck parse(String json, boolean keepOutOfDeck) throws Exception {
        return FetchedDeck.fromArchidekt(new ObjectMapper().readTree(json), keepOutOfDeck);
    }

    @Test
    void sortsCardsIntoSections() throws Exception {
        FetchedDeck d = parse(ArchidektTestDecks.constructed(), true);
        assertEquals("Test  Burn", d.name());
        assertEquals(Format.CONSTRUCTED, d.format());
        assertTrue(d.commander().isEmpty());
        // Printings are added together, split cards keep both halves, adventures use the front face.
        assertEquals(Map.of("Lightning Bolt", 4, "Fire // Ice", 4, "Bonecrusher Giant", 2, "Mountain", 50), d.main());
        assertEquals(60, d.count(d.main()));
        // Sideboard category, companion, and the Maybeboard card even though it is also in Burn.
        assertEquals(Map.of("Smash to Smithereens", 3, "Lurrus of the Dream-Den", 1, "Shock", 2), d.sideboard());
        assertEquals(List.of("Maybeboard"), d.outOfDeck());
        assertEquals(2, d.outOfDeckCards());
    }

    @Test
    void outOfDeckCardsCanBeLeftOut() throws Exception {
        FetchedDeck d = parse(ArchidektTestDecks.constructed(), false);
        assertEquals(Map.of("Smash to Smithereens", 3, "Lurrus of the Dream-Den", 1), d.sideboard());
        assertEquals(2, d.outOfDeckCards());
        assertFalse(d.toText("u").contains("categories not counted"));
    }

    @Test
    void commanderDeck() throws Exception {
        FetchedDeck d = parse(ArchidektTestDecks.commander(), true);
        assertEquals(Format.COMMANDER, d.format());
        assertEquals(Map.of("Krenko, Mob Boss", 1), d.commander());
        assertEquals(Map.of("Village Messenger", 1, "Mountain", 40), d.main());
        assertEquals(Map.of("Goblin Guide", 1), d.sideboard());
        assertEquals(1, d.skippedCustomCards());
    }

    @Test
    void textHasHeadersInOrderAndSortedLines() throws Exception {
        String text = parse(ArchidektTestDecks.commander(), true).toText("https://archidekt.com/decks/7");
        assertEquals("""
                # Goblins
                # From https://archidekt.com/decks/7
                # The sideboard includes 1 card from Archidekt categories not counted in the deck: Maybeboard

                Commander
                1 Krenko, Mob Boss

                Deck
                40 Mountain
                1 Village Messenger

                Sideboard
                1 Goblin Guide
                """, text);
        // Whitespace in the name can't break out of the comment line.
        assertTrue(parse(ArchidektTestDecks.constructed().replace("Test  Burn", "Bad\\nName"), true)
                .toText("u").startsWith("# Bad Name\n"));
    }

    @Test
    void deckIdFromUrls() {
        assertEquals(Optional.of(123456L), Archidekt.deckId("https://archidekt.com/decks/123456/my-deck"));
        assertEquals(Optional.of(123456L), Archidekt.deckId("https://www.archidekt.com/decks/123456"));
        assertEquals(Optional.of(123456L), Archidekt.deckId("archidekt.com/decks/123456/x?tab=1#top"));
        assertEquals(Optional.of(123456L), Archidekt.deckId("HTTP://Archidekt.com/decks/123456/"));
        assertEquals(Optional.of(123456L), Archidekt.deckId("https://archidekt.com/api/decks/123456/"));
        assertEquals(Optional.of(99L), Archidekt.deckId(" 99 "));
        assertEquals(Optional.empty(), Archidekt.deckId("https://moxfield.com/decks/abc"));
        assertEquals(Optional.empty(), Archidekt.deckId("https://archidekt.com/decks/"));
        assertEquals(Optional.empty(), Archidekt.deckId("https://archidekt.com.evil.example/decks/1"));
        assertEquals(Optional.empty(), Archidekt.deckId("https://archidekt.com/decks/99999999999999999999"));
    }
}
