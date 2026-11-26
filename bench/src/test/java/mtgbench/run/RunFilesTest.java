package mtgbench.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import mtgbench.cli.MergeCommandAccess;
import mtgbench.config.BenchConfig;
import mtgbench.config.BenchConfig.DeckRef;
import mtgbench.config.BenchConfig.Shard;
import mtgbench.config.Format;
import mtgbench.report.Reports;
import mtgbench.schedule.GameSpec;
import mtgbench.stats.GameRecord;
import mtgbench.stats.GameRecord.PlayerRecord;

/** Run files and the logic around them; nothing here starts Forge. */
class RunFilesTest {
    @TempDir
    Path dir;

    static BenchConfig config(Shard shard, long seed, int threads) {
        return new BenchConfig(BenchConfig.Mode.GAUNTLET, Format.COMMANDER, 10,
                new DeckRef(Path.of("/decks/t.txt"), 1.0),
                List.of(new DeckRef(Path.of("/decks/a.txt"), 0.3), new DeckRef(Path.of("/decks/b.dck"), 0.7)),
                null, 20, 600, seed, threads, Path.of("/results"), true, 0.05, false, shard);
    }

    static RunManifest manifest(String runId, Shard shard, long seed, String hashB) {
        return new RunManifest(runId, "0.1", "2.0.16", config(shard, seed, 2), List.of(
                new RunManifest.DeckInfo("target", "t", Path.of("/decks/t.txt"), "h1", List.of()),
                new RunManifest.DeckInfo("opponent", "a", Path.of("/decks/a.txt"), "h2", List.of("careful")),
                new RunManifest.DeckInfo("opponent", "b", Path.of("/decks/b.dck"), hashB, List.of())));
    }

    static GameRecord game(int id, String outcome, int turns) {
        return new GameRecord("r", id, "gauntlet", "commander", id % 2, "t vs a", 42L + id,
                List.of(new PlayerRecord(0, "t", true, true, 0, 30, null, 1, 3, 0),
                        new PlayerRecord(1, "a", false, false, 1, 0, "LifeReachedZero", 0, null, 5)),
                0, outcome, null, turns, 1234, "2.0.16", null);
    }

    @Test
    void manifestRoundTripsThroughYaml() throws IOException {
        RunManifest m = manifest("run1", new Shard(1, 4), 7, "h3");
        m.write(dir);
        assertEquals(m, RunManifest.read(dir));
        assertTrue(Files.readString(dir.resolve(RunManifest.FILE)).contains("/decks/a.txt"), "paths stay plain");
    }

    @Test
    void notARunDirectory() {
        assertThrows(IOException.class, () -> RunManifest.read(dir));
    }

    @Test
    void gamesJsonlRoundTripAndTruncatedLastLine() throws IOException {
        Path f = dir.resolve("games.jsonl");
        Reports.rewriteGames(f, List.of(game(0, GameRecord.WIN, 10), game(1, GameRecord.LOSS, 12)));
        Files.writeString(f, Files.readString(f) + "{\"runId\":\"r\",\"gameId\":2,\"mo");   // killed mid-write

        Reports.GamesRead read = Reports.readGames(f);
        assertEquals(2, read.games().size());
        assertEquals(1, read.truncatedLines());
        assertEquals(game(1, GameRecord.LOSS, 12), read.games().get(1));
        assertNull(read.games().get(0).players().get(1).firstCommanderCastRound());

        Reports.rewriteGames(f, read.games());
        try (Reports.GamesJsonl out = new Reports.GamesJsonl(f, true)) {
            out.append(game(2, GameRecord.WIN, 8));
        }
        Reports.GamesRead again = Reports.readGames(f);
        assertEquals(0, again.truncatedLines());
        assertEquals(List.of(0, 1, 2), again.games().stream().map(GameRecord::gameId).toList());
    }

    @Test
    void brokenLineInTheMiddleIsAnError() throws IOException {
        Path f = dir.resolve("games.jsonl");
        Files.writeString(f, "{broken\n" + Reports.JSON.writeValueAsString(game(0, GameRecord.WIN, 1)) + "\n");
        assertThrows(IOException.class, () -> Reports.readGames(f));
    }

    @Test
    void shardsPartitionTheSchedule() {
        List<GameSpec> full = BenchRun.schedule(config(null, 5, 1));
        assertEquals(20, full.size());
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            for (GameSpec s : BenchRun.schedule(config(new Shard(i, 3), 5, 1))) {
                assertTrue(seen.add(s.gameId()), "game in two shards: " + s.gameId());
                assertEquals(full.get(s.gameId()), s, "shards use the full run's specs and seeds");
            }
        }
        assertEquals(20, seen.size());
    }

    @Test
    void shardParsing() {
        assertEquals(new Shard(2, 4), Shard.parse("2/4"));
        assertThrows(IllegalArgumentException.class, () -> Shard.parse("4/4"));
        assertThrows(IllegalArgumentException.class, () -> Shard.parse("x"));
    }

    @Test
    void mergeRules() {
        RunManifest a = manifest("a", new Shard(0, 2), 7, "h3");
        assertNull(MergeCommandAccess.incompatibility(a, manifest("b", new Shard(1, 2), 7, "h3")));
        assertNotNull(MergeCommandAccess.incompatibility(a, manifest("b", new Shard(1, 2), 8, "h3")), "seed");
        assertNotNull(MergeCommandAccess.incompatibility(a, manifest("b", new Shard(1, 2), 7, "other")), "deck file");

        GameRecord g = game(3, GameRecord.WIN, 10);
        GameRecord sameOtherRun = new GameRecord("other", 3, g.mode(), g.format(), g.matchup(), g.matchupName(),
                g.seed(), g.players(), g.winnerSeat(), g.outcome(), g.stopReason(), g.turns(), 9999, g.forgeVersion(),
                null);
        assertTrue(MergeCommandAccess.sameGame(g, sameOtherRun));
        assertTrue(!MergeCommandAccess.sameGame(g, game(3, GameRecord.WIN, 11)));
    }
}
