package mtgbench.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import mtgbench.run.RunManifest;
import mtgbench.top.Terminal;
import mtgbench.top.TopModel;
import mtgbench.top.TopView;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "top", mixinStandardHelpOptions = true,
        description = {
            "Watch a run's results as they come in, like 'top': progress, the overall win rate, and one row per"
                + " matchup. It reads the run directory, so it works from another terminal while 'run' or"
                + " 'resume' plays, and on finished runs.",
            "",
            "Keys: g games, w win rate, r average rounds, n name, o run order (press again, or R, to reverse);"
                + " j/k or arrows to scroll, PgUp/PgDn, Home/End; q to quit.",
            "Without a terminal (or with -b) it prints snapshots instead."})
public final class TopCommand implements Callable<Integer> {
    @Spec
    CommandSpec spec;

    @Parameters(index = "0", arity = "0..1", paramLabel = "RUN_DIR",
            description = "Run directory. Default: the most recently updated run under ./results.")
    Path runDir;

    @Option(names = {"-d", "--delay"}, paramLabel = "SECONDS", description = "Seconds between updates (default: 2).")
    double delay = 2;

    @Option(names = {"-s", "--sort"}, paramLabel = "KEY",
            description = "Starting sort: games, win, rounds, name or order (default: games).")
    String sort = "games";

    @Option(names = {"-b", "--batch"}, description = "Print plain-text snapshots instead of a live screen.")
    boolean batch;

    @Option(names = {"-n", "--iterations"}, paramLabel = "N",
            description = "Stop after N updates. Default: no limit, but batch mode stops once the run is finished,"
                    + " and prints just one snapshot when there's no terminal and no -b.")
    Integer iterations;

    @Option(names = {"-w", "--width"}, paramLabel = "COLUMNS", description = "Line width in batch mode (default: 160).")
    int width = 160;

    @Override
    public Integer call() throws Exception {
        if (!(delay >= 0.1)) {
            throw new ParameterException(spec.commandLine(), "--delay must be at least 0.1 seconds");
        }
        Path dir = runDir != null ? runDir : latestRun(Path.of("results"));
        if (!Files.exists(dir.resolve(RunManifest.FILE))) {
            throw new ParameterException(spec.commandLine(), dir + " is not a run directory (no " + RunManifest.FILE + ")");
        }
        TopView.Settings view = initialSettings();
        TopModel model = new TopModel(dir);
        PrintStream out = System.out;

        Terminal term = batch ? null : Terminal.open(System.in, out);
        if (term == null) {
            return batch(model, view.scrolledTo(0), out, iterations != null ? iterations : (batch ? -1 : 1));
        }
        try (term) {
            return live(model, view, term);
        }
    }

    private TopView.Settings initialSettings() {
        TopView.SortKey key = switch (sort.toLowerCase()) {
            case "games", "g" -> TopView.SortKey.GAMES;
            case "win", "w", "winrate", "win-rate" -> TopView.SortKey.WIN_RATE;
            case "rounds", "r" -> TopView.SortKey.ROUNDS;
            case "name", "n" -> TopView.SortKey.NAME;
            case "order", "o" -> TopView.SortKey.ORDER;
            default -> throw new ParameterException(spec.commandLine(),
                    "--sort must be games, win, rounds, name or order, not " + sort);
        };
        TopView.Settings d = TopView.Settings.defaults(false);
        return d.sort() == key ? d : d.sortBy(key);
    }

    private int batch(TopModel model, TopView.Settings view, PrintStream out, int n) throws Exception {
        for (int i = 0; n < 0 || i < n; i++) {
            if (i > 0) {
                Thread.sleep((long) (delay * 1000));
                out.println();
            }
            long now = System.currentTimeMillis();
            TopModel.Snapshot s = model.refresh(now);
            for (String line : TopView.render(s, view, width, Integer.MAX_VALUE, now)) {
                out.println(line);
            }
            out.flush();
            if (s.finished()) {
                break; // nothing more will change
            }
        }
        return 0;
    }

    private int live(TopModel model, TopView.Settings view, Terminal term) throws Exception {
        view = new TopView.Settings(view.sort(), view.descending(), 0, true);
        long delayMs = (long) (delay * 1000);
        int updates = 0;
        TopModel.Snapshot s = null;
        int[] size = term.size();
        long nextUpdate = 0;
        while (iterations == null || updates < iterations || s == null) {
            long now = System.currentTimeMillis();
            if (now >= nextUpdate) {
                s = model.refresh(now);
                size = term.size();
                updates++;
                nextUpdate = now + delayMs;
            }
            int page = TopView.tableRows(s, size[0]);
            int last = Math.max(0, s.rows().size() - page);
            view = view.scrolledTo(Math.min(view.scroll(), last));
            term.draw(TopView.render(s, view, size[1], size[0], now));

            int key = term.readKey(Math.max(0, nextUpdate - System.currentTimeMillis()));
            if (key == 'q' || key == 'Q' || key == 3 /* Ctrl-C in raw mode */) {
                return 0;
            }
            TopView.SortKey sortKey = key > 0 ? TopView.SortKey.forKey((char) key) : null;
            if (sortKey != null) {
                view = view.sortBy(sortKey);
            } else if (key == 'R') {
                view = view.reversed();
            } else if (key == 'j' || key == Terminal.DOWN) {
                view = view.scrolledTo(Math.min(last, view.scroll() + 1));
            } else if (key == 'k' || key == Terminal.UP) {
                view = view.scrolledTo(view.scroll() - 1);
            } else if (key == Terminal.PAGE_DOWN || key == ' ') {
                view = view.scrolledTo(Math.min(last, view.scroll() + page));
            } else if (key == Terminal.PAGE_UP || key == 'b') {
                view = view.scrolledTo(view.scroll() - page);
            } else if (key == Terminal.HOME) {
                view = view.scrolledTo(0);
            } else if (key == Terminal.END) {
                view = view.scrolledTo(last);
            }
        }
        return 0;
    }

    /** The run directory under {@code results} whose files changed most recently. */
    private Path latestRun(Path results) throws IOException {
        if (!Files.isDirectory(results)) {
            throw new ParameterException(spec.commandLine(), "No run directory given and no " + results
                    + " directory here; pass the run directory, e.g. results/<runId>.");
        }
        try (Stream<Path> dirs = Files.list(results)) {
            List<Path> runs = dirs.filter(d -> Files.exists(d.resolve(RunManifest.FILE))).toList();
            return runs.stream().max(Comparator.comparing(TopCommand::lastChange)).orElseThrow(() ->
                    new ParameterException(spec.commandLine(), "No runs in " + results + "; pass the run directory."));
        }
    }

    private static FileTime lastChange(Path run) {
        try {
            Path games = run.resolve("games.jsonl");
            return Files.getLastModifiedTime(Files.exists(games) ? games : run.resolve(RunManifest.FILE));
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }
}
