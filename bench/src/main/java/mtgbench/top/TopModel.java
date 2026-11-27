package mtgbench.top;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import mtgbench.config.BenchConfig;
import mtgbench.run.AdaptivePlan;
import mtgbench.run.BenchRun;
import mtgbench.run.RunManifest;
import mtgbench.schedule.GameSpec;
import mtgbench.stats.GameRecord;
import mtgbench.stats.Summary;

/**
 * What {@code mtgbench top} shows: a run's results so far, recomputed from games.jsonl with the same
 * code that writes summary.md, plus progress against the plan and the rate games are arriving at.
 */
public final class TopModel {
    /** How far back the games-per-minute rate looks. */
    static final long RATE_WINDOW_MS = 5 * 60_000;

    public enum State { QUEUED, PLAYING, DONE, CAPPED }

    /** One matchup (in pod mode, the one table). */
    public record Row(int index, String opponent, int games, int planned, int wins, int losses, int draws,
                      int timeouts, int errors, Double winRate, Double ciLow, Double ciHigh, Double avgRounds,
                      State state) {}

    /**
     * @param planned   games planned so far: the whole schedule, or for adaptive runs the current stages
     * @param finished  nothing is left to play
     * @param perMinute games per minute over the last few minutes of watching, or null before there's a rate
     * @param lastWrite when games.jsonl last changed (epoch ms), or null if there's none yet
     */
    public record Snapshot(RunManifest manifest, Summary summary, List<Row> rows, int played, int planned,
                           boolean finished, Double perMinute, Long lastWrite, int badLines) {}

    private final Path runDir;
    private final RunManifest manifest;
    private final GamesTail tail;
    private final Deque<long[]> samples = new ArrayDeque<>();
    private Snapshot snapshot;

    public TopModel(Path runDir) throws IOException {
        this.runDir = runDir;
        this.manifest = RunManifest.read(runDir);
        this.tail = new GamesTail(runDir.resolve("games.jsonl"));
    }

    /** Reads new games and recomputes if anything changed. */
    public Snapshot refresh(long now) throws IOException {
        boolean changed = tail.poll();
        int count = tail.games().size();
        samples.addLast(new long[] {now, count});
        while (samples.size() > 2 && samples.peekFirst()[0] < now - RATE_WINDOW_MS) {
            samples.removeFirst();
        }
        if (changed || snapshot == null) {
            snapshot = compute(manifest, tail.games(), null, null, tail.badLines());
        }
        Path games = runDir.resolve("games.jsonl");
        Long lastWrite = Files.exists(games) ? Files.getLastModifiedTime(games).toMillis() : null;
        snapshot = new Snapshot(snapshot.manifest(), snapshot.summary(), snapshot.rows(), snapshot.played(),
                snapshot.planned(), snapshot.finished(), rate(), lastWrite, snapshot.badLines());
        return snapshot;
    }

    /** Games per minute between the oldest and newest sample, once they are at least 10 s apart. */
    private Double rate() {
        long[] first = samples.peekFirst();
        long[] last = samples.peekLast();
        long span = last[0] - first[0];
        if (span < 10_000) {
            return null;
        }
        return (last[1] - first[1]) * 60_000.0 / span;
    }

    /** The snapshot for {@code games}; rate and file time are filled in by {@link #refresh}. */
    static Snapshot compute(RunManifest manifest, List<GameRecord> games, Double perMinute, Long lastWrite,
                            int badLines) {
        BenchConfig config = manifest.config();
        Summary summary = BenchRun.summarize(manifest, games);
        int matchups = summary.matchups().size();
        int[] planned = new int[matchups];
        boolean[] done = new boolean[matchups];
        boolean[] capped = new boolean[matchups];
        if (config.adaptive() != null) {
            List<AdaptivePlan.ChainStatus> chains = AdaptivePlan.status(config, games);
            for (int c = 0; c < chains.size() && c < matchups; c++) {
                planned[c] = chains.get(c).planned();
                done[c] = chains.get(c).finished();
                capped[c] = chains.get(c).capped();
            }
        } else {
            for (GameSpec spec : BenchRun.schedule(config)) {
                if (spec.matchup() < matchups) {
                    planned[spec.matchup()]++;
                }
            }
        }
        boolean finished = BenchRun.remaining(config, games).isEmpty();
        List<Row> rows = new ArrayList<>();
        int plannedTotal = 0;
        for (Summary.MatchupSummary m : summary.matchups()) {
            int i = m.matchup();
            int plan = i < matchups ? planned[i] : 0;
            plannedTotal += plan;
            State state;
            if (config.adaptive() != null ? done[i] : m.games() >= plan && plan > 0) {
                state = capped[i] ? State.CAPPED : State.DONE;
            } else {
                state = m.games() > 0 ? State.PLAYING : State.QUEUED;
            }
            Summary.Rate all = m.all();
            rows.add(new Row(i, m.opponent(), m.games(), plan, m.wins(), m.losses(), m.draws(), m.timeouts(),
                    m.errors(), all == null ? null : all.rate(), all == null ? null : all.ciLow(),
                    all == null ? null : all.ciHigh(), m.avgRounds(), state));
        }
        return new Snapshot(manifest, summary, List.copyOf(rows), games.size(), plannedTotal, finished, perMinute,
                lastWrite, badLines);
    }
}
