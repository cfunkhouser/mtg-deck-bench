package mtgbench.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import mtgbench.config.Format;
import mtgbench.deck.DeckException;
import mtgbench.deck.DeckLoader;
import mtgbench.deck.LoadedDeck;
import mtgbench.engine.BuildProps;
import mtgbench.engine.ForgeBootstrap;
import mtgbench.fetch.FetchException;
import mtgbench.fetch.FetchedDeck;
import mtgbench.fetch.Mtgjson;
import mtgbench.run.ForgeOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

@Command(name = "precons", mixinStandardHelpOptions = true,
        description = {
            "Download every deck of one product type from MTGJSON (by default all Commander precons) into a"
                + " directory of plain-text deck lists, check each like 'validate' does, and write INDEX.md.",
            "",
            "The directory is regenerated: when it has an INDEX.md from an earlier run, lists MTGJSON no"
                + " longer has are removed. Decks with exactly the same"
                + " cards as an earlier one, such as Collector's Editions, are not written again."})
public final class PreconsCommand implements Callable<Integer> {
    static final String INDEX = "INDEX.md";

    @Spec
    CommandSpec spec;

    @Mixin
    BenchCli.ForgeOptions forge = new BenchCli.ForgeOptions();

    @Option(names = {"-o", "--out"}, paramLabel = "DIR", description = "Directory to write. Default: ${DEFAULT-VALUE}.")
    Path out = Path.of("precons", "commander");

    @Option(names = "--type", paramLabel = "TYPE",
            description = "MTGJSON deck type to download, as in DeckList.json. Default: '${DEFAULT-VALUE}'.")
    String type = "Commander Deck";

    @Option(names = "--force",
            description = "Write into DIR even though it has files and no " + INDEX + ". Its other files are kept.")
    boolean force;

    @Option(names = "--no-validate", description = "Only download and write; don't check the decks against Forge.")
    boolean noValidate;

    /** Where MTGJSON's API lives; tests point it at a local server. */
    @Option(names = "--mtgjson-api", hidden = true)
    URI api = Mtgjson.DEFAULT_API;

    /** Pause between deck downloads, to go easy on MTGJSON. */
    @Option(names = "--delay-ms", hidden = true)
    long delayMs = 100;

    /** One deck written, and how it fared. */
    private record Written(Mtgjson.Entry entry, FetchedDeck deck, Path file, List<String> duplicates) {}

    @Override
    public Integer call() throws Exception {
        boolean generated = Files.exists(out.resolve(INDEX));
        if (Files.isDirectory(out) && !generated && !force && !isEmpty(out)) {
            throw new ParameterException(spec.commandLine(), out + " has files but no " + INDEX
                    + ", so it wasn't made by this command; pass --force to write into it anyway.");
        }
        Path res = noValidate ? null : forge.resolveRes();
        Path home = noValidate ? null : forge.resolveHome();

        PrintStream console = System.out;
        Mtgjson mtgjson = new Mtgjson(api, FetchCommand.userAgent());
        Mtgjson.Index index;
        try {
            index = mtgjson.index();
        } catch (FetchException e) {
            throw new BenchCli.SetupException(e.getMessage());
        }
        List<Mtgjson.Entry> entries = index.decks().stream()
                .filter(e -> e.type().equalsIgnoreCase(type))
                .sorted(Comparator.comparing(Mtgjson.Entry::releaseDate).thenComparing(Mtgjson.Entry::code)
                        .thenComparing(Mtgjson.Entry::name))
                .toList();
        if (entries.isEmpty()) {
            throw new ParameterException(spec.commandLine(), "MTGJSON has no decks of type '" + type + "'.");
        }
        console.printf("MTGJSON %s lists %d decks of type '%s'.%n", index.version(), entries.size(), type);

        List<Written> written = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            Mtgjson.Entry e = entries.get(i);
            FetchedDeck deck;
            try {
                if (i > 0 && delayMs > 0) {
                    Thread.sleep(delayMs);
                }
                deck = FetchedDeck.fromMtgjson(mtgjson.deck(e.fileName()));
            } catch (FetchException ex) {
                failed.add(e.name() + ": " + ex.getMessage());
                console.printf("  [%d/%d] FAILED %s: %s%n", i + 1, entries.size(), e.name(), ex.getMessage());
                continue;
            }
            Written same = written.stream().filter(w -> w.deck().sameCards(deck)).findFirst().orElse(null);
            if (same != null) {
                same.duplicates().add(e.name() + " (" + e.code() + ", " + year(e.releaseDate()) + ")");
                console.printf("  [%d/%d] %s: same cards as %s, not written again%n", i + 1, entries.size(),
                        e.name(), same.entry().name());
                continue;
            }
            Path file = out.resolve(fileName(e, names));
            written.add(new Written(e, deck, file, new ArrayList<>()));
            console.printf("  [%d/%d] %s%n", i + 1, entries.size(), file.getFileName());
        }

