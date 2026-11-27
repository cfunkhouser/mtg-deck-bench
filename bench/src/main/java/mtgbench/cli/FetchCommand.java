package mtgbench.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import mtgbench.config.Format;
import mtgbench.deck.DeckLoader;
import mtgbench.engine.BuildProps;
import mtgbench.engine.ForgeBootstrap;
import mtgbench.fetch.Archidekt;
import mtgbench.fetch.ArchidektDeck;
import mtgbench.run.ForgeOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "fetch", mixinStandardHelpOptions = true,
        description = {
            "Download a public deck from Archidekt and save it as a plain-text deck list, then check it like"
                + " 'validate' does.",
            "",
            "The commander goes under a Commander header. Cards in Archidekt's Sideboard category, the companion,"
                + " and cards in categories not counted in the deck (such as Maybeboard) go in the sideboard."
                + " Lines carry card names only, so Forge picks the printing."})
public final class FetchCommand implements Callable<Integer> {
    @Spec
    CommandSpec spec;

    @Mixin
    BenchCli.ForgeOptions forge = new BenchCli.ForgeOptions();

    @Parameters(index = "0", paramLabel = "URL",
            description = "Archidekt deck URL, such as https://archidekt.com/decks/123456/my-deck (or just the id).")
    String url;

    @Parameters(index = "1", paramLabel = "OUT", description = "Text file to write the deck list to.")
    Path out;

    @Option(names = {"-f", "--format"},
            description = "Format to check the deck against: constructed or commander. Default: commander for"
                    + " Archidekt Commander decks, otherwise constructed.")
    Format format;

    @Option(names = "--force", description = "Overwrite OUT if it exists.")
    boolean force;

    @Option(names = "--no-maybeboard",
            description = "Leave out cards in categories Archidekt doesn't count in the deck (Maybeboard and the"
                    + " like) instead of writing them to the sideboard. Commander legality allows at most 10"
                    + " sideboard cards, and counts them for singleton.")
    boolean noMaybeboard;

    @Option(names = "--no-validate", description = "Only download and write; don't check the deck against Forge.")
    boolean noValidate;

    @Option(names = "--no-legality-check", description = "Only check that cards are known.")
    boolean noLegalityCheck;

    /** Where Archidekt's API lives; tests point it at a local server. */
    @Option(names = "--archidekt-api", hidden = true)
    URI api = Archidekt.DEFAULT_API;

    @Override
    public Integer call() throws Exception {
        long id = Archidekt.deckId(url).orElseThrow(() -> new ParameterException(spec.commandLine(),
                "Not an Archidekt deck URL: " + url + " (expected https://archidekt.com/decks/<id>/...)"));
        if (Files.exists(out) && !force) {
            throw new ParameterException(spec.commandLine(), out + " already exists; pass --force to overwrite it.");
        }
        // Find Forge before downloading, so a missing setup is reported before any work is done.
        Path res = noValidate ? null : forge.resolveRes();
        Path home = noValidate ? null : forge.resolveHome();

        ArchidektDeck deck;
        try {
            Archidekt client = new Archidekt(api, "mtgbench/" + BuildProps.get("mtgbench.version")
                    + " (+https://github.com/cfunkhouser/mtg-deck-bench)");
            deck = ArchidektDeck.parse(client.fetch(id), !noMaybeboard);
        } catch (Archidekt.FetchException e) {
            throw new BenchCli.SetupException(e.getMessage());
        }
        if (deck.main().isEmpty() && deck.commander().isEmpty()) {
            throw new BenchCli.SetupException("Archidekt deck " + id + " has no cards in it.");
        }
        write(deck.toText(Archidekt.deckPage(id)));

        PrintStream console = System.out;
        console.println("Wrote " + out + " (" + describe(deck) + ")");
        if (deck.outOfDeckCards() > 0) {
            console.println("      note: " + (deck.outOfDeckKept() ? "wrote " : "left out ") + deck.outOfDeckCards()
                    + " card(s) from categories not counted in the deck" + (deck.outOfDeckKept() ? " to the sideboard" : "")
                    + ": " + String.join(", ", deck.outOfDeck()));
        }
        if (deck.skippedCustomCards() > 0) {
            console.println("      note: left out " + deck.skippedCustomCards() + " custom card(s), which Forge"
                    + " can't play");
        }
        if (noValidate) {
            return 0;
        }

        Format checkAs = format != null ? format : deck.format();
        console = ForgeOutput.redirectTo(home.resolve("logs").resolve("fetch.log"));
        ForgeBootstrap.init(res, home);
        boolean ok = ValidateCommand.check(new DeckLoader(checkAs, !noLegalityCheck), out, console);
        if (!ok) {
            if (checkAs == Format.COMMANDER && deck.outOfDeckKept() && deck.outOfDeckCards() > 0) {
                console.println("The sideboard holds " + deck.outOfDeckCards() + " card(s) from "
                        + String.join(", ", deck.outOfDeck()) + ", which count against Commander legality;"
                        + " rerun with --no-maybeboard --force to leave them out.");
            } else {
                console.println("The deck was saved; fix it there, or rerun with --no-legality-check if only"
                        + " legality failed.");
            }
        }
        return ok ? 0 : BenchCli.EXIT_DECK;
    }

    private void write(String text) {
        try {
            Path parent = out.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(out, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BenchCli.SetupException("Cannot write " + out + ": " + e.getMessage());
        }
    }

    private static String describe(ArchidektDeck deck) {
        List<String> parts = new ArrayList<>();
        if (!deck.name().isEmpty()) {
            parts.add(deck.name());
        }
        if (!deck.commander().isEmpty()) {
            parts.add("commander: " + String.join(" + ", deck.commander().keySet()));
        }
        parts.add(deck.count(deck.main()) + " main");
        parts.add(deck.count(deck.sideboard()) + " sideboard");
        return String.join(", ", parts);
    }
}
