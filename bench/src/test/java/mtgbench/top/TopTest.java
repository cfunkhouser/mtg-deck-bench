package mtgbench.top;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import mtgbench.cli.BenchCliAccess;
import mtgbench.config.BenchConfig;
import mtgbench.config.BenchConfig.DeckRef;
import mtgbench.config.Format;
import mtgbench.run.RunManifest;
import mtgbench.stats.GameRecord;
import mtgbench.stats.GameRecord.PlayerRecord;

/** The top view: following games.jsonl, the rows it shows, sorting, scrolling and layout. No Forge. */
class TopTest {
    @TempDir
    Path dir;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A gauntlet of {@code opponents} matchups, 10 games each. */
    static RunManifest manifest(int opponents) {
        List<DeckRef> refs = new ArrayList<>();
        List<RunManifest.DeckInfo> decks = new ArrayList<>();
        decks.add(new RunManifest.DeckInfo("target", "t", Path.of("/decks/t.txt"), "h", List.of()));
        for (int i = 0; i < opponents; i++) {
            Path p = Path.of("/decks/opp" + i + ".txt");
            refs.add(new DeckRef(p, 1.0));
            decks.add(new RunManifest.DeckInfo("opponent", "opp" + i, p, "h" + i, List.of()));
        }
        BenchConfig config = new BenchConfig(BenchConfig.Mode.GAUNTLET, Format.CONSTRUCTED, 10,
                new DeckRef(Path.of("/decks/t.txt"), 1.0), refs, null, 20, 600, 1L, 2, Path.of("/results"), true,
                0.05, false, null, null);
        return new RunManifest("run1", "0.1", "2.0.16", config, decks);
    }

    /** A game of {@code matchup}: the target wins or loses, after {@code turns} turns (2 per round). */
    static GameRecord game(int id, int matchup, String outcome, int turns) {
        return new GameRecord("run1", id, "gauntlet", "constructed", matchup, "t vs opp" + matchup, id,
                List.of(new PlayerRecord(0, "t", true, true, 0, 10, null, 1, 3, 0),
                        new PlayerRecord(1, "opp" + matchup, false, false, 0, 0, "LifeReachedZero", 0, null, 0)),
                0, outcome, null, turns, 1000, "2.0.16", null);
    }

    private static String line(GameRecord g) throws Exception {
        return JSON.writeValueAsString(g) + "\n";
    }

    private static TopModel.Row row(TopModel.Snapshot s, String opponent) {
        return s.rows().stream().filter(r -> r.opponent().equals(opponent)).findFirst().orElseThrow();
    }

    @Test
    void tailReadsOnlyCompleteLinesAndStartsOverWhenTheFileShrinks() throws Exception {
        Path file = dir.resolve("games.jsonl");
        GamesTail tail = new GamesTail(file);
        assertFalse(tail.poll(), "no file yet");

        String first = line(game(0, 0, GameRecord.WIN, 10));
        String second = line(game(1, 0, GameRecord.LOSS, 12));
        Files.writeString(file, first + second.substring(0, 20));
        assertTrue(tail.poll());
        assertEquals(1, tail.games().size(), "the half-written line waits for its newline");
        Files.writeString(file, second.substring(20) + "not json\n", StandardOpenOption.APPEND);
        assertTrue(tail.poll());
        assertEquals(2, tail.games().size());
        assertEquals(1, tail.badLines());
        assertFalse(tail.poll(), "nothing new");

        Files.writeString(file, first); // rewritten shorter, e.g. by resume
        assertTrue(tail.poll());
        assertEquals(1, tail.games().size());
        assertEquals(0, tail.badLines());
    }

    @Test
    void rowsCarryResultsPlanAndState() {
        List<GameRecord> games = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            games.add(game(i, 0, i < 7 ? GameRecord.WIN : GameRecord.LOSS, 8)); // opp0: all 10 played
        }
        games.add(game(10, 1, GameRecord.LOSS, 20)); // opp1: 1 of 10
        TopModel.Snapshot s = TopModel.compute(manifest(3), games, null, null, 0);

