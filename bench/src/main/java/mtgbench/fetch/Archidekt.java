package mtgbench.fetch;

import java.net.URI;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/** Downloads public decks from Archidekt's JSON API. */
public final class Archidekt {
    public static final URI DEFAULT_API = URI.create("https://archidekt.com/api/");

    /** "archidekt.com/decks/123456/some-slug", with or without scheme, "www." or a query. */
    private static final Pattern DECK_URL = Pattern.compile(
            "^(?:https?://)?(?:www\\.)?archidekt\\.com/(?:api/)?decks/(\\d+)(?:[/?#].*)?$",
            Pattern.CASE_INSENSITIVE);

    private final URI api;
    private final JsonHttp http;

    public Archidekt(URI api, String userAgent) {
        this.api = api.toString().endsWith("/") ? api : URI.create(api + "/");
        this.http = new JsonHttp("Archidekt", userAgent);
    }

    /** The deck id in an Archidekt deck URL, or empty if it isn't one. A bare id is accepted too. */
    public static Optional<Long> deckId(String url) {
        String s = url.strip();
        if (s.matches("\\d{1,18}")) {
            return Optional.of(Long.parseLong(s));
        }
        Matcher m = DECK_URL.matcher(s);
        return m.matches() && m.group(1).length() <= 18 ? Optional.of(Long.parseLong(m.group(1))) : Optional.empty();
    }

    /** The page people open for a deck, as written into the deck file. */
    public static String deckPage(long id) {
        return "https://archidekt.com/decks/" + id;
    }

    /** @throws FetchException with a message for the user if the deck can't be downloaded */
    public JsonNode fetch(long id) throws FetchException {
        JsonNode deck = http.get(api.resolve("decks/" + id + "/"), status -> "Archidekt has no public deck " + id
                + " (HTTP " + status + "): check the URL, and that the deck isn't private.");
        if (!deck.path("cards").isArray()) {
            throw new FetchException("Archidekt's answer for deck " + id + " has no card list; its API may"
                    + " have changed.");
        }
        return deck;
    }
}
