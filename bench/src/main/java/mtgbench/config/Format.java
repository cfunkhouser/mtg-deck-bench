package mtgbench.config;

import forge.deck.DeckFormat;
import forge.game.GameType;

/**
 * Game formats the benchmark can run.
 *
 * <p>The Forge types are looked up on demand, not stored in the constants: Forge's GameType can't be
 * class-loaded before Forge is initialized (it localizes its names), and this enum is loaded earlier,
 * while command-line options are parsed.
 */
public enum Format {
    CONSTRUCTED,
    COMMANDER;

    public GameType gameType() {
        return switch (this) {
            case CONSTRUCTED -> GameType.Constructed;
            case COMMANDER -> GameType.Commander;
        };
    }

    public DeckFormat deckFormat() {
        return switch (this) {
            case CONSTRUCTED -> DeckFormat.Constructed;
            case COMMANDER -> DeckFormat.Commander;
        };
    }
}
