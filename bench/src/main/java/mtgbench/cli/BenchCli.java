package mtgbench.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import mtgbench.engine.BuildProps;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "mtgbench", mixinStandardHelpOptions = true,
        versionProvider = BenchCli.Version.class,
        description = "Benchmark a Magic: The Gathering deck against opponent decks with Forge's rules engine and AI.",
        subcommands = {RunCommand.class, ValidateCommand.class, FetchCommand.class, PreconsCommand.class, ResumeCommand.class, ReportCommand.class,
                MergeCommand.class, ReplayCommand.class},
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {
            "0:Success",
            "1:Bad arguments or configuration",
            "2:A deck file has problems",
            "3:Too many games failed (see --max-error-rate)",
            "4:replay of a deterministic run gave a different result"})
public final class BenchCli {
    public static final int EXIT_USAGE = 1;
    public static final int EXIT_DECK = 2;

    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }

    /** The configured command line, as {@link #main} runs it. */
    static CommandLine commandLine() {
        CommandLine cli = new CommandLine(new BenchCli())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setExecutionExceptionHandler(BenchCli::handleSetupProblem);
        // picocli exits with 2 on bad arguments, which is EXIT_DECK here. Each command reports its own.
        cli.getCommandSpec().exitCodeOnInvalidInput(EXIT_USAGE);
        cli.getSubcommands().values().forEach(sub -> sub.getCommandSpec().exitCodeOnInvalidInput(EXIT_USAGE));
        return cli;
    }

    /**
     * A problem with the environment rather than the arguments, such as missing card data. It is
     * printed as a plain message (no usage help) and exits with {@link #EXIT_USAGE}.
     */
    static final class SetupException extends RuntimeException {
        SetupException(String message) {
            super(message);
        }
    }

    private static int handleSetupProblem(Exception e, CommandLine cmd, CommandLine.ParseResult parsed)
            throws Exception {
        if (e instanceof SetupException) {
            cmd.getErr().println(cmd.getColorScheme().errorText(e.getMessage()));
            return EXIT_USAGE;
        }
        throw e;
    }

    /** Where Forge's card data lives and where Forge may write. Shared by all subcommands. */
    static final class ForgeOptions {
        @Option(names = "--forge-res", paramLabel = "DIR",
                description = "Forge's res/ directory (card data). Default: $FORGE_RES_DIR, ./forge/forge-gui/res, or /opt/forge/res.")
        Path forgeRes;

        @Option(names = "--forge-home", paramLabel = "DIR",
                description = "Writable directory for Forge's preferences and caches. Default: $FORGE_HOME or ~/.cache/mtgbench/forge-home.")
        Path forgeHome;

        /**
         * Finds Forge's card data. Commands call this before redirecting output to a log file, so
         * that the error reaches the user.
         *
         * @throws SetupException if there is none
         */
        Path resolveRes() {
            if (forgeRes != null) {
                return requireCardData(forgeRes, "--forge-res");
            }
            String env = System.getenv("FORGE_RES_DIR");
            if (env != null && !env.isBlank()) {
                return requireCardData(Path.of(env), "FORGE_RES_DIR");
            }
            for (Path candidate : DEFAULT_RES) {
                if (Files.isDirectory(candidate.resolve("cardsfolder"))) {
                    return candidate;
                }
            }
            throw new SetupException("Cannot find Forge's card data (looked in "
                    + String.join(", ", Arrays.stream(DEFAULT_RES).map(Path::toString).toList())
                    + "). In a source checkout, run 'mise run setup' (or scripts/build-forge.sh) to fetch and build"
                    + " Forge; otherwise pass --forge-res or set FORGE_RES_DIR.");
        }

        private static final Path[] DEFAULT_RES = {
            Path.of("forge/forge-gui/res"), Path.of("../forge/forge-gui/res"), Path.of("/opt/forge/res")};

        private static Path requireCardData(Path dir, String source) {
            if (!Files.isDirectory(dir.resolve("cardsfolder"))) {
                throw new SetupException("No Forge card data in " + dir + " (from " + source
                        + "): expected a res/ directory with cardsfolder/ in it.");
            }
            return dir;
        }

        Path resolveHome() {
            if (forgeHome != null) {
                return forgeHome;
            }
            String env = System.getenv("FORGE_HOME");
            if (env != null && !env.isBlank()) {
                return Path.of(env);
            }
            return Path.of(System.getProperty("user.home"), ".cache", "mtgbench", "forge-home");
        }
    }

    static final class Version implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {"mtgbench " + BuildProps.get("mtgbench.version")
                    + " (Forge " + BuildProps.get("forge.version") + ")"};
        }
    }
}
