package mtgbench.deck;

import java.nio.file.Path;
import java.util.List;

import forge.deck.Deck;

/**
 * A deck that loaded and passed validation.
 *
 * @param name     display name (file name without extension unless the file names the deck)
 * @param source   file it was read from
 * @param sha256   hash of the file contents, recorded with results so runs can be traced to inputs
 * @param deck     Forge's deck object; shared read-only by all games
 * @param warnings non-fatal issues, e.g. cards the Forge AI is known to play badly
 */
public record LoadedDeck(String name, Path source, String sha256, Deck deck, List<String> warnings) {
}
