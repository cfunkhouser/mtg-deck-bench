package mtgbench.fetch;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Downloads product deck lists (precons and the like) from MTGJSON, whose data is MIT-licensed. Its
 * deck lists come from Wizards of the Coast's decklist articles.
 */
public final class Mtgjson {
    public static final URI DEFAULT_API = URI.create("https://mtgjson.com/api/v5/");

    /** "mtgjson:<fileName>", or a deck file URL: "https://mtgjson.com/api/v5/decks/<fileName>.json". */
    private static final Pattern DECK_REF = Pattern.compile(
            "^(?:mtgjson:|(?:https?://)?(?:www\\.)?mtgjson\\.com/api/v5/decks/)([A-Za-z0-9_\\-]{1,200})(?:\\.json)?$",
            Pattern.CASE_INSENSITIVE);

    /** One deck in MTGJSON's DeckList.json. */
    public record Entry(String fileName, String name, String code, String releaseDate, String type, String source) {}

    private final URI api;
    private final JsonHttp http;

    public Mtgjson(URI api, String userAgent) {
        this.api = api.toString().endsWith("/") ? api : URI.create(api + "/");
        this.http = new JsonHttp("MTGJSON", userAgent);
    }

    /** The deck file name in an MTGJSON reference, or empty if it isn't one. */
    public static Optional<String> deckFileName(String ref) {
        Matcher m = DECK_REF.matcher(ref.strip());
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /** The public URL of a deck file, as written into the deck list. */
    public static String deckUrl(String fileName) {
        return DEFAULT_API.resolve("decks/" + fileName + ".json").toString();
    }

    /** Every deck MTGJSON lists, from DeckList.json. */
    public Index index() throws FetchException {
        JsonNode list = http.get(api.resolve("DeckList.json"),
                status -> "MTGJSON has no DeckList.json (HTTP " + status + "); its API may have changed.");
        List<Entry> entries = new ArrayList<>();
        for (JsonNode e : list.path("data")) {
            entries.add(new Entry(e.path("fileName").asText(), e.path("name").asText().strip(),
                    e.path("code").asText(), e.path("releaseDate").asText(""), e.path("type").asText(),
                    e.path("source").asText("")));
        }
        if (entries.isEmpty()) {
            throw new FetchException("MTGJSON's DeckList.json lists no decks; its API may have changed.");
        }
        return new Index(list.path("meta").path("version").asText(""), list.path("meta").path("date").asText(""),
                List.copyOf(entries));
    }

    /** @param version and date: MTGJSON's build of the data */
    public record Index(String version, String date, List<Entry> decks) {}

    /** @return the deck file's {@code data} object */
    public JsonNode deck(String fileName) throws FetchException {
        JsonNode file = http.get(api.resolve("decks/" + fileName + ".json"), status -> "MTGJSON has no deck "
                + fileName + " (HTTP " + status + "): see https://mtgjson.com/api/v5/DeckList.json for the names.");
        JsonNode data = file.path("data");
        if (!data.path("mainBoard").isArray()) {
            throw new FetchException("MTGJSON's deck " + fileName + " has no mainBoard; its format may have changed.");
        }
        return data;
    }
}
