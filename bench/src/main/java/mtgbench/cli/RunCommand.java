package mtgbench.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import mtgbench.config.BenchConfig;
import mtgbench.config.BenchConfig.DeckRef;
import mtgbench.config.BenchConfig.Mode;
import mtgbench.config.ConfigFile;
import mtgbench.config.Format;
import mtgbench.deck.LoadedDeck;
import mtgbench.engine.ForgeBootstrap;
import mtgbench.engine.BuildProps;
import mtgbench.run.BenchRun;
import mtgbench.run.RunManifest;
import mtgbench.run.RunSupport;
import mtgbench.run.ForgeOutput;
import mtgbench.stats.Summary;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(name = "run", mixinStandardHelpOptions = true,
        description = "Play the target deck against each opponent deck and report win rates and a benchmark score.")
public final class RunCommand implements Callable<Integer> {
    @Spec
    CommandSpec spec;

    @Mixin
    BenchCli.ForgeOptions forge = new BenchCli.ForgeOptions();

    @Option(names = {"-c", "--config"}, paramLabel = "FILE", description = "YAML config file; flags override it.")
    Path configFile;

    @Option(names = {"-T", "--target"}, paramLabel = "DECK", description = "The deck to benchmark.")
    Path target;

    @Option(names = {"-O", "--opponent"}, paramLabel = "DECK", description = "An opponent deck (repeat for several).")
    List<Path> opponents = new ArrayList<>();

    @Option(names = "--weights", split = ",", paramLabel = "W",
            description = "Metagame weights for the opponents, in the same order (default: equal).")
    List<Double> weights;

    @Option(names = {"-n", "--games"}, description = "Games per opponent; in pod mode, games in total (default: "
            + BenchConfig.DEFAULT_GAMES + ").")
    Integer games;

    @Option(names = "--target-ci", paramLabel = "HALF_WIDTH",
            description = "Keep adding games until every matchup's 95%% CI is within ±HALF_WIDTH, e.g. 0.05 for "
                    + "±5 percentage points. --games is then the first batch.")
    Double targetCi;

    @Option(names = "--max-games", description = "With --target-ci: stop a matchup at this many games even if its CI "
            + "is still wider (pod: games in total; default: what a 50%% matchup needs, plus 25%%).")
    Integer maxGames;

    @Option(names = {"-t", "--threads"}, description = "Games played at once (default: number of CPUs).")
    Integer threads;

    @Option(names = "--deterministic",
            description = "Play one game at a time so every game can be reproduced exactly from its seed.")
    boolean deterministic;

    @Option(names = "--seed", description = "Run seed (default: random; always recorded).")
    Long seed;

    @Option(names = "--mode", description = "gauntlet (default: the target plays each opponent 1v1) or pod "
            + "(the target and all opponents at one free-for-all table).")
    Mode mode;

    @Option(names = {"-f", "--format"}, description = "constructed (default) or commander.")
    Format format;

    @Option(names = "--max-rounds", description = "End a game as a timeout after this many rounds (default: "
            + BenchConfig.DEFAULT_MAX_ROUNDS + ").")
    Integer maxRounds;

    @Option(names = "--game-timeout", paramLabel = "SECONDS",
            description = "Wall-clock limit per game (default: 120, or 600 for commander).")
    Integer gameTimeout;

    @Option(names = "--ai-profile", description = "Forge AI profile for every player (default: Forge's default).")
    String aiProfile;

    @Option(names = {"-o", "--out"}, paramLabel = "DIR", description = "Results directory (default: ./results).")
    Path output;

    @Option(names = "--no-legality-check", description = "Skip format legality checks (unknown cards are still rejected).")
    boolean noLegalityCheck;

    @Option(names = "--max-error-rate", description = "Exit with code 3 if more than this share of games fail (default: 0.05).")
    Double maxErrorRate;

    @Option(names = "--game-logs", description = "Write Forge's full log of every game to logs/games/.")
    boolean gameLogs;

    @Option(names = "--shard", paramLabel = "I/K",
            description = "Play only games with id % K == I (0-based), e.g. 0/4. Combine shard runs with 'merge'.")
    String shard;

    @Override
    public Integer call() throws Exception {
        BenchConfig config = resolveConfig();
        // Before creating the run directory and sending output to its log, so a setup problem is shown.
        Path res = forge.resolveRes();
        Path home = forge.resolveHome();
        String runId = RunSupport.newRunId("");
        Path runDir = Files.createDirectories(config.output().resolve(runId));

        PrintStream console = ForgeOutput.redirectTo(runDir.resolve("logs").resolve("forge.log"));
        console.println("Run " + runId + " → " + runDir);
        console.println("Seed " + config.seed() + RunSupport.reproducibilityNote(config));
        if (!RunSupport.startForge(res, home, config.aiProfile(), console)) {
            return BenchCli.EXIT_USAGE;
        }
        List<LoadedDeck> decks = RunSupport.loadDecks(config, console);
        if (decks == null) {
            return BenchCli.EXIT_DECK;
        }
        RunManifest manifest = RunManifest.of(runId, BuildProps.get("mtgbench.version"),
                ForgeBootstrap.forgeVersion(), config, decks);
        manifest.write(runDir);

        Summary summary = new BenchRun(manifest, config, runDir, decks, console).execute(List.of());
        return RunSupport.finish(summary, config, runDir, console);
    }

