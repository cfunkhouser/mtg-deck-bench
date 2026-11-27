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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import forge.deck.DeckSection;
import mtgbench.config.Format;
import mtgbench.deck.DeckLoader;
import mtgbench.deck.LoadedDeck;
import mtgbench.fetch.ArchidektTestDecks;
import mtgbench.fetch.MtgjsonTestDecks;
import mtgbench.testsupport.ForgeTestSupport;
import picocli.CommandLine;

/** The fetch command end to end, against a local stand-in for Archidekt's API. */
class FetchCommandTest {
    @TempDir
    Path dir;

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();
    private final StringWriter err = new StringWriter();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    @BeforeEach
    void startServer() throws Exception {
        Map<String, String> decks = Map.of(
                "/api/decks/42/", ArchidektTestDecks.constructed(),
                "/api/decks/7/", ArchidektTestDecks.commander(),
                "/api/v5/decks/Goblins_TST.json", MtgjsonTestDecks.goblins("Goblin Gang"));
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(path + " " + exchange.getRequestHeaders().getFirst("User-Agent"));
            String body = decks.get(path);
            byte[] bytes = (body != null ? body : "{\"detail\": \"Not found.\"}").getBytes(StandardCharsets.UTF_8);
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

    private int fetch(String... args) {
        CommandLine cli = BenchCli.commandLine();
        cli.setErr(new PrintWriter(err, true));
        cli.setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF));
        List<String> all = new ArrayList<>(List.of("fetch",
                "--archidekt-api", "http://127.0.0.1:" + server.getAddress().getPort() + "/api/",
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

    @Test
    void fetchesWritesAndValidates() throws Exception {
        Path deck = dir.resolve("decks/burn.txt");
        int code = fetch("https://archidekt.com/decks/42/test-burn", deck.toString());

        assertEquals(0, code, output() + err);
        assertTrue(output().contains("Wrote " + deck + " (Test  Burn, 60 main, 6 sideboard)"), output());
        assertTrue(output().contains("wrote 2 card(s) from categories not counted in the deck to the sideboard:"
                + " Maybeboard"), output());
        assertTrue(output().contains("OK    " + deck), output());
        assertEquals(1, requests.size());
        assertTrue(requests.get(0).startsWith("/api/decks/42/ mtgbench/"), requests.toString());

        ForgeTestSupport.init();
        LoadedDeck loaded = new DeckLoader(Format.CONSTRUCTED, true).load(deck);
        assertEquals(60, loaded.deck().getMain().countAll());
        assertEquals(6, loaded.deck().get(DeckSection.Sideboard).countAll());
    }

    @Test
    void commanderDeckIsCheckedAsCommanderAndLeavesOutTheMaybeboard() throws Exception {
        Path deck = dir.resolve("goblins.txt");
        // 42 cards is too few for Commander, which shows the deck was checked as one.
        assertEquals(BenchCli.EXIT_DECK, fetch("7", deck.toString()), output() + err);
        assertTrue(output().contains("should have at least"), output());
        assertTrue(output().contains("left out 1 card(s) from categories not counted in the deck: Maybeboard"
                + " (--maybeboard keeps them in the sideboard)"), output());
        assertTrue(Files.exists(deck), "the deck is saved even when it doesn't validate");
        assertFalse(Files.readString(deck).contains("Goblin Guide"));

        assertEquals(0, fetch("--force", "--maybeboard", "--no-legality-check", "7", deck.toString()),
                output() + err);
        assertTrue(output().contains("commander: Krenko, Mob Boss"), output());
        assertTrue(Files.readString(deck).contains("\nSideboard\n1 Goblin Guide\n"), Files.readString(deck));
    }

    @Test
    void maybeboardDefaultFollowsTheFormatChecked() throws Exception {
        Path deck = dir.resolve("goblins.txt");
        // Checked as constructed, the Commander deck keeps its Maybeboard in the sideboard.
        fetch("-f", "constructed", "--no-legality-check", "7", deck.toString());
        assertTrue(Files.readString(deck).contains("1 Goblin Guide"), Files.readString(deck));
        // And a constructed deck checked as Commander leaves it out.
        fetch("--force", "-f", "commander", "--no-legality-check", "42", deck.toString());
        assertFalse(Files.readString(deck).contains("Shock"), Files.readString(deck));
        // --no-maybeboard leaves it out of a constructed deck.
        fetch("--force", "--no-maybeboard", "--no-validate", "42", deck.toString());
        assertFalse(Files.readString(deck).contains("Shock"), Files.readString(deck));
    }

    @Test
    void fetchesMtgjsonDecks() throws Exception {
        Path deck = dir.resolve("goblins.txt");
        assertEquals(0, fetch("--no-validate", "mtgjson:Goblins_TST", deck.toString()), output() + err);
        assertTrue(output().contains("(Goblin Gang, commander: Krenko, Mob Boss, 40 main, 0 sideboard)"), output());
        assertTrue(Files.readString(deck).contains("# From https://mtgjson.com/api/v5/decks/Goblins_TST.json\n"),
                Files.readString(deck));
        assertTrue(requests.get(0).startsWith("/api/v5/decks/Goblins_TST.json mtgbench/"), requests.toString());

        assertEquals(BenchCli.EXIT_USAGE, fetch("--no-validate", "mtgjson:Missing_TST", dir.resolve("m.txt").toString()));
        assertTrue(err.toString().contains("MTGJSON has no deck Missing_TST (HTTP 404)"), err.toString());
    }

    @Test
    void refusesToOverwriteWithoutForce() throws Exception {
        Path deck = Files.writeString(dir.resolve("mine.txt"), "4 Lightning Bolt\n");
        assertEquals(BenchCli.EXIT_USAGE, fetch("42", deck.toString()));
        assertTrue(err.toString().contains("already exists; pass --force"), err.toString());
        assertEquals("4 Lightning Bolt\n", Files.readString(deck));
        assertTrue(requests.isEmpty(), "nothing is downloaded");
    }

    @Test
    void reportsUnknownDecksAndUrls() {
        Path deck = dir.resolve("x.txt");
        assertEquals(BenchCli.EXIT_USAGE, fetch("https://archidekt.com/decks/404/gone", deck.toString()));
        assertTrue(err.toString().contains("Archidekt has no public deck 404 (HTTP 404)"), err.toString());
        assertFalse(err.toString().contains("Usage:"), err.toString());
        assertFalse(Files.exists(deck));

        assertEquals(BenchCli.EXIT_USAGE, fetch("https://moxfield.com/decks/abc", deck.toString()));
        assertTrue(err.toString().contains("Not an Archidekt or MTGJSON deck"), err.toString());
    }

    @Test
    void noValidateSkipsForge() throws Exception {
        Path deck = dir.resolve("burn.txt");
        // A Forge directory that doesn't exist is fine when nothing is validated.
        CommandLine cli = BenchCli.commandLine();
        cli.setErr(new PrintWriter(err, true));
        PrintStream stdout = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        int code;
        try {
            code = cli.execute("fetch", "--archidekt-api", "http://127.0.0.1:" + server.getAddress().getPort() + "/api",
                    "--forge-res", dir.resolve("nowhere").toString(), "--no-validate", "42", deck.toString());
        } finally {
            System.setOut(stdout);
        }
        assertEquals(0, code, output() + err);
        assertFalse(output().contains("OK "), output());
        assertTrue(Files.readString(deck).contains("\nDeck\n2 Bonecrusher Giant\n4 Fire // Ice\n"), Files.readString(deck));
    }
}
