package mtgbench.cli;

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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import picocli.CommandLine;

/** The target deck can also be an opponent (its mirror match); different decks can't share a name. */
class MirrorMatchTest {
    private static final Path BURN = Path.of("..", "examples", "decks", "mono-red-burn.txt");
    private static final Path STOMPY = Path.of("..", "examples", "decks", "mono-green-stompy.txt");

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private int mtgbench(String... args) {
        CommandLine cli = BenchCli.commandLine();
        cli.setErr(new PrintWriter(new StringWriter(), true));
        List<String> all = new ArrayList<>(List.of(args));
        all.addAll(List.of("--forge-res", System.getProperty("mtgbench.test.forgeRes"),
                "--forge-home", System.getProperty("mtgbench.test.forgeHome")));
        PrintStream stdout = System.out;
        PrintStream stderr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            return cli.execute(all.toArray(String[]::new));
        } finally {
            System.setOut(stdout);
            System.setErr(stderr);
        }
    }

    private String output() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private Path onlyRun(Path results) throws Exception {
        try (Stream<Path> s = Files.list(results)) {
            List<Path> runs = s.toList();
            assertEquals(1, runs.size(), runs.toString());
            return runs.get(0);
        }
    }

    @Test
    void targetPlaysItsOwnMirrorAndTheRunResumes() throws Exception {
        Path results = dir.resolve("results");
        int code = mtgbench("run", "-T", BURN.toString(), "-O", BURN.toString(), "-O", STOMPY.toString(),
                "-n", "2", "--deterministic", "--seed", "7", "--out", results.toString());
        assertEquals(0, code, output());
        Path run = onlyRun(results);

        JsonNode summary = new ObjectMapper().readTree(run.resolve("summary.json").toFile());
        List<String> opponents = new ArrayList<>();
        summary.path("matchups").forEach(m -> opponents.add(m.path("opponent").asText()));
        assertEquals(List.of("mono-red-burn (mirror)", "mono-green-stompy"), opponents, summary.toPrettyString());
        assertTrue(Files.readString(run.resolve("games.jsonl")).contains("\"mono-red-burn vs mono-red-burn (mirror)\"")
                || Files.readString(run.resolve("games.jsonl")).contains("\"mono-red-burn (mirror) vs mono-red-burn\""),
                Files.readString(run.resolve("games.jsonl")));
        // Warnings about the deck are printed once, not once per seat.
        assertFalse(output().contains("Two decks") || output().contains("same name"), output());

        // Resuming reloads the decks and must name them the same way.
        out.reset();
        assertEquals(0, mtgbench("resume", run.toString()), output());
    }

    @Test
    void differentDecksWithTheSameNameAreStillRejected() throws Exception {
        Path a = Files.createDirectories(dir.resolve("a")).resolve("burn.txt");
        Path b = Files.createDirectories(dir.resolve("b")).resolve("burn.txt");
        Files.copy(BURN, a);
        // Another file with the same name: different bytes, so not the target's mirror.
        Files.writeString(b, "# my version\n" + Files.readString(BURN));

        int code = mtgbench("run", "-T", a.toString(), "-O", b.toString(), "-O", STOMPY.toString(), "-n", "2",
                "--out", dir.resolve("results").toString());
        assertEquals(BenchCli.EXIT_DECK, code, output());
        String text = output();
        assertTrue(text.contains("Different decks have the same name (burn)"), text);
        assertFalse(text.contains("mono-green-stompy"), "only the clashing decks are named:\n" + text);
    }
}
