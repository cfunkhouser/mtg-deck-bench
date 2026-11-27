package mtgbench.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import mtgbench.fetch.MtgjsonTestDecks;
import picocli.CommandLine;

/** The precons command end to end, against a local stand-in for MTGJSON. */
class PreconsCommandTest {
    @TempDir
    Path dir;

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();
    private final StringWriter err = new StringWriter();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    @BeforeEach
    void startServer() throws Exception {
        Map<String, String> files = MtgjsonTestDecks.files();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath().replaceFirst("^/api/v5/", "");
            requests.add(path);
            String body = files.get(path);
            byte[] bytes = (body != null ? body : "Not found").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(body != null ? 200 : 404, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private int precons(Path target, String... args) {
        CommandLine cli = BenchCli.commandLine();
        cli.setErr(new PrintWriter(err, true));
        cli.setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF));
        List<String> all = new ArrayList<>(List.of("precons", "--out", target.toString(), "--delay-ms", "0",
                "--mtgjson-api", "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v5/",
                "--forge-res", System.getProperty("mtgbench.test.forgeRes"),
                "--forge-home", System.getProperty("mtgbench.test.forgeHome")));
        all.addAll(List.of(args));
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

    private static List<String> txtFiles(Path d) throws Exception {
        try (Stream<Path> s = Files.list(d)) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".txt")).sorted().toList();
        }
    }

    @Test
    void writesOneListPerDistinctDeckAndAnIndex() throws Exception {
        Path target = dir.resolve("precons");
        assertEquals(0, precons(target, "--no-validate"), output() + err);

        // The Collector's Edition has the same cards, and the Intro Pack is another type.
        assertEquals(List.of("tst-elf-army.txt", "tst-goblin-gang.txt"), txtFiles(target));
        assertFalse(requests.contains("decks/Intro_TST.json"), requests.toString());
        String goblins = Files.readString(target.resolve("tst-goblin-gang.txt"));
        assertTrue(goblins.startsWith("""
                # Goblin Gang
                # TST, released 2024-02-09
                # From https://mtgjson.com/api/v5/decks/Goblins_TST.json
                # Source: https://magic.wizards.com/goblins

                Commander
                1 Krenko, Mob Boss
                """), goblins);

        String index = Files.readString(target.resolve(PreconsCommand.INDEX));
        assertTrue(index.contains("data version 5.3.0+test"), index);
        // Oldest first; pipes in names are escaped.
        assertTrue(index.indexOf("Elf \\| Army") < index.indexOf("Goblin Gang"), index);
        assertTrue(index.contains("Goblin Gang (also sold as: Goblin Gang Collector's Edition (TST, 2024)) | Krenko, Mob Boss"
                + " | [tst-goblin-gang.txt](tst-goblin-gang.txt) |"), index);
        assertTrue(output().contains("same cards as Goblin Gang, not written again"), output());
    }

    @Test
    void regeneratingRemovesListsMtgjsonNoLongerHas() throws Exception {
        Path target = dir.resolve("precons");
        assertEquals(0, precons(target, "--no-validate"), output() + err);
        Files.writeString(target.resolve("tst-retired-deck.txt"), "1 Mountain\n");
        assertEquals(0, precons(target, "--no-validate"), output() + err);
        assertEquals(List.of("tst-elf-army.txt", "tst-goblin-gang.txt"), txtFiles(target));
        assertTrue(output().contains("removed 1 lists MTGJSON no longer has"), output());
    }

    @Test
    void refusesADirectoryItDidNotMake() throws Exception {
        Path mine = Files.createDirectories(dir.resolve("mine"));
        Files.writeString(mine.resolve("my-deck.txt"), "4 Lightning Bolt\n");
        assertEquals(BenchCli.EXIT_USAGE, precons(mine, "--no-validate"));
        assertTrue(err.toString().contains("pass --force"), err.toString());
        assertTrue(requests.isEmpty(), requests.toString());

        assertEquals(0, precons(mine, "--no-validate", "--force"), output() + err);
        assertTrue(Files.exists(mine.resolve("my-deck.txt")), "a forced run never deletes files it didn't make");
        assertTrue(Files.exists(mine.resolve("tst-goblin-gang.txt")));
    }

    @Test
    void checksEveryDeckAndRecordsTheResult() throws Exception {
        Path target = dir.resolve("precons");
        assertEquals(0, precons(target), output() + err);
        String index = Files.readString(target.resolve(PreconsCommand.INDEX));
        // The test decks are far too small for Commander: the problem is recorded, not fatal.
        assertTrue(index.contains("0 of 2 pass"), index);
        assertTrue(index.contains("should have at least"), index);
        assertTrue(output().contains("0 of 2 pass validation"), output());
    }
}
