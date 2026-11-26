package mtgbench.stats;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Predicate;

import mtgbench.stats.Summary.CommanderStats;
import mtgbench.stats.Summary.MatchupSummary;
import mtgbench.stats.Summary.MulliganStats;
import mtgbench.stats.Summary.Rate;

/** Turns game records into a {@link Summary}. Pure functions: no Forge, no I/O. */
public final class Aggregator {
    /** A matchup is flagged when more than this share of its games ended in timeout or error. */
    public static final double FLAG_THRESHOLD = 0.05;
    private static final double Z95 = 1.959963984540054;

    private Aggregator() {}

    /**
     * @param opponentNames display name per matchup index
     * @param weights       metagame weight per matchup index (any positive scale)
     */
    public static Summary gauntlet(String runId, String target, List<String> opponentNames, List<Double> weights,
                                   Collection<GameRecord> games) {
        List<MatchupSummary> matchups = new ArrayList<>();
        for (int m = 0; m < opponentNames.size(); m++) {
            final int matchup = m;
            List<GameRecord> mg = games.stream().filter(g -> g.matchup() == matchup).toList();
            int wins = count(mg, GameRecord.WIN), losses = count(mg, GameRecord.LOSS), draws = count(mg, GameRecord.DRAW);
            int timeouts = count(mg, GameRecord.TIMEOUT), errors = count(mg, GameRecord.ERROR);
            Double avgTurns = mg.stream().filter(Aggregator::decided).mapToInt(GameRecord::turns).average().stream()
                    .boxed().findFirst().orElse(null);
            boolean flagged = !mg.isEmpty() && (timeouts + errors) > FLAG_THRESHOLD * mg.size();
            matchups.add(new MatchupSummary(m, opponentNames.get(m), weights.get(m), mg.size(), wins, losses, draws,
                    timeouts, errors, rate(mg, g -> true),
                    rate(mg, g -> g.targetPlayer().onPlay()), rate(mg, g -> !g.targetPlayer().onPlay()),
                    avgTurns, flagged, commanderStats(mg), avgRounds(mg), mulliganStats(mg),
                    reasons(mg, GameRecord.WIN), reasons(mg, GameRecord.LOSS),
                    roundHistogram(mg, GameRecord.WIN), roundHistogram(mg, GameRecord.LOSS)));
        }

        // Weighted score over matchups that have at least one decided game.
        double weightSum = 0, score = 0, variance = 0, unweighted = 0;
        int scored = 0;
        String worst = null;
        double worstRate = Double.MAX_VALUE;
        for (MatchupSummary ms : matchups) {
            if (ms.all().rate() == null) {
                continue;
            }
            weightSum += ms.weight();
        }
        for (MatchupSummary ms : matchups) {
            Double w = ms.all().rate();
            if (w == null) {
                continue;
            }
            double omega = ms.weight() / weightSum;
            score += omega * w;
            variance += omega * omega * w * (1 - w) / ms.all().decided();
            unweighted += w;
            scored++;
            if (w < worstRate) {
                worstRate = w;
                worst = ms.opponent();
            }
        }

        int n = games.size();
        return new Summary(runId, target, n, rate(games, g -> true),
                scored > 0 ? 100 * score : null,
                scored > 0 ? 100 * Z95 * Math.sqrt(variance) : null,
                scored > 0 ? 100 * unweighted / scored : null,
                worst,
                n == 0 ? 0 : (double) count(games, GameRecord.TIMEOUT) / n,
                n == 0 ? 0 : (double) count(games, GameRecord.ERROR) / n,
                matchups);
    }

    /** Round in which a game ended: Forge turns spread over the players at the table. */
    public static int round(GameRecord g) {
        int players = Math.max(1, g.players().size());
        return Math.max(1, (g.turns() + players - 1) / players);
    }

    static Double avgRounds(List<GameRecord> games) {
        return games.stream().filter(Aggregator::decided).mapToInt(Aggregator::round).average().stream()
                .boxed().findFirst().orElse(null);
    }

    static MulliganStats mulliganStats(List<GameRecord> games) {
        List<GameRecord> decided = games.stream().filter(Aggregator::decided).toList();
        if (decided.isEmpty()) {
            return new MulliganStats(null, null, rate(decided, g -> true), rate(decided, g -> true));
        }
        long target = decided.stream().filter(g -> g.targetPlayer().mulligans() > 0).count();
        long opponent = decided.stream()
                .filter(g -> g.players().stream().anyMatch(p -> !p.target() && p.mulligans() > 0)).count();
        return new MulliganStats((double) target / decided.size(), (double) opponent / decided.size(),
                rate(decided, g -> g.targetPlayer().mulligans() > 0),
                rate(decided, g -> g.targetPlayer().mulligans() == 0));
    }

