package mtgbench.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import mtgbench.stats.GameRecord.PlayerRecord;
import mtgbench.stats.Summary.MatchupSummary;

class AggregatorTest {
    private static int nextId;

    private static GameRecord game(int matchup, String outcome, boolean targetOnPlay) {
        List<PlayerRecord> players = List.of(
                new PlayerRecord(0, "T", true, targetOnPlay, 0, 20, null),
                new PlayerRecord(1, "B" + matchup, false, !targetOnPlay, 0, 20, null));
        return new GameRecord("r", nextId++, "gauntlet", "constructed", matchup, "T vs B", 0, players,
                null, outcome, null, 10, 100, "x", null);
    }

    private static List<GameRecord> games(int matchup, int wins, int losses, int draws, int timeouts, int errors) {
        List<GameRecord> g = new ArrayList<>();
        for (int i = 0; i < wins; i++) g.add(game(matchup, GameRecord.WIN, i % 2 == 0));
        for (int i = 0; i < losses; i++) g.add(game(matchup, GameRecord.LOSS, i % 2 == 1));
        for (int i = 0; i < draws; i++) g.add(game(matchup, GameRecord.DRAW, true));
        for (int i = 0; i < timeouts; i++) g.add(game(matchup, GameRecord.TIMEOUT, true));
        for (int i = 0; i < errors; i++) g.add(game(matchup, GameRecord.ERROR, true));
        return g;
    }

    @Test
    void wilsonMatchesKnownValues() {
        double[] ci = Aggregator.wilson(50, 100);
        assertEquals(0.4038, ci[0], 1e-4);
        assertEquals(0.5962, ci[1], 1e-4);
        double[] zero = Aggregator.wilson(0, 10);
        assertEquals(0.0, zero[0], 1e-9);
        assertEquals(0.2775, zero[1], 1e-4);
    }

    @Test
    void drawsCountHalfAndTimeoutsAreExcluded() {
        List<GameRecord> all = games(0, 6, 2, 2, 1, 0);
        Summary s = Aggregator.gauntlet("r", "T", List.of("B0"), List.of(1.0), all);
        MatchupSummary m = s.matchups().get(0);
        assertEquals(10, m.all().decided());
        assertEquals(0.7, m.all().rate(), 1e-9);
        assertEquals(1, m.timeouts());
        assertTrue(m.flagged(), "1 of 11 games timed out (> 5%)");
        assertEquals(70.0, s.score(), 1e-9);
    }

    @Test
    void weightedScoreAndWorstMatchup() {
        List<GameRecord> all = new ArrayList<>(games(0, 8, 2, 0, 0, 0));   // 80%
        all.addAll(games(1, 2, 8, 0, 0, 0));                                // 20%
        Summary s = Aggregator.gauntlet("r", "T", List.of("A", "B"), List.of(3.0, 1.0), all);
        assertEquals(65.0, s.score(), 1e-9);            // 0.75*80 + 0.25*20
        assertEquals(50.0, s.unweightedMean(), 1e-9);
        assertEquals("B", s.worstMatchup());
        assertFalse(s.matchups().get(0).flagged());
        // CI: 1.96 * sqrt(0.75^2*0.16/10 + 0.25^2*0.16/10) * 100
        assertEquals(100 * 1.959964 * Math.sqrt((0.5625 + 0.0625) * 0.016), s.scoreCiHalfWidth(), 1e-3);
    }

    @Test
    void playDrawSplit() {
        Summary s = Aggregator.gauntlet("r", "T", List.of("A"), List.of(1.0), games(0, 4, 4, 0, 0, 0));
        // wins alternate on-play/on-draw starting on play; losses start on draw
        assertEquals(4, s.matchups().get(0).onPlay().decided());
        assertEquals(0.5, s.matchups().get(0).onPlay().rate(), 1e-9);
    }

    @Test
    void matchupWithoutDecidedGamesIsLeftOutOfTheScore() {
        List<GameRecord> all = new ArrayList<>(games(0, 5, 5, 0, 0, 0));
        all.addAll(games(1, 0, 0, 0, 0, 3));
        Summary s = Aggregator.gauntlet("r", "T", List.of("A", "B"), List.of(1.0, 1.0), all);
        assertNull(s.matchups().get(1).all().rate());
        assertEquals(50.0, s.score(), 1e-9);
        assertEquals(3 / 13.0, s.errorRate(), 1e-9);
    }

    private static GameRecord commanderGame(String outcome, String targetLoss, String oppLoss, int casts, Integer first) {
        List<PlayerRecord> players = List.of(
                new PlayerRecord(0, "T", true, true, 0, 10, targetLoss, casts, first, 0),
                new PlayerRecord(1, "B", false, false, 0, 10, oppLoss, 1, 3, 0));
        return new GameRecord("r", nextId++, "gauntlet", "commander", 0, "T vs B", 0, players,
                null, outcome, null, 10, 100, "x", null);
    }

    @Test
    void commanderStatsFromTargetsSide() {
        List<GameRecord> all = List.of(
                commanderGame(GameRecord.WIN, null, "CommanderDamage", 2, 3),
                commanderGame(GameRecord.WIN, null, "LifeReachedZero", 1, 5),
                commanderGame(GameRecord.LOSS, "CommanderDamage", null, 0, null),
                commanderGame(GameRecord.LOSS, "LifeReachedZero", null, 1, 4),
                commanderGame(GameRecord.TIMEOUT, null, null, 3, 2));   // not decided: ignored
        Summary.CommanderStats c = Aggregator.gauntlet("r", "T", List.of("B"), List.of(1.0), all)
                .matchups().get(0).commander();
        assertEquals(1, c.winsByCommanderDamage());
        assertEquals(1, c.lossesByCommanderDamage());
        assertEquals(0.75, c.castRate(), 1e-9);
        assertEquals(1.0, c.avgCasts(), 1e-9);
        assertEquals(4.0, c.avgFirstCastRound(), 1e-9);
    }

