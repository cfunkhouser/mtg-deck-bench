package mtgbench.cli;

import java.io.PrintWriter;

import picocli.CommandLine;

/** Test access to the configured command line, for tests outside this package. */
public final class BenchCliAccess {
    private BenchCliAccess() {}

    /** Runs mtgbench with {@code args}, errors to {@code err}, no colour. @return the exit code */
    public static int execute(PrintWriter err, String... args) {
        CommandLine cli = BenchCli.commandLine();
        cli.setErr(err);
        cli.setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF));
        return cli.execute(args);
    }
}
