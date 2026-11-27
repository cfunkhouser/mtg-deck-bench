package mtgbench.fetch;

import java.util.Map;

/** MTGJSON API answers trimmed to the fields mtgbench reads. */
public final class MtgjsonTestDecks {
    private MtgjsonTestDecks() {}

    private static String card(int count, String name, String layout) {
        return """
                {"count": %d, "name": "%s", "layout": "%s", "setCode": "XYZ", "uuid": "u"}"""
                .formatted(count, name, layout);
    }

    /** A small Commander precon: a commander, a two-faced card, a token and a promo commander for the box. */
    public static String goblins(String name) {
        return """
                {"meta": {"version": "5.3.0+test"}, "data": {"code": "TST", "name": "%s", "type": "Commander Deck",
                 "releaseDate": "2024-02-09",
                 "commander": [%s], "displayCommander": [%s],
                 "mainBoard": [%s, %s, %s], "sideBoard": [], "tokens": [%s], "planes": [], "schemes": []}}"""
                .formatted(name, card(1, "Krenko, Mob Boss", "normal"), card(1, "Goblin Guide", "normal"),
                        card(1, "Village Messenger // Moonrise Intruder", "transform"),
                        card(1, "Fire // Ice", "split"), card(38, "Mountain", "normal"),
                        card(1, "Goblin", "token"));
    }

    /** A different Commander precon with a sideboard. */
    public static String elves() {
        return """
                {"meta": {}, "data": {"code": "TST", "name": "Elf | Army", "type": "Commander Deck",
                 "releaseDate": "2023-05-05", "commander": [%s],
                 "mainBoard": [%s, %s], "sideBoard": [%s]}}"""
                .formatted(card(1, "Ezuri, Renegade Leader", "normal"), card(1, "Llanowar Elves", "normal"),
                        card(40, "Forest", "normal"), card(1, "Elvish Mystic", "normal"));
    }

    /** DeckList.json: the decks above, a Collector's Edition with the same cards, and a non-Commander deck. */
    public static String deckList() {
        return """
                {"meta": {"version": "5.3.0+test", "date": "2026-11-27"}, "data": [
                  {"fileName": "Goblins_TST", "name": "Goblin Gang", "code": "TST", "releaseDate": "2024-02-09",
                   "type": "Commander Deck", "source": "https://magic.wizards.com/goblins"},
                  {"fileName": "GoblinsCollector_TST", "name": "Goblin Gang Collector's Edition", "code": "TST",
                   "releaseDate": "2024-02-09", "type": "Commander Deck"},
                  {"fileName": "Elves_TST", "name": "Elf | Army", "code": "TST", "releaseDate": "2023-05-05",
                   "type": "Commander Deck"},
                  {"fileName": "Intro_TST", "name": "Intro", "code": "TST", "releaseDate": "2023-01-01",
                   "type": "Intro Pack"}]}""";
    }

    /** Path under the API root to answer. */
    public static Map<String, String> files() {
        return Map.of(
                "DeckList.json", deckList(),
                "decks/Goblins_TST.json", goblins("Goblin Gang"),
                "decks/GoblinsCollector_TST.json", goblins("Goblin Gang Collector's Edition"),
                "decks/Elves_TST.json", elves(),
                "decks/Intro_TST.json", elves());
    }
}