    @Test
    void noCommanderStatsForConstructed() {
        Summary s = Aggregator.gauntlet("r", "T", List.of("A"), List.of(1.0), games(0, 1, 1, 0, 0, 0));
        assertNull(s.matchups().get(0).commander());
    }

    private static GameRecord detailed(String outcome, int turns, int targetMull, int oppMull,
                                       String targetLoss, String oppLoss) {
        List<PlayerRecord> players = List.of(
                new PlayerRecord(0, "T", true, true, targetMull, 0, targetLoss),
                new PlayerRecord(1, "B", false, false, oppMull, 0, oppLoss));
        return new GameRecord("r", nextId++, "gauntlet", "constructed", 0, "T vs B", 0, players,
                null, outcome, null, turns, 100, "x", null);
    }

    @Test
    void mulligansReasonsAndRounds() {
        List<GameRecord> all = List.of(
                detailed(GameRecord.WIN, 9, 0, 1, null, "LifeReachedZero"),    // round 5
                detailed(GameRecord.WIN, 10, 1, 0, null, "Milled"),            // round 5
                detailed(GameRecord.WIN, 13, 0, 0, null, "LifeReachedZero"),   // round 7
                detailed(GameRecord.LOSS, 8, 1, 0, "LifeReachedZero", null),   // round 4
                detailed(GameRecord.TIMEOUT, 40, 2, 2, null, null));           // ignored except counts
        MatchupSummary m = Aggregator.gauntlet("r", "T", List.of("B"), List.of(1.0), all).matchups().get(0);

        assertEquals(0.5, m.mulligans().targetRate(), 1e-9);
        assertEquals(0.25, m.mulligans().opponentRate(), 1e-9);
        assertEquals(0.5, m.mulligans().afterMulligan().rate(), 1e-9);
        assertEquals(1.0, m.mulligans().withoutMulligan().rate(), 1e-9);

        assertEquals(List.of("LifeReachedZero", "Milled"), List.copyOf(m.targetWinReasons().keySet()));
        assertEquals(2, m.targetWinReasons().get("LifeReachedZero"));
        assertEquals(java.util.Map.of("LifeReachedZero", 1), m.targetLossReasons());

        assertEquals(java.util.Map.of(5, 2, 7, 1), m.winRounds());
        assertEquals(java.util.Map.of(4, 1), m.lossRounds());
        assertEquals(5.25, m.avgRounds(), 1e-9);
    }

    @Test
    void roundOfAGame() {
        assertEquals(1, Aggregator.round(detailed(GameRecord.WIN, 1, 0, 0, null, null)));
        assertEquals(1, Aggregator.round(detailed(GameRecord.WIN, 2, 0, 0, null, null)));
        assertEquals(2, Aggregator.round(detailed(GameRecord.WIN, 3, 0, 0, null, null)));
    }

    private static GameRecord podGame(int targetSeat, Integer winnerSeat, int[] placement, Integer targetEliminatedBy) {
        String[] decks = {"A", "B", "C"};
        List<PlayerRecord> players = new ArrayList<>();
        for (int seat = 0; seat < 3; seat++) {
            boolean target = seat == targetSeat;
            String deck = target ? "T" : decks[seat];
            Integer place = placement == null ? null : placement[seat];
            String loss = winnerSeat != null && seat != winnerSeat ? "LifeReachedZero" : null;
            players.add(new PlayerRecord(seat, deck, target, seat == 0, 0, 0, loss, null, null, null,
                    place, loss != null ? 5 : null, target ? targetEliminatedBy : null));
        }
        String outcome = winnerSeat == null ? GameRecord.TIMEOUT : winnerSeat == targetSeat ? GameRecord.WIN : GameRecord.LOSS;
        return new GameRecord("r", nextId++, "pod", "constructed", 0, "pod", 0, players, winnerSeat, outcome,
                winnerSeat == null ? "turnCap" : null, 30, 100, "x", null);
    }

    @Test
    void podSummary() {
        List<GameRecord> all = List.of(
                podGame(0, 0, new int[] {1, 3, 2}, null),        // T (seat 0) wins
                podGame(1, 2, new int[] {3, 2, 1}, 2),           // T (seat 1) 2nd; C won, dealt last damage
                podGame(2, 0, new int[] {1, 2, 3}, 0),           // T (seat 2) 3rd; A won
                podGame(0, null, null, null));                   // timeout
        Summary s = Aggregator.pod("r", "T", List.of("A", "C"), all);
        Summary.PodSummary p = s.pod();
        assertEquals(3, p.players());
        assertEquals(1.0 / 3, p.winShare().rate(), 1e-9);
        assertEquals(50.0, s.score(), 1e-9, "a third of the wins at a 3-player table is the fair share");
        assertEquals(2.0, p.meanPlacement(), 1e-9);
        assertEquals(java.util.Map.of(1, 1, 2, 1, 3, 1), p.placements());
        assertEquals(1.0, p.bySeat().get(0).rate(), 1e-9);
        assertEquals(0.0, p.bySeat().get(1).rate(), 1e-9);
        assertEquals(1, p.deckWins().get("T"));
        assertEquals(java.util.Map.of("C", 1, "A", 1), p.winnerWhenTargetLost());
        assertEquals(1, s.matchups().size());
        assertEquals(1, s.matchups().get(0).timeouts());
        assertEquals(0.25, s.timeoutRate(), 1e-9);
    }
}
