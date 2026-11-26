package mtgbench.run;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Forge writes a lot of noise to stdout and stderr (card database warnings, AI traces). This sends
 * both to a log file and hands back the real console for our own output.
 */
public final class ForgeOutput {
    private ForgeOutput() {}

    /** @return the original stdout, for messages meant for the user */
    public static PrintStream redirectTo(Path logFile) throws IOException {
        Files.createDirectories(logFile.getParent());
        PrintStream console = System.out;
        PrintStream log = new PrintStream(new FileOutputStream(logFile.toFile(), true), true, StandardCharsets.UTF_8);
        System.setOut(log);
        System.setErr(log);
        return console;
    }
}
