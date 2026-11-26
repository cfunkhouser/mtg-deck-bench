package mtgbench.run;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import mtgbench.config.BenchConfig;
import mtgbench.deck.LoadedDeck;
import mtgbench.engine.GameRunner;
import mtgbench.report.Reports;
import mtgbench.schedule.GameSpec;
import mtgbench.schedule.Scheduler;
import mtgbench.stats.Aggregator;
import mtgbench.stats.GameRecord;
import mtgbench.stats.Summary;

/**
 * Plays the games of a run that aren't recorded yet and writes the results. Used for new runs
 * (nothing recorded) and for resume. Forge must already be initialized.
 */
public final class BenchRun {
    public static final int EXIT_OK = 0;
    public static final int EXIT_TOO_MANY_ERRORS = 3;

    private final RunManifest manifest;
    private final BenchConfig config;
    private final Path runDir;
    private final List<LoadedDeck> decks;
    private final PrintStream console;

    /** @param decks the target deck first, then the opponents in manifest order */
    public BenchRun(RunManifest manifest, BenchConfig config, Path runDir, List<LoadedDeck> decks,
                    PrintStream console) {
        this.manifest = manifest;
        this.config = config;
        this.runDir = runDir;
        this.decks = List.copyOf(decks);
        this.console = console;
    }

    /** Every game of the run, ignoring any shard. */
    public static List<GameSpec> fullSchedule(BenchConfig config) {
        return config.mode() == BenchConfig.Mode.POD
                ? Scheduler.pod(config.opponents().size(), config.scheduledGames(), config.seed())
                : Scheduler.gauntlet(config.opponents().size(), config.scheduledGames(), config.seed());
    }

    /** Every game of the run's schedule that belongs to its shard (all games if not sharded). */
    public static List<GameSpec> schedule(BenchConfig config) {
        List<GameSpec> all = fullSchedule(config);
        if (config.shard() == null) {
            return all;
        }
        return all.stream().filter(s -> config.shard().includes(s.gameId())).toList();
    }

    /**
     * Games of the run that still have to be played. Fixed-size runs: every scheduled game (of the shard)
     * not recorded yet. Adaptive runs: the missing games of the current stage; once a stage is complete,
     * the next one is planned from its results, so call this again after playing what it returns.
     */
    public static List<GameSpec> remaining(BenchConfig config, Collection<GameRecord> recorded) {
        if (config.adaptive() != null) {
            return AdaptivePlan.remaining(config, recorded);
        }
        Set<Integer> done = new HashSet<>();
        recorded.forEach(r -> done.add(r.gameId()));
        return schedule(config).stream().filter(s -> !done.contains(s.gameId())).toList();
    }

    public static Summary summarize(RunManifest manifest, Collection<GameRecord> records) {
        BenchConfig config = manifest.config();
        Summary summary = config.mode() == BenchConfig.Mode.POD
                ? Aggregator.pod(manifest.runId(), manifest.targetName(), manifest.opponentNames(), records)
                : Aggregator.gauntlet(manifest.runId(), manifest.targetName(), manifest.opponentNames(),
                        manifest.opponentWeights(), records);
        if (config.adaptive() == null) {
            return summary;
        }
        List<AdaptivePlan.ChainStatus> status = AdaptivePlan.status(config, records);
        List<String> names = config.mode() == BenchConfig.Mode.POD ? List.of("the table") : manifest.opponentNames();
        List<String> capped = new ArrayList<>();
        for (int c = 0; c < status.size(); c++) {
            if (status.get(c).capped()) {
                capped.add(names.get(c));
            }
        }
        boolean finished = status.stream().allMatch(AdaptivePlan.ChainStatus::finished);
        return summary.withAdaptive(new Summary.AdaptiveSummary(config.adaptive().targetCi(),
                config.adaptive().maxGames(), finished, capped));
    }

    /** Writes summary.json and summary.md for {@code records}. */
    public static Summary writeSummary(RunManifest manifest, Path dir, Collection<GameRecord> records)
            throws IOException {
        Summary summary = summarize(manifest, records);
        Reports.writeJson(dir.resolve("summary.json"), summary);
        Files.writeString(dir.resolve("summary.md"), Reports.table(summary, true));
        return summary;
    }

