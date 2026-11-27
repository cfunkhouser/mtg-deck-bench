package mtgbench.fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import mtgbench.config.Format;

/** MTGJSON deck files to deck sections, and deck references. No network, no Forge. */
class MtgjsonDeckTest {
    private static FetchedDeck parse(String file) throws Exception {
        return FetchedDeck.fromMtgjson(new ObjectMapper().readTree(file).path("data"));
    }

    @Test
    void sectionsAndNames() throws Exception {
        FetchedDeck d = parse(MtgjsonTestDecks.goblins("Goblin Gang"));
        assertEquals("Goblin Gang", d.name());
        assertEquals(Format.COMMANDER, d.format());
        // displayCommander (the promo on the box) and tokens are not part of the deck.
        assertEquals(Map.of("Krenko, Mob Boss", 1), d.commander());
        assertEquals(Map.of("Village Messenger", 1, "Fire // Ice", 1, "Mountain", 38), d.main());
        assertTrue(d.sideboard().isEmpty());

        FetchedDeck elves = parse(MtgjsonTestDecks.elves());
        assertEquals(Map.of("Elvish Mystic", 1), elves.sideboard());
    }

    @Test
    void sameCardsIgnoresTheName() throws Exception {
        FetchedDeck a = parse(MtgjsonTestDecks.goblins("Goblin Gang"));
        assertTrue(a.sameCards(parse(MtgjsonTestDecks.goblins("Goblin Gang Collector's Edition"))));
        assertFalse(a.sameCards(parse(MtgjsonTestDecks.elves())));
    }

    @Test
    void deckReferences() {
        assertEquals(Optional.of("ForcesOfTheImperium_40K"), Mtgjson.deckFileName("mtgjson:ForcesOfTheImperium_40K"));
        assertEquals(Optional.of("ForcesOfTheImperium_40K"),
                Mtgjson.deckFileName("https://mtgjson.com/api/v5/decks/ForcesOfTheImperium_40K.json"));
        assertEquals(Optional.of("A_B"), Mtgjson.deckFileName("mtgjson.com/api/v5/decks/A_B"));
        assertEquals(Optional.empty(), Mtgjson.deckFileName("mtgjson:../etc/passwd"));
        assertEquals(Optional.empty(), Mtgjson.deckFileName("https://mtgjson.com/api/v5/AllPrintings.json"));
        assertEquals(Optional.empty(), Mtgjson.deckFileName("https://archidekt.com/decks/1"));
        assertEquals("https://mtgjson.com/api/v5/decks/X_Y.json", Mtgjson.deckUrl("X_Y"));
    }
}
