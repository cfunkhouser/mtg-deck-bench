package mtgbench.fetch;

/** Archidekt API answers trimmed to the fields mtgbench reads. */
public final class ArchidektTestDecks {
    private ArchidektTestDecks() {}

    private static String card(int qty, String name, String layout, String categories, String extra) {
        return """
                {"quantity": %d, "categories": %s, "deletedAt": null, "companion": %s,
                 "card": {"edition": {"editioncode": "xyz"}, "oracleCard": {"name": "%s", "layout": "%s"}}}"""
                .formatted(qty, categories, extra.contains("companion") ? "true" : "false", name, layout);
    }

    /** A 60-card constructed deck with a sideboard, a maybeboard and a few awkward cards. */
    public static String constructed() {
        return """
                {"id": 42, "name": "Test  Burn", "deckFormat": 1, "customCards": [],
                 "categories": [
                   {"name": "Burn", "includedInDeck": true, "isPremier": false},
                   {"name": "Land", "includedInDeck": true, "isPremier": false},
                   {"name": "Sideboard", "includedInDeck": true, "isPremier": false},
                   {"name": "Maybeboard", "includedInDeck": false, "isPremier": false}],
                 "cards": [%s]}"""
                .formatted(String.join(",",
                        card(2, "Lightning Bolt", "normal", "[\"Burn\"]", ""),
                        card(2, "Lightning Bolt", "normal", "[\"Burn\"]", ""), // another printing
                        card(4, "Fire // Ice", "split", "[\"Burn\"]", ""),
                        card(2, "Bonecrusher Giant // Stomp", "adventure", "[]", ""),
                        card(50, "Mountain", "normal", "[\"Land\"]", ""),
                        card(3, "Smash to Smithereens", "normal", "[\"Sideboard\"]", ""),
                        card(1, "Lurrus of the Dream-Den", "normal", "[]", "companion"),
                        card(2, "Shock", "normal", "[\"Maybeboard\", \"Burn\"]", ""),
                        card(0, "Skullcrack", "normal", "[\"Burn\"]", ""),
                        """
                        {"quantity": 1, "categories": ["Burn"], "deletedAt": "2024-01-01T00:00:00Z",
                         "card": {"oracleCard": {"name": "Lava Spike", "layout": "normal"}}}"""));
    }

    /** A short Commander deck: the commander is in the premier category, plus one two-faced card. */
    public static String commander() {
        return """
                {"id": 7, "name": "Goblins", "deckFormat": 3, "customCards": [{"name": "Homebrew"}],
                 "categories": [
                   {"name": "Commander", "includedInDeck": true, "isPremier": true},
                   {"name": "Maybeboard", "includedInDeck": false, "isPremier": false}],
                 "cards": [%s]}"""
                .formatted(String.join(",",
                        card(1, "Krenko, Mob Boss", "normal", "[\"Commander\"]", ""),
                        card(1, "Village Messenger // Moonrise Intruder", "transform", "[]", ""),
                        card(40, "Mountain", "normal", "[]", ""),
                        card(1, "Goblin Guide", "normal", "[\"Maybeboard\"]", "")));
    }
}
