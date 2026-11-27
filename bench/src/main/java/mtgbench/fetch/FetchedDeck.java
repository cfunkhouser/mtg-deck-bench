package mtgbench.fetch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;

import mtgbench.config.Format;

/**
 * A deck downloaded from a deck site, sorted into the sections of a plain-text deck list.
 *
 * <p>Lines carry the card name only, so Forge picks the printing. Copies of the same card from
 * different printings are added together.
 *
 * <h2>Archidekt</h2>
 *
 * <p>From {@code GET /api/decks/<id>/}. Each card on Archidekt has a list of categories, and each
 * category says whether it counts as part of the deck ({@code includedInDeck}) and whether it is the
 * commander category ({@code isPremier}). Cards are sorted as follows:
 *
 * <ol>
 *   <li>In a premier category: Commander.
 *   <li>In any category not counted in the deck, such as Maybeboard: Sideboard, so the file still has
 *       them, or left out with {@code keepOutOfDeck} false.
 *   <li>Marked as the companion, or in a category named Sideboard: Sideboard. Archidekt counts its
 *       Sideboard category as part of the deck, so it is recognized by name.
 *   <li>Everything else, including cards with no category: the main deck.
 * </ol>
 *
 * <h2>MTGJSON</h2>
 *
 * <p>From a deck file's {@code data}: {@code commander}, {@code mainBoard} and {@code sideBoard} map
 * directly. Tokens, planes, schemes and {@code displayCommander} (a promo copy of the commander shown on
 * the box) are not part of the deck.
 *
 * @param name the deck's name on the site
 * @param format the closest format
 * @param commander card name to count, sorted by name
 * @param main card name to count, sorted by name
 * @param sideboard card name to count, sorted by name
 * @param outOfDeck names of the Archidekt categories not counted in the deck that had cards
 * @param outOfDeckCards how many cards those are
 * @param outOfDeckKept whether those cards are in the sideboard (otherwise they were left out)
 * @param skippedCustomCards how many custom (user-made) cards were left out
 */