    /** Merges defaults, the YAML file and flags (flags win). */
    BenchConfig resolveConfig() throws IOException {
        ConfigFile file = new ConfigFile();
        Path base = Path.of("");
        if (configFile != null) {
            file = ConfigFile.read(configFile);
            base = configFile.toAbsolutePath().getParent();
        }

        Path targetPath = target != null ? target : file.target != null ? base.resolve(file.target) : null;
        if (targetPath == null) {
            throw new ParameterException(spec.commandLine(), "No target deck: use --target or 'target:' in the config");
        }

        List<DeckRef> opps = new ArrayList<>();
        if (!opponents.isEmpty()) {
            if (weights != null && weights.size() != opponents.size()) {
                throw new ParameterException(spec.commandLine(), "--weights needs one value per --opponent");
            }
            for (int i = 0; i < opponents.size(); i++) {
                opps.add(new DeckRef(opponents.get(i).toAbsolutePath(), weights != null ? weights.get(i) : 1.0));
            }
        } else {
            for (ConfigFile.Opponent o : file.opponents) {
                if (o.path == null) {
                    throw new ParameterException(spec.commandLine(), "An opponent in the config has no 'path'");
                }
                opps.add(new DeckRef(base.resolve(o.path).toAbsolutePath(), o.weight != null ? o.weight : 1.0));
            }
        }
        if (opps.isEmpty()) {
            throw new ParameterException(spec.commandLine(), "No opponent decks: use --opponent or 'opponents:' in the config");
        }

        Format fmt = first(format, file.format, Format.CONSTRUCTED);
        Mode md = first(mode, file.mode, Mode.GAUNTLET);
        int gameCount = first(games, file.games, BenchConfig.DEFAULT_GAMES);
        if (md == Mode.POD) {
            if (opps.size() < 2) {
                throw new ParameterException(spec.commandLine(), "pod mode needs at least two opponent decks");
            }
            int rounded = mtgbench.schedule.Scheduler.podGames(opps.size(), gameCount);
            if (rounded != gameCount) {
                System.out.printf("Note: %d games rounded up to %d so every seat is played equally often.%n",
                        gameCount, rounded);
                gameCount = rounded;
            }
        }
        BenchConfig.Adaptive adaptive = null;
        Double ci = first(targetCi, file.targetCi);
        Integer cap = first(maxGames, file.maxGames);
        if (ci == null && cap != null) {
            throw new ParameterException(spec.commandLine(), "--max-games only applies with --target-ci");
        }
        if (ci != null) {
            int step = md == Mode.POD ? opps.size() + 1 : 2;
            if (!(ci > 0 && ci < 0.5)) {
                throw new ParameterException(spec.commandLine(),
                        "--target-ci is a half-width between 0 and 0.5, e.g. 0.05 for ±5 percentage points");
            }
            if (cap == null) {
                cap = Math.max(gameCount, BenchConfig.Adaptive.defaultMaxGames(ci, step));
            } else if (md == Mode.POD) {
                int rounded = BenchConfig.Adaptive.roundUp(cap, step);
                if (rounded != cap) {
                    System.out.printf("Note: --max-games %d rounded up to %d so every seat is played equally often.%n",
                            cap, rounded);
                    cap = rounded;
                }
            }
            try {
                adaptive = new BenchConfig.Adaptive(ci, cap);
            } catch (IllegalArgumentException e) {
                throw new ParameterException(spec.commandLine(), e.getMessage());
            }
        }
        int thr = deterministic ? 1 : first(threads, file.threads, Runtime.getRuntime().availableProcessors());
        if (deterministic && threads != null && threads != 1) {
            throw new ParameterException(spec.commandLine(), "--deterministic plays one game at a time; drop --threads");
        }
        int defaultTimeout = fmt == Format.COMMANDER
                ? BenchConfig.DEFAULT_TIMEOUT_COMMANDER_SEC : BenchConfig.DEFAULT_TIMEOUT_CONSTRUCTED_SEC;
        Path out = output != null ? output : file.output != null ? base.resolve(file.output) : Path.of("results");

        try {
            return new BenchConfig(
                    md,
                    fmt,
                    gameCount,
                    new DeckRef(targetPath.toAbsolutePath(), 1.0),
                    opps,
                    first(aiProfile, file.ai != null ? file.ai.profile : null, null),
                    first(maxRounds, file.limits != null ? file.limits.maxRounds : null, BenchConfig.DEFAULT_MAX_ROUNDS),
                    first(gameTimeout, file.limits != null ? file.limits.gameTimeoutSec : null, defaultTimeout),
                    first(seed, file.seed, new java.security.SecureRandom().nextLong()),
                    thr,
                    out.toAbsolutePath(),
                    !noLegalityCheck && first(null, file.checkLegality, true),
                    first(maxErrorRate, file.maxErrorRate, BenchConfig.DEFAULT_MAX_ERROR_RATE),
                    gameLogs || first(null, file.gameLogs, false),
                    shard != null ? BenchConfig.Shard.parse(shard) : null,
                    adaptive);
        } catch (IllegalArgumentException e) {
            throw new ParameterException(spec.commandLine(), e.getMessage());
        }
    }

    @SafeVarargs
    private static <T> T first(T... values) {
        for (T v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}
