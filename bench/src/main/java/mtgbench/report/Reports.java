package mtgbench.report;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import mtgbench.stats.GameRecord;
import mtgbench.stats.Summary;
import mtgbench.stats.Summary.MatchupSummary;
import mtgbench.stats.Summary.Rate;

/** Writes run outputs. */
public final class Reports {
    /** Paths as plain strings (Jackson's default writes file:// URIs). */
    private static final SimpleModule PATHS = new SimpleModule()
            .addSerializer(Path.class, new ToStringSerializer(Path.class));

    public static final ObjectMapper JSON = JsonMapper.builder().addModule(PATHS).build();
    private static final ObjectMapper YAML = JsonMapper.builder(
            new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER))
            .addModule(PATHS)
            .enable(SerializationFeature.INDENT_OUTPUT).build();

    private Reports() {}

    private static final ObjectMapper READ_JSON = JsonMapper.builder()
            .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(com.fasterxml.jackson.databind.MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS).build();
    private static final ObjectMapper READ_YAML = JsonMapper.builder(new YAMLFactory())
            .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(com.fasterxml.jackson.databind.MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS).build();

    public static <T> T readYaml(Path file, Class<T> type) throws IOException {
        return READ_YAML.readValue(file.toFile(), type);
    }

    /**
     * Reads games.jsonl. A last line cut off by a crash or kill is skipped (and counted); a broken
     * line anywhere else is an error.
     */
    public static GamesRead readGames(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<GameRecord> games = new ArrayList<>();
        int skipped = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) {
                continue;
            }
            try {
                games.add(READ_JSON.readValue(line, GameRecord.class));
            } catch (IOException e) {
                if (i == lines.size() - 1) {
                    skipped++;
                } else {
                    throw new IOException(file + " line " + (i + 1) + ": " + e.getMessage(), e);
                }
            }
        }
        return new GamesRead(games, skipped);
    }

    public record GamesRead(List<GameRecord> games, int truncatedLines) {}

    /** Writes games.jsonl from scratch with these records (atomically, via a temporary file). */
    public static void rewriteGames(Path file, List<GameRecord> games) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (GamesJsonl out = new GamesJsonl(tmp)) {
            for (GameRecord g : games) {
                out.append(g);
            }
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    public static void writeYaml(Path file, Object value) throws IOException {
        YAML.writeValue(file.toFile(), value);
    }

    public static void writeJson(Path file, Object value) throws IOException {
        JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), value);
    }

    /** Appends one JSON line per game and flushes it, so a crashed or stopped run keeps every finished game. */
    public static final class GamesJsonl implements Closeable {
        private final BufferedWriter out;

        public GamesJsonl(Path file) throws IOException {
            this(file, false);
        }

        /** @param append add to an existing file (resume) instead of replacing it */
        public GamesJsonl(Path file, boolean append) throws IOException {
            out = append
                    ? Files.newBufferedWriter(file, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND)
                    : Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        }

        public synchronized void append(GameRecord r) throws IOException {
            out.write(JSON.writeValueAsString(r));
            out.newLine();
            out.flush();
        }

        @Override
        public synchronized void close() throws IOException {
            out.close();
        }
    }

    /** The summary as Markdown (summary.md) or as plain console text (same table). */
    public static String table(Summary s, boolean markdown) {
        StringBuilder sb = new StringBuilder();
        if (markdown) {
            sb.append("# Benchmark: ").append(s.target()).append("\n\nRun `").append(s.runId()).append("`\n\n");
        }
        if (s.score() != null) {
            sb.append(markdown ? "**Score: " : "Score: ").append(fmt1(s.score())).append(" ± ")
              .append(fmt1(s.scoreCiHalfWidth())).append(markdown ? "**" : "")
              .append("  (weighted win rate ×100, 95% CI; unweighted mean ").append(fmt1(s.unweightedMean()))
              .append(", worst matchup: ").append(s.worstMatchup()).append(")\n\n");
        }
        String sep = markdown ? " | " : "  ";
        String[] head = {"Opponent", "Weight", "Games", "W-L-D", "Timeout/Err", "Win rate [95% CI]", "On play", "On draw", "Avg rounds"};
        if (markdown) {
            sb.append("| ").append(String.join(" | ", head)).append(" |\n|");
            sb.append("---|".repeat(head.length)).append("\n");
        } else {
            sb.append(String.format("%-28s %6s %6s %-11s %-11s %-22s %-8s %-8s %s%n", (Object[]) head));
        }
        for (MatchupSummary m : s.matchups()) {
            String[] row = {
                m.opponent() + (m.flagged() ? " ⚠" : ""),
                fmt2(m.weight()),
                String.valueOf(m.games()),
                m.wins() + "-" + m.losses() + "-" + m.draws(),
                m.timeouts() + "/" + m.errors(),
                rateWithCi(m.all()),
                pct(m.onPlay()),
                pct(m.onDraw()),
                m.avgRounds() == null ? "-" : fmt1(m.avgRounds())};
            if (markdown) {
                sb.append("| ").append(String.join(sep, row)).append(" |\n");
            } else {
                sb.append(String.format("%-28s %6s %6s %-11s %-11s %-22s %-8s %-8s %s%n", (Object[]) row));
            }
        }
        if (s.matchups().stream().anyMatch(m -> m.commander() != null)) {
            sb.append(markdown ? "\n### Commander\n\n" : "\nCommander (target deck, decided games):\n");
            String[] ch = {"Opponent", "Wins by cmdr dmg", "Losses to cmdr dmg", "Cast commander", "Casts/game", "First cast (round)"};
            if (markdown) {
                sb.append("| ").append(String.join(" | ", ch)).append(" |\n|").append("---|".repeat(ch.length)).append("\n");
            } else {
                sb.append(String.format("%-28s %-17s %-19s %-15s %-11s %s%n", (Object[]) ch));
            }
            for (MatchupSummary m : s.matchups()) {
                Summary.CommanderStats c = m.commander();
                if (c == null) {
                    continue;
                }
                String[] row = {m.opponent(), String.valueOf(c.winsByCommanderDamage()),
                        String.valueOf(c.lossesByCommanderDamage()), pctOf(c.castRate()), fmt1(c.avgCasts()),
                        c.avgFirstCastRound() == null ? "-" : fmt1(c.avgFirstCastRound())};
                if (markdown) {
                    sb.append("| ").append(String.join(" | ", row)).append(" |\n");
                } else {
                    sb.append(String.format("%-28s %-17s %-19s %-15s %-11s %s%n", (Object[]) row));
                }
            }
        }
        sb.append(markdown ? "\n" : "").append("Overall: ").append(s.gamesPlayed()).append(" games, win rate ")
          .append(rateWithCi(s.overall())).append(", timeouts ").append(pctOf(s.timeoutRate()))
          .append(", errors ").append(pctOf(s.errorRate())).append("\n");
        if (s.matchups().stream().anyMatch(MatchupSummary::flagged)) {
            sb.append("⚠ = more than 5% of that matchup's games hit a limit or failed; treat its numbers with care.\n");
        }
        if (markdown) {
            details(s, sb);
        } else {
            sb.append("More detail (mulligans, how games were won and lost, game length) is in summary.md.\n");
        }
        if (markdown) {
            sb.append("\nAll games were played by Forge's AI on both sides. Results show how well Forge's AI pilots ")
              .append("each deck, which can differ from human play, especially for combo and control decks.\n");
        }
        return sb.toString();
    }

    /** Per-matchup detail sections for summary.md. */
    private static void details(Summary s, StringBuilder sb) {
        sb.append("\n## Mulligans\n\n| Opponent | Target mulligans | Opponent mulligans | Win rate after mulligan | Win rate keeping 7 |\n")
          .append("|---|---|---|---|---|\n");
        for (MatchupSummary m : s.matchups()) {
            Summary.MulliganStats ms = m.mulligans();
            sb.append("| ").append(m.opponent()).append(" | ")
              .append(ms.targetRate() == null ? "-" : pctOf(ms.targetRate())).append(" | ")
              .append(ms.opponentRate() == null ? "-" : pctOf(ms.opponentRate())).append(" | ")
              .append(rateWithN(ms.afterMulligan())).append(" | ")
              .append(rateWithN(ms.withoutMulligan())).append(" |\n");
        }

        sb.append("\n## How games ended\n\n| Opponent | Target won by | Target lost to |\n|---|---|---|\n");
        for (MatchupSummary m : s.matchups()) {
            sb.append("| ").append(m.opponent()).append(" | ").append(reasonList(m.targetWinReasons()))
              .append(" | ").append(reasonList(m.targetLossReasons())).append(" |\n");
        }

        sb.append("\n## Game length (round the game ended; a round is one turn for each player)\n");
        for (MatchupSummary m : s.matchups()) {
            sb.append("\n**vs ").append(m.opponent()).append("**")
              .append(m.avgRounds() == null ? "" : " (average round " + fmt1(m.avgRounds()) + ")").append("\n\n```\n");
            java.util.SortedSet<Integer> rounds = new java.util.TreeSet<>(m.winRounds().keySet());
            rounds.addAll(m.lossRounds().keySet());
            int max = 1;
            for (int r : rounds) {
                max = Math.max(max, Math.max(m.winRounds().getOrDefault(r, 0), m.lossRounds().getOrDefault(r, 0)));
            }
            sb.append(String.format("%5s  %-22s %s%n", "round", "target wins", "target losses"));
            for (int r : rounds) {
                int w = m.winRounds().getOrDefault(r, 0), l = m.lossRounds().getOrDefault(r, 0);
                sb.append(String.format("%5d  %-22s %s%n", r, bar(w, max), bar(l, max)));
            }
            sb.append("```\n");
        }
    }

    private static String bar(int n, int max) {
        if (n == 0) {
            return "";
        }
        int width = Math.max(1, Math.round(16f * n / max));
        return "█".repeat(width) + " " + n;
    }

    private static String reasonList(java.util.Map<String, Integer> reasons) {
        if (reasons.isEmpty()) {
            return "-";
        }
        List<String> parts = new ArrayList<>();
        reasons.forEach((k, v) -> parts.add(k + " " + v));
        return String.join(", ", parts);
    }

    private static String rateWithN(Rate r) {
        return r.rate() == null ? "-" : pctOf(r.rate()) + " (n=" + r.decided() + ")";
    }

    private static String rateWithCi(Rate r) {
        if (r.rate() == null) {
            return "-";
        }
        return pctOf(r.rate()) + " [" + pctOf(r.ciLow()) + "–" + pctOf(r.ciHigh()) + "]";
    }

    private static String pct(Rate r) {
        return r.rate() == null ? "-" : pctOf(r.rate());
    }

    private static String pctOf(double v) {
        return String.format(Locale.ROOT, "%.1f%%", 100 * v);
    }

    private static String fmt1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String fmt2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