public record FetchedDeck(String name, Format format, Map<String, Integer> commander, Map<String, Integer> main,
                            Map<String, Integer> sideboard, List<String> outOfDeck, int outOfDeckCards,
                            boolean outOfDeckKept, int skippedCustomCards) {

    /** Archidekt's deckFormat number for Commander / EDH. */
    static final int ARCHIDEKT_COMMANDER = 3;

    /** Layouts whose Forge name is both halves ("Fire // Ice"); other two-faced cards use the front face. */
    private static final List<String> BOTH_HALVES_LAYOUTS = List.of("split", "aftermath");

    public static FetchedDeck fromArchidekt(JsonNode deck, boolean keepOutOfDeck) {
        Map<String, JsonNode> categories = new HashMap<>();
        for (JsonNode c : deck.path("categories")) {
            categories.put(c.path("name").asText(), c);
        }
        Map<String, Integer> commander = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, Integer> main = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, Integer> sideboard = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        TreeSet<String> outOfDeck = new TreeSet<>();
        int outOfDeckCards = 0;

        for (JsonNode entry : deck.path("cards")) {
            if (!entry.path("deletedAt").isMissingNode() && !entry.path("deletedAt").isNull()) {
                continue;
            }
            int quantity = entry.path("quantity").asInt(0);
            if (quantity <= 0) {
                continue;
            }
            String cardName = forgeName(entry.path("card").path("oracleCard"));
            if (cardName.isEmpty()) {
                continue;
            }
            boolean premier = false;
            boolean sideboardCategory = false;
            List<String> excludedBy = new ArrayList<>();
            for (JsonNode cat : entry.path("categories")) {
                String catName = cat.asText();
                JsonNode info = categories.get(catName);
                if (info != null && info.path("isPremier").asBoolean(false)) {
                    premier = true;
                }
                if (info != null && !info.path("includedInDeck").asBoolean(true)) {
                    excludedBy.add(catName);
                }
                if (catName.equalsIgnoreCase("Sideboard")) {
                    sideboardCategory = true;
                }
            }
            boolean companion = entry.path("companion").asBoolean(false);

            Map<String, Integer> target;
            if (premier) {
                target = commander;
            } else if (!excludedBy.isEmpty()) {
                outOfDeck.addAll(excludedBy);
                outOfDeckCards += quantity;
                if (!keepOutOfDeck) {
                    continue;
                }
                target = sideboard;
            } else if (companion || sideboardCategory) {
                target = sideboard;
            } else {
                target = main;
            }
            target.merge(cardName, quantity, Integer::sum);
        }

        return new FetchedDeck(deck.path("name").asText("").strip(), archidektFormat(deck), commander, main, sideboard,
                List.copyOf(outOfDeck), outOfDeckCards, keepOutOfDeck, deck.path("customCards").size());
    }

    /** The closest format to the Archidekt deck's: Commander for Commander decks, otherwise constructed. */
    public static Format archidektFormat(JsonNode deck) {
        return deck.path("deckFormat").asInt(-1) == ARCHIDEKT_COMMANDER ? Format.COMMANDER : Format.CONSTRUCTED;
    }

    /**
     * @param data the {@code data} object of an MTGJSON deck file ({@code api/v5/decks/<fileName>.json})
     */
    public static FetchedDeck fromMtgjson(JsonNode data) {
        Map<String, Integer> commander = mtgjsonSection(data.path("commander"));
        Map<String, Integer> main = mtgjsonSection(data.path("mainBoard"));
        Map<String, Integer> sideboard = mtgjsonSection(data.path("sideBoard"));
        Format format = !commander.isEmpty() || data.path("type").asText("").toLowerCase(Locale.ROOT).contains("commander")
                ? Format.COMMANDER : Format.CONSTRUCTED;
        return new FetchedDeck(data.path("name").asText("").strip(), format, commander, main, sideboard, List.of(), 0,
                false, 0);
    }

    private static Map<String, Integer> mtgjsonSection(JsonNode cards) {
        Map<String, Integer> section = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (JsonNode card : cards) {
            int count = card.path("count").asInt(0);
            String cardName = forgeName(card);
            if (count > 0 && !cardName.isEmpty()) {
                section.merge(cardName, count, Integer::sum);
            }
        }
        return section;
    }

    /** True if both decks have the same cards in the same sections (names and formats aside). */
    public boolean sameCards(FetchedDeck other) {
        return commander.equals(other.commander) && main.equals(other.main) && sideboard.equals(other.sideboard);
    }

    /** The name Forge knows a card by: the front face of a two-faced card, both halves of a split card. */
    static String forgeName(JsonNode oracleCard) {
        String name = oracleCard.path("name").asText("").strip();
        String layout = oracleCard.path("layout").asText("").toLowerCase(Locale.ROOT);
        int split = name.indexOf(" // ");
        if (split > 0 && !BOTH_HALVES_LAYOUTS.contains(layout)) {
            return name.substring(0, split).strip();
        }
        return name;
    }

    public int count(Map<String, Integer> section) {
        return section.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** The deck as a plain-text list that {@code mtgbench.deck.DeckLoader} reads. */
    public String toText(String sourceUrl) {
        return toText(List.of("From " + sourceUrl));
    }

    /** @param comments lines for the header, after the deck name, each written as a "# " comment */
    public String toText(List<String> comments) {
        StringBuilder out = new StringBuilder();
        if (!name.isEmpty()) {
            out.append("# ").append(oneLine(name)).append('\n');
        }
        for (String c : comments) {
            out.append("# ").append(oneLine(c)).append('\n');
        }
        if (outOfDeckCards > 0 && outOfDeckKept) {
            out.append("# The sideboard includes ").append(outOfDeckCards)
                    .append(outOfDeckCards == 1 ? " card" : " cards")
                    .append(" from Archidekt categories not counted in the deck: ")
                    .append(String.join(", ", outOfDeck)).append('\n');
        }
        section(out, "Commander", commander);
        section(out, "Deck", main);
        section(out, "Sideboard", sideboard);
        return out.toString();
    }

    private static void section(StringBuilder out, String header, Map<String, Integer> cards) {
        if (cards.isEmpty()) {
            return;
        }
        out.append('\n').append(header).append('\n');
        cards.forEach((card, n) -> out.append(n).append(' ').append(card).append('\n'));
    }

    private static String oneLine(String s) {
        return s.replaceAll("\\s+", " ");
    }
}
