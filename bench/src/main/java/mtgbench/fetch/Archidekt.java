package mtgbench.fetch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Downloads public decks from Archidekt's JSON API. */
public final class Archidekt {
    public static final URI DEFAULT_API = URI.create("https://archidekt.com/api/");

    /** "archidekt.com/decks/123456/some-slug", with or without scheme, "www." or a query. */
    private static final Pattern DECK_URL = Pattern.compile(
            "^(?:https?://)?(?:www\\.)?archidekt\\.com/(?:api/)?decks/(\\d+)(?:[/?#].*)?$",
            Pattern.CASE_INSENSITIVE);

    private final URI api;
    private final String userAgent;
    private final HttpClient http;

    public Archidekt(URI api, String userAgent) {
        this.api = api.toString().endsWith("/") ? api : URI.create(api + "/");
        this.userAgent = userAgent;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
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
        URI uri = api.resolve("decks/" + id + "/");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new FetchException("Cannot reach Archidekt (" + uri + "): " + describe(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FetchException("Interrupted while downloading " + uri);
        }
        int status = response.statusCode();
        if (status == 404 || status == 403 || status == 401) {
            throw new FetchException("Archidekt has no public deck " + id + " (HTTP " + status
                    + "): check the URL, and that the deck isn't private.");
        }
        if (status != 200) {
            throw new FetchException("Archidekt returned HTTP " + status + " for " + uri + "; try again later.");
        }
        try {
            JsonNode deck = new ObjectMapper().readTree(response.body());
            if (deck == null || !deck.path("cards").isArray()) {
                throw new FetchException("Archidekt's answer for deck " + id + " has no card list; its API may"
                        + " have changed.");
            }
            return deck;
        } catch (IOException e) {
            throw new FetchException("Archidekt's answer for deck " + id + " isn't valid JSON: " + describe(e));
        }
    }

    private static String describe(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** A deck could not be downloaded; the message is meant for the user. */
    public static final class FetchException extends Exception {
        public FetchException(String message) {
            super(message);
        }
    }
}