    /**
     * Wins: how the opponent lost. Losses: how the target lost. Most frequent first; "Unknown" when
     * Forge recorded no reason.
     */
    static Map<String, Integer> reasons(List<GameRecord> games, String outcome) {
        Map<String, Integer> counts = new HashMap<>();
        for (GameRecord g : games) {
            if (!g.outcome().equals(outcome)) {
                continue;
            }
            String reason;
            if (outcome.equals(GameRecord.LOSS)) {
                reason = g.targetPlayer().lossReason();
            } else {
                reason = g.players().stream().filter(p -> !p.target() && p.lossReason() != null)
                        .map(GameRecord.PlayerRecord::lossReason).findFirst().orElse(null);
            }
            counts.merge(reason != null ? reason : "Unknown", 1, Integer::sum);
        }
        Map<String, Integer> sorted = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    static SortedMap<Integer, Integer> roundHistogram(List<GameRecord> games, String outcome) {
        SortedMap<Integer, Integer> h = new TreeMap<>();
        for (GameRecord g : games) {
            if (g.outcome().equals(outcome)) {
                h.merge(round(g), 1, Integer::sum);
            }
        }
        return h;
    }

    /** Null unless these are Commander games. */
    static CommanderStats commanderStats(List<GameRecord> games) {
        List<GameRecord> decided = games.stream().filter(Aggregator::decided)
                .filter(g -> "commander".equals(g.format())).toList();
        if (decided.isEmpty()) {
            return null;
        }
        int winsByCd = 0, lossesByCd = 0, castGames = 0, casts = 0, firstSum = 0;
        for (GameRecord g : decided) {
            GameRecord.PlayerRecord t = g.targetPlayer();
            boolean opponentDiedToCd = g.players().stream()
                    .anyMatch(p -> !p.target() && "CommanderDamage".equals(p.lossReason()));
            if (g.outcome().equals(GameRecord.WIN) && opponentDiedToCd) {
                winsByCd++;
            }
            if (g.outcome().equals(GameRecord.LOSS) && "CommanderDamage".equals(t.lossReason())) {
                lossesByCd++;
            }
            int c = t.commanderCasts() != null ? t.commanderCasts() : 0;
            casts += c;
            if (c > 0) {
                castGames++;
                if (t.firstCommanderCastRound() != null) {
                    firstSum += t.firstCommanderCastRound();
                }
            }
        }
        int n = decided.size();
        return new CommanderStats(winsByCd, lossesByCd, (double) castGames / n, (double) casts / n,
                castGames > 0 ? (double) firstSum / castGames : null);
    }

    static boolean decided(GameRecord g) {
        return g.outcome().equals(GameRecord.WIN) || g.outcome().equals(GameRecord.LOSS)
                || g.outcome().equals(GameRecord.DRAW);
    }

    private static int count(Collection<GameRecord> games, String outcome) {
        return (int) games.stream().filter(g -> g.outcome().equals(outcome)).count();
    }

    private static Rate rate(Collection<GameRecord> games, Predicate<GameRecord> filter) {
        int decided = 0;
        double wins = 0;
        for (GameRecord g : games) {
            if (!filter.test(g) || !decided(g)) {
                continue;
            }
            decided++;
            if (g.outcome().equals(GameRecord.WIN)) {
                wins += 1;
            } else if (g.outcome().equals(GameRecord.DRAW)) {
                wins += 0.5;
            }
        }
        if (decided == 0) {
            return new Rate(0, 0, null, null, null);
        }
        double[] ci = wilson(wins, decided);
        return new Rate(decided, wins, wins / decided, ci[0], ci[1]);
    }

    /** 95% Wilson score interval for {@code successes} out of {@code n}. */
    static double[] wilson(double successes, int n) {
        double p = successes / n;
        double z2 = Z95 * Z95;
        double denom = 1 + z2 / n;
        double centre = (p + z2 / (2 * n)) / denom;
        double half = Z95 * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / denom;
        return new double[] {Math.max(0, centre - half), Math.min(1, centre + half)};
    }
}
