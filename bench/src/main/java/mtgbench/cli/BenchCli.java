package mtgbench.cli;

import java.nio.file.Files;
import java.nio.file.Path;

import mtgbench.engine.BuildProps;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "mtgbench", mixinStandardHelpOptions = true,
        versionProvider = BenchCli.Version.class,
        description = "Benchmark a Magic: The Gathering deck against opponent decks with Forge's rules engine and AI.",
        subcommands = {RunCommand.class, ValidateCommand.class, ResumeCommand.class, ReportCommand.class,
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
        int code = new CommandLine(new BenchCli())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .execute(args);
        System.exit(code);
    }

    /** Where Forge's card data lives and where Forge may write. Shared by all subcommands. */
    static final class ForgeOptions {
        @Option(names = "--forge-res", paramLabel = "DIR",
                description = "Forge's res/ directory (card data). Default: $FORGE_RES_DIR, ./forge/forge-gui/res, or /opt/forge/res.")
        Path forgeRes;

        @Option(names = "--forge-home", paramLabel = "DIR",
                description = "Writable directory for Forge's preferences and caches. Default: $FORGE_HOME or ~/.cache/mtgbench/forge-home.")
        Path forgeHome;

        Path resolveRes() {
            if (forgeRes != null) {
                return forgeRes;
            }
            String env = System.getenv("FORGE_RES_DIR");
            if (env != null && !env.isBlank()) {
                return Path.of(env);
            }
            for (Path candidate : new Path[] {Path.of("forge/forge-gui/res"), Path.of("../forge/forge-gui/res"),
                    Path.of("/opt/forge/res")}) {
                if (Files.isDirectory(candidate.resolve("cardsfolder"))) {
                    return candidate;
                }
            }
            throw new CommandLine.ParameterException(new CommandLine(new BenchCli()),
                    "Cannot find Forge's card data; pass --forge-res or set FORGE_RES_DIR");
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
