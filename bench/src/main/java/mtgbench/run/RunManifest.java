package mtgbench.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import mtgbench.config.BenchConfig;
import mtgbench.deck.LoadedDeck;
import mtgbench.report.Reports;

/**
 * {@code config.resolved.yaml}: what a run was, so it can be resumed, replayed, re-reported and
 * merged later without the original command line.
 *
 * @param decks target first, then opponents in config order
 */
public record RunManifest(String runId, String mtgbenchVersion, String forgeVersion, BenchConfig config,
                          List<DeckInfo> decks) {

    public static final String FILE = "config.resolved.yaml";

    public record DeckInfo(String role, String name, Path path, String sha256, List<String> warnings) {}

    public RunManifest {
        decks = List.copyOf(decks);
    }

    public static RunManifest of(String runId, String mtgbenchVersion, String forgeVersion, BenchConfig config,
                                 List<LoadedDeck> loaded) {
        List<DeckInfo> infos = new ArrayList<>();
        for (int i = 0; i < loaded.size(); i++) {
            LoadedDeck d = loaded.get(i);
            infos.add(new DeckInfo(i == 0 ? "target" : "opponent", d.name(), d.source().toAbsolutePath(),
                    d.sha256(), d.warnings()));
        }
        return new RunManifest(runId, mtgbenchVersion, forgeVersion, config, infos);
    }

    public String targetName() {
        return decks.get(0).name();
    }

    public List<String> opponentNames() {
        return decks.subList(1, decks.size()).stream().map(DeckInfo::name).toList();
    }

    public List<Double> opponentWeights() {
        return config.opponents().stream().map(BenchConfig.DeckRef::weight).toList();
    }

    public void write(Path runDir) throws IOException {
        Reports.writeYaml(runDir.resolve(FILE), this);
    }

    public static RunManifest read(Path runDir) throws IOException {
        Path file = runDir.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            throw new IOException("Not a run directory (no " + FILE + "): " + runDir);
        }
        return Reports.readYaml(file, RunManifest.class);
    }
}
