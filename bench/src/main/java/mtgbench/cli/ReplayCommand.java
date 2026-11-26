package mtgbench.cli;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import mtgbench.config.BenchConfig;
import mtgbench.deck.LoadedDeck;
import mtgbench.engine.GameRunner;
import mtgbench.run.ForgeOutput;
import mtgbench.run.RunManifest;
import mtgbench.run.RunSupport;
import mtgbench.schedule.GameSpec;
import mtgbench.schedule.Scheduler;
import mtgbench.stats.GameRecord;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "replay", mixinStandardHelpOptions = true,
        description = {"Play one game of a run again from its seed and show Forge's full game log.",
                "Exact for runs made with --deterministic; other runs give a game with the same setup."})
public final class ReplayCommand implements Callable<Integer> {
    public static final int EXIT_MISMATCH = 4;

    @Mixin
    BenchCli.ForgeOptions forge = new BenchCli.ForgeOptions();

    @Parameters(paramLabel = "RUN_DIR", description = "The run directory.")
    Path runDir;

    @Option(names = {"-g", "--game"}, required = true, description = "Game id (gameId in games.jsonl).")
    int gameId;

    @Option(names = {"-q", "--quiet"}, description = "Don't print the game log, only the comparison.")
    boolean quiet;

    @Override
    public Integer call() throws Exception {
        RunManifest manifest = RunManifest.read(runDir);
        BenchConfig config = manifest.config();
        Path logDir = Files.createDirectories(runDir.resolve("logs").resolve("replay"));
        PrintStream console = ForgeOutput.redirectTo(logDir.resolve("forge.log"));

        List<GameSpec> all = Scheduler.gauntlet(config.opponents().size(), config.games(), config.seed());
        if (gameId < 0 || gameId >= all.size()) {
            console.println("No game " + gameId + " in this run (ids 0.." + (all.size() - 1) + ")");
            return BenchCli.EXIT_USAGE;
        }
        GameSpec spec = all.get(gameId);
        if (!RunSupport.startForge(forge.resolveRes(), forge.resolveHome(), config.aiProfile(), console)) {
            return BenchCli.EXIT_USAGE;
        }
        List<LoadedDeck> decks = RunSupport.reloadDecks(manifest, console);
        if (decks == null) {
            return BenchCli.EXIT_DECK;
        }

        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "watchdog");
            t.setDaemon(true);
            return t;
        });
        GameRecord replayed;
        try {
            GameRunner runner = new GameRunner(manifest.runId(), "gauntlet", config.format(), decks,
                    config.aiProfile(), config.maxRounds(), config.gameTimeoutSec(), watchdog, logDir);
            replayed = runner.play(spec);
        } finally {
            watchdog.shutdownNow();
        }
        Path gameLog = logDir.resolve(String.format("game-%06d.log", gameId));
        if (!quiet && Files.exists(gameLog)) {
            Files.readAllLines(gameLog).forEach(console::println);
            console.println();
        }
        console.printf("Replayed game %d (%s, seed %d): %s in %d turns%n", gameId, replayed.matchupName(),
                spec.seed(), replayed.outcome(), replayed.turns());
        console.println("Game log: " + gameLog);

        Optional<GameRecord> recorded = RunSupport.readRecorded(runDir, console).stream()
                .filter(g -> g.gameId() == gameId).findFirst();
        if (recorded.isEmpty()) {
            console.println("This game isn't recorded in the run, so there is nothing to compare with.");
            return 0;
        }
        GameRecord r = recorded.get();
        if (MergeCommand.sameGame(r, replayed)) {
            console.println("Same result as recorded.");
            return 0;
        }
        console.printf("Different from recorded: %s in %d turns.%n", r.outcome(), r.turns());
        if (config.threads() != 1) {
            console.println("Expected: the run used " + config.threads()
                    + " threads, so its games can't be reproduced exactly. Use --deterministic for exact replays.");
            return 0;
        }
        console.println("The run was deterministic, so this is a reproducibility bug (different Forge build, "
                + "JVM flags or deck files?).");
        return EXIT_MISMATCH;
    }
}