        Files.createDirectories(out);
        Set<Path> keep = new HashSet<>();
        for (Written w : written) {
            List<String> comments = new ArrayList<>();
            comments.add(w.entry().code() + ", released " + w.entry().releaseDate());
            comments.add("From " + Mtgjson.deckUrl(w.entry().fileName()));
            if (!w.entry().source().isEmpty()) {
                comments.add("Source: " + w.entry().source());
            }
            Files.writeString(w.file(), w.deck().toText(comments), StandardCharsets.UTF_8);
            keep.add(w.file().getFileName());
        }
        int removed = 0;
        // Only in a directory this command made: --force into another one never deletes its files.
        if (failed.isEmpty() && generated) {
            try (Stream<Path> files = Files.list(out)) {
                for (Path p : files.filter(p -> p.getFileName().toString().endsWith(".txt")
                        && !keep.contains(p.getFileName())).toList()) {
                    Files.delete(p);
                    removed++;
                }
            }
        }

        Map<Path, String> checks = new LinkedHashMap<>();
        if (!noValidate) {
            console.println("Checking the decks against Forge...");
            console = ForgeOutput.redirectTo(home.resolve("logs").resolve("precons.log"));
            ForgeBootstrap.init(res, home);
            DeckLoader commander = new DeckLoader(Format.COMMANDER, true);
            DeckLoader constructed = new DeckLoader(Format.CONSTRUCTED, true);
            for (Written w : written) {
                DeckLoader loader = w.deck().format() == Format.COMMANDER ? commander : constructed;
                checks.put(w.file(), check(loader, w.file()));
            }
        }
        Files.writeString(out.resolve(INDEX), index(index, written, checks), StandardCharsets.UTF_8);

        long bad = checks.values().stream().filter(c -> !c.equals("OK")).count();
        console.printf("Wrote %d decks to %s, and %s%s.%n", written.size(), out, INDEX,
                removed > 0 ? "; removed " + removed + " lists MTGJSON no longer has" : "");
        if (!noValidate) {
            console.printf("%d of %d pass validation%s%n", written.size() - bad, written.size(),
                    bad > 0 ? "; the others are listed in " + INDEX + " with their problems:" : ".");
            for (Map.Entry<Path, String> c : checks.entrySet()) {
                if (!c.getValue().equals("OK")) {
                    console.println("  " + c.getKey().getFileName() + ": " + c.getValue());
                }
            }
        }
        if (!failed.isEmpty()) {
            console.println(failed.size() + " decks could not be downloaded, so old lists were kept:");
            for (String f : failed) {
                console.println("  " + f);
            }
            return BenchCli.EXIT_USAGE;
        }
        return 0;
    }

    private static String check(DeckLoader loader, Path file) {
        try {
            LoadedDeck d = loader.load(file);
            return d.deck().getMain().isEmpty() ? "empty" : "OK";
        } catch (DeckException e) {
            return String.join("; ", e.problems());
        }
    }

    /** "40k-forces-of-the-imperium.txt", unique within this run. */
    static String fileName(Mtgjson.Entry e, Set<String> taken) {
        String slug = e.name().toLowerCase(Locale.ROOT)
                .replace("'", "")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        String base = e.code().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "") + "-" + slug;
        String name = base + ".txt";
        for (int n = 2; !taken.add(name); n++) {
            name = base + "-" + n + ".txt";
        }
        return name;
    }

    private String index(Mtgjson.Index index, List<Written> written, Map<Path, String> checks) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(type.equalsIgnoreCase("Commander Deck") ? "Commander precons" : type).append("\n\n");
        md.append("Generated by `mtgbench precons` from [MTGJSON](https://mtgjson.com/) (MIT-licensed) data version ")
                .append(index.version()).append(". MTGJSON's lists come from Wizards of the Coast's decklist articles;")
                .append(" each file names its source. Don't edit these files by hand: rerun the command instead.\n\n");
        if (!checks.isEmpty()) {
            long ok = checks.values().stream().filter(c -> c.equals("OK")).count();
            md.append("Checked with Forge ").append(BuildProps.get("forge.version")).append(": ").append(ok)
                    .append(" of ").append(written.size()).append(" pass. Failures are usually cards banned since")
                    .append(" the deck came out; such decks still play with `--no-legality-check`.\n\n");
        }
        md.append("| Released | Set | Deck | Commander | File |").append(checks.isEmpty() ? "" : " Check |").append('\n');
        md.append("|---|---|---|---|---|").append(checks.isEmpty() ? "" : "---|").append('\n');
        for (Written w : written) {
            String deck = w.entry().name();
            if (!w.duplicates().isEmpty()) {
                deck += " (also sold as: " + String.join(", ", w.duplicates()) + ")";
            }
            String file = w.file().getFileName().toString();
            md.append("| ").append(w.entry().releaseDate())
                    .append(" | ").append(w.entry().code())
                    .append(" | ").append(cell(deck))
                    .append(" | ").append(cell(String.join(" + ", w.deck().commander().keySet())))
                    .append(" | [").append(file).append("](").append(file).append(")");
            if (!checks.isEmpty()) {
                md.append(" | ").append(cell(checks.get(w.file())));
            }
            md.append(" |\n");
        }
        return md.toString();
    }

    private static String year(String date) {
        return date.length() >= 4 ? date.substring(0, 4) : date;
    }

    private static String cell(String s) {
        return s.replace("|", "\\|").replaceAll("\\s+", " ");
    }

    private static boolean isEmpty(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.findAny().isEmpty();
        }
    }
}
