package mtgbench.cli;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import mtgbench.report.Reports;
import mtgbench.run.BenchRun;
import mtgbench.run.RunManifest;
import mtgbench.run.RunSupport;
import mtgbench.stats.GameRecord;
import mtgbench.stats.Summary;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "report", mixinStandardHelpOptions = true,
        description = "Rebuild summary.json and summary.md of a run from its games.jsonl (no games are played).")
public final class ReportCommand implements Callable<Integer> {
    @Parameters(paramLabel = "RUN_DIR", description = "A run directory (contains config.resolved.yaml and games.jsonl).")
    Path runDir;

    @Override
    public Integer call() throws Exception {
        PrintStream console = System.out;
        RunManifest manifest = RunManifest.read(runDir);
        List<GameRecord> games = RunSupport.readRecorded(runDir, console);
        if (!BenchRun.remaining(manifest.config(), games).isEmpty()) {
            console.printf("Note: the run isn't finished (%d games recorded); 'mtgbench resume %s' plays the rest.%n",
                    games.size(), runDir);
        }
        Summary summary = BenchRun.writeSummary(manifest, runDir, games);
        console.print(Reports.table(summary, false));
        console.println("Wrote " + runDir.resolve("summary.md") + " and summary.json");
        return 0;
    }
}
