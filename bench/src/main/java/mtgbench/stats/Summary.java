package mtgbench.stats;

import java.util.List;
import java.util.Map;
import java.util.SortedMap;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Aggregated results of a gauntlet run (written as {@code summary.json}).
 *
 * <p>Win rates count a draw as half a win: {@code w = (wins + 0.5·draws) / decided}, where decided
 * games are wins, losses and draws. Timeouts and errors are excluded and reported separately.
 *
 * <p>A round is one turn for every player; round 5 is each player's fifth turn. Forge's turn counter
 * counts every player's turn, so {@code avgTurns} is roughly players × {@code avgRounds}.
 *
 * <p>Win and loss reasons are Forge's loss reason of the losing player (LifeReachedZero, Milled,
 * Poisoned, CommanderDamage, SpellEffect, Conceded, ...).
 */
public record Summary(
        String runId,
        String target,
        int gamesPlayed,
        Rate overall,
        Double score,
        Double scoreCiHalfWidth,
        Double unweightedMean,
        String worstMatchup,
        double timeoutRate,
        double errorRate,
        List<MatchupSummary> matchups) {

    /** A win rate over some set of decided games, with a 95% Wilson interval. Null rate when n = 0. */
    public record Rate(int decided, double wins, Double rate, Double ciLow, Double ciHigh) {}

    public record MatchupSummary(
            int matchup,
            String opponent,
            double weight,
            int games,
            int wins,
            int losses,
            int draws,
            int timeouts,
            int errors,
            Rate all,
            Rate onPlay,
            Rate onDraw,
            Double avgTurns,
            boolean flagged,
            @JsonInclude(JsonInclude.Include.NON_NULL) CommanderStats commander,
            Double avgRounds,
            MulliganStats mulligans,
            Map<String, Integer> targetWinReasons,
            Map<String, Integer> targetLossReasons,
            SortedMap<Integer, Integer> winRounds,
            SortedMap<Integer, Integer> lossRounds) {}

    /**
     * Over decided games.
     *
     * @param targetRate      share of games where the target deck mulliganed at least once
     * @param opponentRate    same for the opponent
     * @param afterMulligan   target win rate in games where it mulliganed
     * @param withoutMulligan target win rate in games where it kept seven
     */
    public record MulliganStats(Double targetRate, Double opponentRate, Rate afterMulligan, Rate withoutMulligan) {}

    /**
     * Commander games only, over decided games, from the target deck's side.
     *
     * @param winsByCommanderDamage   target wins where the opponent lost to 21 commander damage
     * @param lossesByCommanderDamage target losses to commander damage
     * @param castRate                share of games where the target cast its commander at least once
     * @param avgCasts                average commander casts per game by the target
     * @param avgFirstCastRound       average round of the target's first commander cast (games where it was cast)
     */
    public record CommanderStats(int winsByCommanderDamage, int lossesByCommanderDamage, Double castRate,
                                 Double avgCasts, Double avgFirstCastRound) {}
}