        assertEquals(11, s.played());
        assertEquals(30, s.planned());
        assertFalse(s.finished());
        TopModel.Row opp0 = row(s, "opp0");
        assertEquals(10, opp0.planned());
        assertEquals(7, opp0.wins());
        assertEquals(0.7, opp0.winRate(), 1e-9);
        assertEquals(TopModel.State.DONE, opp0.state());
        assertEquals(TopModel.State.PLAYING, row(s, "opp1").state());
        assertEquals(TopModel.State.QUEUED, row(s, "opp2").state());
        assertEquals(null, row(s, "opp2").winRate());
    }

    private static TopModel.Snapshot threeMatchups() {
        List<GameRecord> games = new ArrayList<>();
        int id = 0;
        // opp0: 4 games, 25% wins, 4 rounds. opp1: 2 games, 100%, 10 rounds. opp2: none.
        for (int i = 0; i < 4; i++) {
            games.add(game(id++, 0, i == 0 ? GameRecord.WIN : GameRecord.LOSS, 8));
        }
        for (int i = 0; i < 2; i++) {
            games.add(game(id++, 1, GameRecord.WIN, 20));
        }
        return TopModel.compute(manifest(3), games, 12.0, System.currentTimeMillis() - 5000, 0);
    }

    private static List<String> order(TopModel.Snapshot s, TopView.Settings view) {
        return TopView.sorted(s.rows(), view).stream().map(TopModel.Row::opponent).toList();
    }

    @Test
    void sortsByGamesWinRateRoundsAndNameWithEmptyRowsLast() {
        TopModel.Snapshot s = threeMatchups();
        TopView.Settings view = TopView.Settings.defaults(false);
        assertEquals(List.of("opp0", "opp1", "opp2"), order(s, view), "games, most first");
        assertEquals(List.of("opp2", "opp1", "opp0"), order(s, view.sortBy(TopView.SortKey.GAMES)), "pressed again");

        TopView.Settings win = view.sortBy(TopView.SortKey.WIN_RATE);
        assertEquals(List.of("opp1", "opp0", "opp2"), order(s, win));
        assertEquals(List.of("opp0", "opp1", "opp2"), order(s, win.reversed()), "no win rate stays last");

        TopView.Settings rounds = view.sortBy(TopView.SortKey.ROUNDS);
        assertEquals(List.of("opp1", "opp0", "opp2"), order(s, rounds));
        assertEquals(List.of("opp0", "opp1", "opp2"), order(s, rounds.sortBy(TopView.SortKey.ROUNDS)));

        assertEquals(List.of("opp0", "opp1", "opp2"), order(s, view.sortBy(TopView.SortKey.NAME)));
        assertEquals(TopView.SortKey.ROUNDS, TopView.SortKey.forKey('r'));
    }

    @Test
    void screenShowsProgressAndMarksTheSortColumn() {
        TopModel.Snapshot s = threeMatchups();
        List<String> lines = TopView.render(s, TopView.Settings.defaults(false).sortBy(TopView.SortKey.WIN_RATE),
                160, 40, System.currentTimeMillis());
        String text = String.join("\n", lines);
        assertTrue(lines.get(0).startsWith("mtgbench top - ") && lines.get(0).contains("run run1"), text);
        assertTrue(lines.get(0).contains("last game 5s ago"), text);
        assertTrue(text.contains("Games: 6 of 30 planned (20.0%)   12.0/min   ETA 2m 00s"), text);
        assertTrue(text.contains("Matchups: 2 playing, 1 queued, 0 done, 0 capped"), text);
        assertTrue(text.contains("WIN%▼"), text);
        // Row: index, name, games, plan, W-L-D, win%, CI, timeouts, errors, rounds, state.
        assertTrue(text.matches("(?s).*\\n\\s+2 opp1\\s+2\\s+10\\s+2-0-0\\s+100\\.0\\s+\\[.*\\]\\s+0\\s+0\\s+10\\.0\\s+playing.*"),
                text);
    }

    @Test
    void longTablesScrollAndLinesFitTheWidth() {
        List<GameRecord> games = IntStream.range(0, 50).mapToObj(i -> game(i, i, GameRecord.WIN, 8)).toList();
        TopModel.Snapshot s = TopModel.compute(manifest(50), games, null, null, 0);
        TopView.Settings view = TopView.Settings.defaults(true).sortBy(TopView.SortKey.ORDER);
        int height = 20;
        List<String> top = TopView.render(s, view, 100, height, 0);
        assertEquals(height, top.size());
        int fit = TopView.tableRows(s, height);
        assertTrue(top.get(height - 1).contains("rows 1-" + fit + " of 50"), top.get(height - 1));

        List<String> scrolled = TopView.render(s, view.scrolledTo(45), 100, height, 0);
        assertTrue(scrolled.get(height - 1).contains("of 50"), scrolled.get(height - 1));
        assertTrue(String.join("\n", scrolled).contains(" opp49 "), "scrolling past the end shows the last page");
        for (String l : scrolled) {
            assertTrue(l.replaceAll("\u001b\\[[0-9;?]*[A-Za-z]", "").length() <= 100, l);
        }
    }

    @Test
    void clipKeepsEscapesAndDurationsRead() {
        assertEquals("\u001b[1mab\u001b[0m", TopView.clip("\u001b[1mabcd", 2));
        assertEquals("abc", TopView.clip("abc", 10));
        assertEquals("45s", TopView.duration(45_000));
        assertEquals("12m 03s", TopView.duration(723_000));
        assertEquals("3h 20m", TopView.duration(12_000_000));
        assertEquals("2d 4h", TopView.duration((2 * 24 + 4) * 3_600_000L));
    }

    @Test
    void batchModePrintsASnapshotOfARunDirectory() throws Exception {
        Path run = Files.createDirectories(dir.resolve("results").resolve("run1"));
        manifest(2).write(run);
        Files.writeString(run.resolve("games.jsonl"), line(game(0, 0, GameRecord.WIN, 8)) + line(game(1, 1,
                GameRecord.LOSS, 9)));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StringWriter err = new StringWriter();
        PrintStream stdout = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        int code;
        try {
            code = BenchCliAccess.execute(new PrintWriter(err, true), "top", "-b", "-n", "1", "-s", "name",
                    run.toString());
        } finally {
            System.setOut(stdout);
        }
        String text = out.toString(StandardCharsets.UTF_8);
        assertEquals(0, code, text + err);
        assertTrue(text.contains("Games: 2 of 20 planned (10.0%)"), text);
        assertTrue(text.contains("OPPONENT▲"), text);
        assertFalse(text.contains("\u001b["), "batch output has no escape codes");

        code = BenchCliAccess.execute(new PrintWriter(err, true), "top", "-b", dir.toString());
        assertEquals(1, code);
        assertTrue(err.toString().contains("is not a run directory"), err.toString());
    }
}
