package mtgbench.cli;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import mtgbench.config.BenchConfig;
import mtgbench.deck.LoadedDeck;
import mtgbench.report.Reports;
import mtgbench.run.BenchRun;
import mtgbench.run.ForgeOutput;
import mtgbench.run.RunManifest;
import mtgbench.run.RunSupport;
import mtgbench.stats.GameRecord;
import mtgbench.stats.Summary;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "resume", mixinStandardHelpOptions = true,
        description = "Finish an interrupted run: play only the games missing from its games.jsonl.")
public final class ResumeCommand implements Callable<Integer> {
    @Mixin
    BenchCli.ForgeOptions forge = new BenchCli.ForgeOptions();

    @Parameters(paramLabel = "RUN_DIR", description = "The run directory to continue.")
    Path runDir;

    @Option(names = {"-t", "--threads"}, description = "Threads for the remaining games (default: as recorded).")
    Integer threads;

    @Override
    public Integer call() throws Exception {
        RunManifest manifest = RunManifest.read(runDir);
        BenchConfig config = threads != null ? manifest.config().withThreads(threads) : manifest.config();
        PrintStream console = ForgeOutput.redirectTo(runDir.resolve("logs").resolve("forge.log"));
        console.println("Resuming run " + manifest.runId() + " (seed " + config.seed() + ")"
                + RunSupport.reproducibilityNote(config));
        if (manifest.config().threads() == 1 && config.threads() != 1) {
            console.println("Note: the run was deterministic; games played now with several threads won't be.");
        }

        List<GameRecord> recorded = RunSupport.readRecorded(runDir, console);
        // Drop a cut-off last line before appending, or the next record would be glued onto it.
        Reports.rewriteGames(runDir.resolve("games.jsonl"), recorded);

        if (!RunSupport.startForge(forge.resolveRes(), forge.resolveHome(), config.aiProfile(), console)) {
            return BenchCli.EXIT_USAGE;
        }
        List<LoadedDeck> decks = RunSupport.reloadDecks(manifest, console);
        if (decks == null) {
            return BenchCli.EXIT_DECK;
        }
        Summary summary = new BenchRun(manifest, config, runDir, decks, console).execute(recorded);
        return RunSupport.finish(summary, config, runDir, console);
    }
}