    /**
     * Plays until {@link #remaining} is empty: once for a fixed-size run, stage by stage for an adaptive one.
     *
     * @param recorded games already in games.jsonl (resume); they are kept and not played again
     */
    public Summary execute(List<GameRecord> recorded) throws IOException, InterruptedException {
        List<GameRecord> records = Collections.synchronizedList(new ArrayList<>(recorded));
        List<GameSpec> todo = remaining(config, recorded);
        if (todo.isEmpty()) {
            console.println("Nothing left to play: all " + recorded.size() + " games are recorded.");
            return writeSummary(manifest, runDir, records);
        }

        Path gameLogDir = null;
        if (config.gameLogs()) {
            gameLogDir = Files.createDirectories(runDir.resolve("logs").resolve("games"));
        }
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(daemon("watchdog"));
        ExecutorService pool = Executors.newFixedThreadPool(config.threads(), daemon("game-worker"));
        GameRunner runner = new GameRunner(manifest.runId(), modeName(config), config.format(), decks, config.aiProfile(),
                config.maxRounds(), config.gameTimeoutSec(), watchdog, gameLogDir);
        long start = System.currentTimeMillis();
        try (Reports.GamesJsonl jsonl = new Reports.GamesJsonl(runDir.resolve("games.jsonl"), !recorded.isEmpty())) {
            boolean first = true;
            while (!todo.isEmpty()) {
                if (config.adaptive() != null) {
                    printStage(records);
                }
                console.printf("Playing %d games%s%s on %d threads...%n", todo.size(),
                        first && !recorded.isEmpty() ? " (" + recorded.size() + " already recorded)" : "",
                        config.shard() != null ? " (shard " + config.shard() + ")" : "", config.threads());
                play(todo, runner, pool, jsonl, records, start);
                first = false;
                synchronized (records) {
                    todo = remaining(config, List.copyOf(records));
                }
            }
        } finally {
            pool.shutdownNow();
            watchdog.shutdownNow();
        }
        if (config.adaptive() != null) {
            printStage(records);
        }
        return writeSummary(manifest, runDir, records);
    }

    private void play(List<GameSpec> todo, GameRunner runner, ExecutorService pool, Reports.GamesJsonl jsonl,
                      List<GameRecord> records, long start) throws IOException, InterruptedException {
        AtomicInteger finished = new AtomicInteger();
        AtomicInteger targetWins = new AtomicInteger();
        AtomicInteger decided = new AtomicInteger();
        int total = todo.size();
        int every = Math.max(1, total / 20);
        List<Future<?>> futures = new ArrayList<>(total);
        for (GameSpec spec : todo) {
            futures.add(pool.submit(() -> {
                GameRecord r = runner.play(spec);
                records.add(r);
                try {
                    jsonl.append(r);
                } catch (IOException e) {
                    throw new RuntimeException("cannot write games.jsonl", e);
                }
                if (r.outcome().equals(GameRecord.WIN)) {
                    targetWins.incrementAndGet();
                }
                if (!r.outcome().equals(GameRecord.TIMEOUT) && !r.outcome().equals(GameRecord.ERROR)) {
                    decided.incrementAndGet();
                }
                int n = finished.incrementAndGet();
                if (n % every == 0 || n == total) {
                    long elapsed = System.currentTimeMillis() - start;
                    console.printf(Locale.ROOT, "  [%d/%d] %.0fs elapsed, target won %d of %d decided games%n",
                            n, total, elapsed / 1000.0, targetWins.get(), decided.get());
                }
            }));
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (ExecutionException e) {
                throw new IOException(e.getCause().getMessage(), e.getCause());
            }
        }
    }

    /** One line per matchup: how far its CI is from the target and where its current stage ends. */
    private void printStage(List<GameRecord> records) {
        List<AdaptivePlan.ChainStatus> status;
        synchronized (records) {
            status = AdaptivePlan.status(config, List.copyOf(records));
        }
        List<String> names = config.mode() == BenchConfig.Mode.POD ? List.of("the table") : manifest.opponentNames();
        console.printf(Locale.ROOT, "Target CI ±%.1f%%, at most %d games%s:%n", 100 * config.adaptive().targetCi(),
                config.adaptive().maxGames(), config.mode() == BenchConfig.Mode.POD ? "" : " per matchup");
        for (int c = 0; c < status.size(); c++) {
            AdaptivePlan.ChainStatus st = status.get(c);
            String ci = st.halfWidth() == null ? "no CI yet" : String.format(Locale.ROOT, "±%.1f%%", 100 * st.halfWidth());
            String state = !st.finished() ? "playing to " + st.planned() + " games"
                    : st.capped() ? "stopped at the cap of " + st.planned() + " games" : "done after " + st.planned() + " games";
            console.printf("  %-28s %-10s %s%n", names.get(c), ci, state);
        }
    }

    public static String modeName(BenchConfig config) {
        return config.mode().name().toLowerCase(Locale.ROOT);
    }

    private static ThreadFactory daemon(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            // Daemon threads: a game stuck in Forge code that ignores interrupts can't keep the JVM alive.
            t.setDaemon(true);
            return t;
        };
    }
}
