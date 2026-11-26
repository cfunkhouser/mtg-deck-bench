package mtgbench.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import mtgbench.config.BenchConfig;
import mtgbench.config.Format;
import picocli.CommandLine;

/** Config merging only; nothing here touches Forge. */
class RunCommandConfigTest {
    @TempDir
    Path dir;

    private static BenchConfig resolve(String... args) throws Exception {
        RunCommand cmd = new RunCommand();
        new CommandLine(cmd).setCaseInsensitiveEnumValuesAllowed(true).parseArgs(args);
        return cmd.resolveConfig();
    }

    @Test
    void yamlFileWithRelativePathsAndFlagOverrides() throws Exception {
        Path cfg = dir.resolve("bench.yaml");
        Files.writeString(cfg, """
                format: Commander
                games: 50
                target: decks/t.txt
                opponents:
                  - path: decks/a.txt
                    weight: 0.3
                  - path: decks/b.dck
                limits:
                  maxRounds: 15
                seed: 7
                threads: 2
                """);
        BenchConfig c = resolve("-c", cfg.toString(), "-n", "10");
        assertEquals(Format.COMMANDER, c.format());
        assertEquals(10, c.games(), "flag beats file");
        assertEquals(dir.resolve("decks/t.txt"), c.target().path());
        assertEquals(dir.resolve("decks/a.txt"), c.opponents().get(0).path());
        assertEquals(0.3, c.opponents().get(0).weight());
        assertEquals(1.0, c.opponents().get(1).weight());
        assertEquals(15, c.maxRounds());
        assertEquals(BenchConfig.DEFAULT_TIMEOUT_COMMANDER_SEC, c.gameTimeoutSec());
        assertEquals(7L, c.seed());
        assertEquals(2, c.threads());
        assertTrue(c.checkLegality());
    }

    @Test
    void flagsOnlyWithWeightsAndDeterministic() throws Exception {
        BenchConfig c = resolve("-T", "t.txt", "-O", "a.txt", "-O", "b.txt", "--weights", "2,1",
                "--deterministic", "--no-legality-check");
        assertEquals(Format.CONSTRUCTED, c.format());
        assertEquals(BenchConfig.DEFAULT_GAMES, c.games());
        assertEquals(1, c.threads());
        assertEquals(2.0, c.opponents().get(0).weight());
        assertEquals(BenchConfig.DEFAULT_TIMEOUT_CONSTRUCTED_SEC, c.gameTimeoutSec());
        assertEquals(false, c.checkLegality());
    }

    @Test
    void rejectsBadInput() {
        assertThrows(CommandLine.ParameterException.class, () -> resolve("-O", "a.txt"));
        assertThrows(CommandLine.ParameterException.class, () -> resolve("-T", "t.txt"));
        assertThrows(CommandLine.ParameterException.class,
                () -> resolve("-T", "t.txt", "-O", "a.txt", "--weights", "1,2"));
        assertThrows(CommandLine.ParameterException.class,
                () -> resolve("-T", "t.txt", "-O", "a.txt", "--deterministic", "-t", "4"));
    }

    @Test
    void targetCiFromFlagsAndFile() throws Exception {
        BenchConfig c = resolve("-T", "t.txt", "-O", "a.txt", "--target-ci", "0.05");
        assertEquals(BenchConfig.DEFAULT_GAMES, c.games(), "--games stays the first batch");
        assertEquals(0.05, c.adaptive().targetCi());
        assertEquals(BenchConfig.Adaptive.defaultMaxGames(0.05, 2), c.adaptive().maxGames());
        assertEquals(c.adaptive().maxGames(), c.scheduledGames());

        Path cfg = dir.resolve("bench.yaml");
        Files.writeString(cfg, "target: t.txt\nopponents: [{path: a.txt}, {path: b.txt}]\nmode: pod\n"
                + "games: 30\ntargetCi: 0.1\nmaxGames: 100\n");
        BenchConfig pod = resolve("-c", cfg.toString());
        assertEquals(0.1, pod.adaptive().targetCi());
        assertEquals(102, pod.adaptive().maxGames(), "rounded up to whole 3-seat rotations");

        assertNull(resolve("-T", "t.txt", "-O", "a.txt").adaptive(), "fixed game count by default");
    }

    @Test
    void targetCiRejectsBadInput() {
        assertThrows(CommandLine.ParameterException.class,
                () -> resolve("-T", "t.txt", "-O", "a.txt", "--max-games", "500"), "--max-games alone");
        assertThrows(CommandLine.ParameterException.class,
                () -> resolve("-T", "t.txt", "-O", "a.txt", "--target-ci", "5"), "percent instead of a share");
        assertThrows(CommandLine.ParameterException.class,
                () -> resolve("-T", "t.txt", "-O", "a.txt", "--target-ci", "0.05", "-n", "200", "--max-games", "100"));
        assertThrows(CommandLine.ParameterException.class,
                () -> resolve("-T", "t.txt", "-O", "a.txt", "--target-ci", "0.05", "--shard", "0/2"));
    }

    @Test
    void unknownYamlKeyIsAnError() throws Exception {
        Path cfg = dir.resolve("typo.yaml");
        Files.writeString(cfg, "target: t.txt\nopponents: [{path: a.txt}]\ngmaes: 5\n");
        assertThrows(Exception.class, () -> resolve("-c", cfg.toString()));
    }
}
