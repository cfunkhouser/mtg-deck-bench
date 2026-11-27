package mtgbench.run;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import forge.ai.AiProfileUtil;
import mtgbench.config.BenchConfig;
import mtgbench.deck.DeckException;
import mtgbench.deck.DeckLoader;
import mtgbench.deck.LoadedDeck;
import mtgbench.engine.ForgeBootstrap;
import mtgbench.report.Reports;
import mtgbench.stats.GameRecord;
import mtgbench.stats.Summary;

/** Steps shared by run, resume and replay. */
public final class RunSupport {
    private RunSupport() {}

    public static String newRunId(String prefix) {
        return prefix + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(ZonedDateTime.now(ZoneOffset.UTC))
                + "-" + HexFormat.of().formatHex(SecureRandom.getSeed(2));
    }

    /** Starts Forge and checks the AI profile. @return false (after explaining) if the profile is unknown */
    public static boolean startForge(Path res, Path home, String aiProfile, PrintStream console) {
        console.println("Loading Forge card data...");
        ForgeBootstrap.init(res, home);
        if (aiProfile != null && !AiProfileUtil.getProfilesDisplayList().contains(aiProfile)) {
            console.println("Unknown AI profile '" + aiProfile + "'. Available: "
                    + String.join(", ", AiProfileUtil.getProfilesDisplayList()));
            return false;
        }
        return true;
    }

    /** Loads the config's decks, printing warnings and problems. @return null if any deck has problems */
    public static List<LoadedDeck> loadDecks(BenchConfig config, PrintStream console) {
        List<Path> paths = new ArrayList<>();
        paths.add(config.target().path());
        config.opponents().forEach(o -> paths.add(o.path()));
        List<LoadedDeck> decks = load(config, paths, console);
        if (decks == null) {
            return null;
        }
        decks = nameMirrors(decks);
        Map<String, Long> counts = decks.stream()
                .collect(Collectors.groupingBy(LoadedDeck::name, TreeMap::new, Collectors.counting()));
        List<String> clashes = counts.entrySet().stream().filter(e -> e.getValue() > 1).map(Map.Entry::getKey).toList();
        if (!clashes.isEmpty()) {
            List<Path> files = decks.stream().filter(d -> clashes.contains(d.name())).map(LoadedDeck::source).toList();
            console.println("Different decks have the same name (" + String.join(", ", clashes) + "): " + files
                    + ". Rename a file so results can tell them apart.");
            return null;
        }
        return decks;
    }

    /**
     * Gives an opponent that is the target deck itself (same name, same bytes) its own name, "NAME (mirror)",
     * so the target can play its mirror match and results can tell the two seats apart. A second copy is
     * "NAME (mirror 2)", and so on. Runs and resumes apply the same rule, so names stay stable.
     */
    static List<LoadedDeck> nameMirrors(List<LoadedDeck> decks) {
        LoadedDeck target = decks.get(0);
        List<LoadedDeck> named = new ArrayList<>(decks);
        int mirrors = 0;
        for (int i = 1; i < named.size(); i++) {
            LoadedDeck d = named.get(i);
            if (d.name().equals(target.name()) && d.sha256().equals(target.sha256())) {
                mirrors++;
                String name = target.name() + (mirrors == 1 ? " (mirror)" : " (mirror " + mirrors + ")");
                named.set(i, new LoadedDeck(name, d.source(), d.sha256(), d.deck(), d.warnings()));
            }
        }
        return named;
    }

    /**
     * Loads a recorded run's decks again and checks they are byte-for-byte the files the run used.
     *
     * @return null (after explaining) if a deck is missing, has problems or has changed
     */
    public static List<LoadedDeck> reloadDecks(RunManifest manifest, PrintStream console) {
        List<Path> paths = manifest.decks().stream().map(RunManifest.DeckInfo::path).toList();
        List<LoadedDeck> decks = load(manifest.config(), paths, console);
        if (decks == null) {
            return null;
        }
        decks = nameMirrors(decks);
        boolean changed = false;
        for (int i = 0; i < decks.size(); i++) {
            if (!decks.get(i).sha256().equals(manifest.decks().get(i).sha256())) {
                console.println("Deck file changed since the run: " + paths.get(i));
                changed = true;
            }
        }
        if (changed) {
            console.println("Results would mix two versions of a deck. Restore the files or start a new run.");
            return null;
        }
        return decks;
    }

    private static List<LoadedDeck> load(BenchConfig config, List<Path> paths, PrintStream console) {
        DeckLoader loader = new DeckLoader(config.format(), config.checkLegality());
        List<LoadedDeck> decks = new ArrayList<>();
        boolean failed = false;
        Set<Path> warned = new HashSet<>();
        for (Path p : paths) {
            try {
                LoadedDeck d = loader.load(p);
                decks.add(d);
                // A deck listed twice (the target as its own mirror) is warned about once.
                for (String w : warned.add(p.toAbsolutePath().normalize()) ? d.warnings() : List.<String>of()) {
                    console.println("Warning: " + d.name() + ": " + w);
                }
            } catch (DeckException e) {
                failed = true;
                console.println("Deck problem in " + e.getMessage());
            }
        }
        return failed ? null : decks;
    }

    /** Reads games.jsonl of a run directory (empty if there is none yet). */
    public static List<GameRecord> readRecorded(Path runDir, PrintStream console) throws IOException {
        Path file = runDir.resolve("games.jsonl");
        if (!Files.exists(file)) {
            return List.of();
        }
        Reports.GamesRead read = Reports.readGames(file);
        if (read.truncatedLines() > 0) {
            console.println("Ignoring an incomplete last line in " + file + " (the run was interrupted mid-write).");
        }
        return read.games();
    }

    /** Prints the summary and returns the run's exit code. */
    public static int finish(Summary summary, BenchConfig config, Path runDir, PrintStream console) {
        console.println();
        console.print(Reports.table(summary, false));
        console.println();
        console.println("Results: " + runDir);
        if (summary.errorRate() > config.maxErrorRate()) {
            console.printf("%.1f%% of games failed (limit %.1f%%); see %s%n", 100 * summary.errorRate(),
                    100 * config.maxErrorRate(), runDir.resolve("logs").resolve("forge.log"));
            return BenchRun.EXIT_TOO_MANY_ERRORS;
        }
        return BenchRun.EXIT_OK;
    }

    public static String reproducibilityNote(BenchConfig config) {
        return config.threads() == 1
                ? " (one game at a time: every game is reproducible)"
                : " (" + config.threads() + " threads: totals are valid, single games are not reproducible)";
    }
}
