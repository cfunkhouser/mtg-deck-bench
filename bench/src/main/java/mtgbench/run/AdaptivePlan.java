package mtgbench.run;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mtgbench.config.BenchConfig;
import mtgbench.config.BenchConfig.Adaptive;
import mtgbench.schedule.GameSpec;
import mtgbench.stats.Aggregator;
import mtgbench.stats.GameRecord;

/**
 * Decides which games an adaptive run ({@code --target-ci}) plays next. Pure: no Forge, no I/O.
 *
 * <p>Each matchup (in pod mode: the whole table) is a <em>chain</em> of games in schedule order. A chain
 * grows in stages. The first stage is {@code games}; when a stage is fully played, its 95% Wilson
 * half-width is checked. If it is above the target and the cap isn't reached, the next stage is
 * projected from the observed win rate and share of decided games. The plan is recomputed from the
 * recorded games alone, so resume needs no extra state, and a deterministic run makes the same
 * decisions every time.
 *
 * <p>Stages after the first are multiples of {@code step} (2 in a gauntlet, the table size in a pod),
 * so every stopping point keeps the play/draw or seat balance. Only games inside a fully played stage
 * are counted, so the decision never depends on which games of a stage happened to finish first.
 */
public final class AdaptivePlan {
    /** Each stage grows a chain by at least this share, so batches don't get too small to keep all threads busy. */
    static final double MIN_GROWTH = 0.25;

    private AdaptivePlan() {}

    /**
     * Where one chain stands.
     *
     * @param planned   games in the current stage (all of them, once finished)
     * @param played    games of the current stage already recorded
     * @param finished  the chain needs no more games
     * @param halfWidth CI half-width over the last fully played stage; null before the first is done
     *                  or when it has no decided game
     * @param capped    finished at the cap without reaching the target
     */
    public record ChainStatus(int planned, int played, boolean finished, Double halfWidth, boolean capped) {}

    /** Games still to play in the current stage of every chain; empty when the run is finished. */
    public static List<GameSpec> remaining(BenchConfig config, Collection<GameRecord> recorded) {
        Map<Integer, GameRecord> byId = byId(recorded);
        List<ChainStatus> status = status(config, byId);
        List<GameSpec> todo = new ArrayList<>();
        for (GameSpec spec : BenchRun.fullSchedule(config)) {
            ChainStatus c = status.get(chain(config, spec.gameId()));
            if (!c.finished() && index(config, spec.gameId()) < c.planned() && !byId.containsKey(spec.gameId())) {
                todo.add(spec);
            }
        }
        return todo;
    }

    /** One status per chain: per opponent in a gauntlet, one for the table in a pod. */
    public static List<ChainStatus> status(BenchConfig config, Collection<GameRecord> recorded) {
        return status(config, byId(recorded));
    }

    private static List<ChainStatus> status(BenchConfig config, Map<Integer, GameRecord> byId) {
        Adaptive a = config.adaptive();
        if (a == null) {
            throw new IllegalArgumentException("not an adaptive run");
        }
        int chains = chains(config);
        List<List<GameRecord>> byChain = new ArrayList<>();
        for (int c = 0; c < chains; c++) {
            // Indexed by position in the chain; null = not played yet.
            byChain.add(new ArrayList<>(Collections.nCopies(a.maxGames(), (GameRecord) null)));
        }
        for (GameRecord g : byId.values()) {
            int index = index(config, g.gameId());
            if (g.gameId() < chains * a.maxGames() && index < a.maxGames()) {
                byChain.get(chain(config, g.gameId())).set(index, g);
            }
        }
        List<ChainStatus> result = new ArrayList<>();
        for (List<GameRecord> games : byChain) {
            result.add(chainStatus(config.games(), a.maxGames(), step(config), a.targetCi(), games));
        }
        return result;
    }

    /** Follows one chain's stages over its games (by position; null = not played). */
    static ChainStatus chainStatus(int first, int cap, int step, double target, List<GameRecord> games) {
        int planned = Math.min(first, cap);
        Double lastHalfWidth = null;
        while (true) {
            int played = 0, decided = 0;
            double wins = 0;
            for (int k = 0; k < planned; k++) {
                GameRecord g = games.get(k);
                if (g == null) {
                    continue;
                }
                played++;
                switch (g.outcome()) {
                    case GameRecord.WIN -> { decided++; wins += 1; }
                    case GameRecord.DRAW -> { decided++; wins += 0.5; }
                    case GameRecord.LOSS -> decided++;
                    default -> { }
                }
            }
            if (played < planned) {
                return new ChainStatus(planned, played, false, lastHalfWidth, false);
            }
            Double half = decided > 0 ? Aggregator.wilsonHalfWidth(wins / decided, decided) : null;
            boolean reached = half != null && half <= target;
            if (reached || planned >= cap) {
                return new ChainStatus(planned, played, true, half, !reached);
            }
            lastHalfWidth = half;
            planned = nextStage(planned, decided, wins, target, step, cap);
        }
    }

    /**
     * The next stage size after {@code games} played games with {@code decided} decided and {@code wins}
     * wins: enough to reach the target at the observed rate, growing by at least {@link #MIN_GROWTH}
     * and at most doubling, rounded up to {@code step}, never past {@code cap}.
     */
    static int nextStage(int games, int decided, double wins, double target, int step, int cap) {
        int wanted;
        if (decided == 0) {
            wanted = 2 * games;
        } else {
            int decidedNeeded = Adaptive.decidedGamesNeeded(wins / decided, target);
            wanted = (int) Math.ceil(decidedNeeded * (double) games / decided);
        }
        int lowest = games + Math.max(1, (int) Math.ceil(games * MIN_GROWTH));
        int next = Math.min(Math.max(wanted, lowest), 2 * games);
        return Math.min(Adaptive.roundUp(next, step), cap);
    }

    private static Map<Integer, GameRecord> byId(Collection<GameRecord> recorded) {
        Map<Integer, GameRecord> byId = new HashMap<>();
        recorded.forEach(r -> byId.put(r.gameId(), r));
        return byId;
    }

    private static boolean pod(BenchConfig config) {
        return config.mode() == BenchConfig.Mode.POD;
    }

    static int chains(BenchConfig config) {
        return pod(config) ? 1 : config.opponents().size();
    }

    /** Stopping points keep the balance: a play/draw pair, or a full seat rotation. */
    public static int step(BenchConfig config) {
        return pod(config) ? config.opponents().size() + 1 : 2;
    }

    /** Gauntlet games are interleaved across matchups (see Scheduler.gauntlet). */
    private static int chain(BenchConfig config, int gameId) {
        return pod(config) ? 0 : gameId % config.opponents().size();
    }

    private static int index(BenchConfig config, int gameId) {
        return pod(config) ? gameId : gameId / config.opponents().size();
    }
}
