package mtgbench.deck;

import java.nio.file.Path;
import java.util.List;

/** A deck file that cannot be used, with every problem found (not just the first). */
public class DeckException extends Exception {
    private final Path source;
    private final List<String> problems;

    public DeckException(Path source, List<String> problems) {
        super(source + ":\n  - " + String.join("\n  - ", problems));
        this.source = source;
        this.problems = List.copyOf(problems);
    }

    public Path source() {
        return source;
    }

    public List<String> problems() {
        return problems;
    }
}
