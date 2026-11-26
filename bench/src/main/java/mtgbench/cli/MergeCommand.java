package mtgbench.cli;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.Callable;

import mtgbench.config.BenchConfig;
import mtgbench.report.Reports;
import mtgbench.run.BenchRun;
import mtgbench.run.RunManifest;
import mtgbench.run.RunSupport;
import mtgbench.stats.GameRecord;
import mtgbench.stats.Summary;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "merge", mixinStandardHelpOptions = true,
        description = "Combine runs of the same benchmark (typically --shard runs) into one run directory.")
public final class MergeCommand implements Callable<Integer> {
    @Parameters(paramLabel = "RUN_DIR", arity = "2..*", description = "Run directories to combine.")
    List<Path> runDirs;

    @Option(names = {"-o", "--out"}, paramLabel = "DIR", description = "Where to create the merged run (default: ./results).")
    Path output = Path.of("results");

    @Override
    public Integer call() throws Exception {
        PrintStream console = System.out;
        List<RunManifest> manifests = new ArrayList<>();
        for (Path d : runDirs) {
            manifests.add(RunManifest.read(d));
        }
        RunManifest first = manifests.get(0);
        for (int i = 1; i < manifests.size(); i++) {
            String why = incompatibility(first, manifests.get(i));
            if (why != null) {
                console.println("Cannot merge " + runDirs.get(i) + " with " + runDirs.get(0) + ": " + why);
                return BenchCli.EXIT_USAGE;
            }
        }

        TreeMap<Integer, GameRecord> byId = new TreeMap<>();
        for (int i = 0; i < runDirs.size(); i++) {
            for (GameRecord g : RunSupport.readRecorded(runDirs.get(i), console)) {
                GameRecord prev = byId.putIfAbsent(g.gameId(), g);
                if (prev != null && !sameGame(prev, g)) {
                    console.println("Game " + g.gameId() + " was recorded twice with different results ("
                            + prev.runId() + " and " + g.runId() + "). Were these runs non-deterministic and "
                            + "overlapping? Merge runs whose shards don't overlap.");
                    return BenchCli.EXIT_USAGE;
                }
            }
        }

        int maxThreads = manifests.stream().mapToInt(m -> m.config().threads()).max().orElse(1);
        String runId = RunSupport.newRunId("merged-");
        Path runDir = Files.createDirectories(output.resolve(runId));
        BenchConfig config = first.config().withoutShard(output.toAbsolutePath()).withThreads(maxThreads);
        RunManifest merged = new RunManifest(runId, first.mtgbenchVersion(), first.forgeVersion(), config, first.decks());
        merged.write(runDir);
        Reports.rewriteGames(runDir.resolve("games.jsonl"), new ArrayList<>(byId.values()));

        int expected = BenchRun.schedule(config).size();
        Summary summary = BenchRun.writeSummary(merged, runDir, byId.values());
        console.print(Reports.table(summary, false));
        if (byId.size() < expected) {
            console.printf("Note: %d of %d games are present; the merged run is incomplete "
                    + "('mtgbench resume %s' plays the rest).%n", byId.size(), expected, runDir);
        }
        console.println("Merged " + runDirs.size() + " runs into " + runDir);
        return 0;
    }

    /** Runs can be merged when they are the same benchmark: same decks, settings and seed. */
    static String incompatibility(RunManifest a, RunManifest b) {
        BenchConfig x = a.config(), y = b.config();
        if (!Objects.equals(a.forgeVersion(), b.forgeVersion())) return "different Forge versions";
        if (x.mode() != y.mode() || x.format() != y.format()) return "different mode or format";
        if (x.games() != y.games()) return "different number of games";
        if (x.seed() != y.seed()) return "different seeds";
        if (x.maxRounds() != y.maxRounds() || x.gameTimeoutSec() != y.gameTimeoutSec()) return "different limits";
        if (!Objects.equals(x.aiProfile(), y.aiProfile())) return "different AI profiles";
        if (x.checkLegality() != y.checkLegality()) return "different legality settings";
        if (!a.opponentWeights().equals(b.opponentWeights())) return "different opponent weights";
        List<String> ha = a.decks().stream().map(RunManifest.DeckInfo::sha256).toList();
        List<String> hb = b.decks().stream().map(RunManifest.DeckInfo::sha256).toList();
        if (!ha.equals(hb)) return "different deck files";
        return null;
    }

    /** Same game result, ignoring which run recorded it and how long it took. */
    static boolean sameGame(GameRecord a, GameRecord b) {
        return a.gameId() == b.gameId() && a.seed() == b.seed() && a.outcome().equals(b.outcome())
                && a.turns() == b.turns() && Objects.equals(a.winnerSeat(), b.winnerSeat())
                && Objects.equals(a.stopReason(), b.stopReason()) && a.players().equals(b.players());
    }
}
