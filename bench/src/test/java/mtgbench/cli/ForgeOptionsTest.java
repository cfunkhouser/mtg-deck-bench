package mtgbench.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;

/** Finding Forge's card data, and how a missing one is reported. Nothing here starts Forge. */
class ForgeOptionsTest {
    @TempDir
    Path dir;

    @Test
    void explicitDirectoryMustHoldCardData() throws Exception {
        BenchCli.ForgeOptions o = new BenchCli.ForgeOptions();
        o.forgeRes = dir;
        BenchCli.SetupException e = assertThrows(BenchCli.SetupException.class, o::resolveRes);
        assertTrue(e.getMessage().contains("--forge-res"), e.getMessage());

        Files.createDirectories(dir.resolve("cardsfolder"));
        assertEquals(dir, o.resolveRes());
    }

    @Test
    void missingCardDataIsAOneLineErrorNotUsageHelp() {
        // A validate run pointed at an empty directory, as in a checkout without the forge submodule.
        CommandLine cli = BenchCli.commandLine();
        StringWriter err = new StringWriter();
        cli.setErr(new PrintWriter(err, true));
        cli.setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF));
        int code = cli.execute("validate", "--forge-res", dir.toString(), "deck.txt");

        assertEquals(BenchCli.EXIT_USAGE, code);
        String text = err.toString();
        assertTrue(text.contains("No Forge card data in " + dir), text);
        assertFalse(text.contains("Usage:"), "no usage help for a setup problem:\n" + text);
        assertEquals(1, text.strip().lines().count(), text);
    }
}
