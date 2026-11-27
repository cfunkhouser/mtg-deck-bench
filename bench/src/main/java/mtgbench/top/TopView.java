package mtgbench.top;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import mtgbench.config.BenchConfig;
import mtgbench.stats.Summary;

/**
 * Lays out a {@link TopModel.Snapshot} as lines of a terminal screen, like {@code top}: a few summary
 * lines, then one row per matchup, sorted and scrolled as the {@link Settings} say. Pure: no terminal
 * access, so the same text serves the live screen and batch output.
 */
public final class TopView {
    public enum SortKey {
        GAMES('g', "GAMES", true), WIN_RATE('w', "WIN%", true), ROUNDS('r', "ROUNDS", true),
        NAME('n', "OPPONENT", false), ORDER('o', "#", false);

        final char key;
        final String column;
        /** Direction when the key is first picked: biggest first, except names and run order. */
        final boolean descendingFirst;

        SortKey(char key, String column, boolean descendingFirst) {
            this.key = key;
            this.column = column;
            this.descendingFirst = descendingFirst;
        }

        public static SortKey forKey(char c) {
            for (SortKey k : values()) {
                if (k.key == c) {
                    return k;
                }
            }
            return null;
        }
    }

    /** How the table is shown: sort column and direction, first row shown, and whether to use ANSI styles. */
    public record Settings(SortKey sort, boolean descending, int scroll, boolean ansi) {
        public static Settings defaults(boolean ansi) {
            return new Settings(SortKey.GAMES, true, 0, ansi);
        }

        /** Pressing a sort key: picks that column, or flips the direction if it is already the column. */
        public Settings sortBy(SortKey key) {
            return key == sort ? new Settings(sort, !descending, 0, ansi)
                    : new Settings(key, key.descendingFirst, 0, ansi);
        }

        public Settings reversed() {
            return new Settings(sort, !descending, scroll, ansi);
        }

        public Settings scrolledTo(int first) {
            return new Settings(sort, descending, Math.max(0, first), ansi);
        }
    }

    static final String HELP = "Sort: g games  w win%  r rounds  n name  o order  (again or R: reverse)"
            + "   Scroll: j/k ↑/↓ PgUp/PgDn   q quit";

    private static final String BOLD = "\u001b[1m";
    private static final String REVERSE = "\u001b[7m";
    private static final String RESET = "\u001b[0m";
    private static final int MAX_NAME = 40;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Columns after the opponent's name: header, width. */
    private static final String[][] COLUMNS = {
        {"GAMES", "6"}, {"PLAN", "6"}, {"W-L-D", "12"}, {"WIN%", "6"}, {"95% CI", "14"},
        {"TIMEOUT", "8"}, {"ERR", "4"}, {"ROUNDS", "7"}, {"STATE", "8"}};

    private TopView() {}

    /** How many table rows fit on a screen of {@code height} lines. */
    public static int tableRows(TopModel.Snapshot s, int height) {
        // Below the header: a blank line and the column header above the rows, the footer below them.
        return Math.max(1, height - header(s, 0).size() - 3);
    }

    /** The screen: exactly {@code height} lines or fewer, none wider than {@code width}. */
    public static List<String> render(TopModel.Snapshot s, Settings view, int width, int height, long now) {
        List<String> lines = new ArrayList<>(header(s, now));
        List<TopModel.Row> rows = sorted(s.rows(), view);
        int nameWidth = nameWidth(rows, width);
        lines.add("");
        String columns = columnHeader(view, nameWidth);
        lines.add(view.ansi() ? REVERSE + columns + RESET : columns);

        int fit = Math.max(1, height - lines.size() - 1);
        int first = Math.min(view.scroll(), Math.max(0, rows.size() - fit));
        for (int i = first; i < Math.min(rows.size(), first + fit); i++) {
            lines.add(row(rows.get(i), nameWidth));
        }
        String more = rows.size() > fit
                ? String.format("rows %d-%d of %d   ", first + 1, Math.min(rows.size(), first + fit), rows.size())
                : "";
        if (view.ansi()) {
            while (lines.size() < height - 1) {
                lines.add("");
            }
            lines.add(more + HELP);
        } else if (!more.isEmpty()) {
            lines.add(more.strip());
        }
        List<String> clipped = new ArrayList<>();
        for (String l : lines.subList(0, Math.min(lines.size(), height))) {
            clipped.add(clip(l, width));
        }
        return clipped;
    }

    /** Rows in display order. Rows without a value for the sort column always come last. */
    static List<TopModel.Row> sorted(List<TopModel.Row> rows, Settings view) {
        Comparator<TopModel.Row> byName = Comparator.comparing(r -> r.opponent().toLowerCase(Locale.ROOT));
        Comparator<TopModel.Row> order = switch (view.sort()) {
            case GAMES -> Comparator.comparingInt(TopModel.Row::games);
            case WIN_RATE -> Comparator.comparing(TopModel.Row::winRate, Comparator.nullsFirst(Double::compare));
            case ROUNDS -> Comparator.comparing(TopModel.Row::avgRounds, Comparator.nullsFirst(Double::compare));
            case NAME -> byName;
            case ORDER -> Comparator.comparingInt(TopModel.Row::index);
        };
        if (view.descending()) {
            order = order.reversed();
        }
        Comparator<TopModel.Row> missingLast = Comparator.comparing(r -> missing(r, view.sort()));
        return rows.stream().sorted(missingLast.thenComparing(order).thenComparing(byName)).toList();
    }

    private static boolean missing(TopModel.Row r, SortKey key) {
        return switch (key) {
            case WIN_RATE -> r.winRate() == null;
            case ROUNDS -> r.avgRounds() == null;
            default -> false;
        };
    }

    private static List<String> header(TopModel.Snapshot s, long now) {
        BenchConfig c = s.manifest().config();
        Summary sum = s.summary();
        List<String> h = new ArrayList<>();

        String status;
        if (s.finished()) {
            status = "finished";
        } else if (s.lastWrite() == null) {
            status = "waiting for the first game";
        } else {
            status = "last game " + duration(Math.max(0, now - s.lastWrite())) + " ago";
        }
        h.add(String.format("mtgbench top - %s   run %s   %s",
                CLOCK.format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())), s.manifest().runId(), status));

        int opponents = s.manifest().opponentNames().size();
        String plan = c.adaptive() != null
                ? String.format("adaptive ±%.1f pp, cap %d", c.adaptive().targetCi() * 100, c.adaptive().maxGames())
                : (c.mode() == BenchConfig.Mode.POD ? c.games() + " games" : c.games() + " games each");
        h.add(String.format("Target: %s   %s, %s, %d opponent%s, %s%s", sum.target(),
                c.mode().name().toLowerCase(Locale.ROOT), c.format().name().toLowerCase(Locale.ROOT), opponents,
                opponents == 1 ? "" : "s", plan, c.shard() != null ? ", shard " + c.shard() : ""));

        StringBuilder games = new StringBuilder(String.format("Games: %d of %d %s", s.played(), s.planned(),
                c.adaptive() != null ? "planned so far" : "planned"));
        if (s.planned() > 0) {
            games.append(String.format(" (%.1f%%)", 100.0 * s.played() / s.planned()));
        }
        if (s.perMinute() != null) {
            games.append(String.format("   %.1f/min", s.perMinute()));
            int left = Math.max(0, s.planned() - s.played());
            if (!s.finished() && left > 0 && s.perMinute() > 0) {
                games.append("   ETA ").append(c.adaptive() != null ? "≥ " : "")
                        .append(duration((long) (left / s.perMinute() * 60_000)));
            }
        }
        if (s.badLines() > 0) {
            games.append("   (").append(s.badLines()).append(" unreadable lines skipped)");
        }
        h.add(games.toString());

        StringBuilder overall = new StringBuilder("Overall: win ").append(percent(sum.overall().rate()))
                .append(sum.overall().rate() == null ? "" : "%")
                .append(' ').append(ci(sum.overall().ciLow(), sum.overall().ciHigh()))
                .append(String.format("   timeouts %.1f%%   errors %.1f%%", sum.timeoutRate() * 100,
                        sum.errorRate() * 100));
        if (sum.score() != null) {
            // The score is already a win rate ×100 (0-100).
            overall.append(String.format("   score %.1f ± %.1f", sum.score(),
                    sum.scoreCiHalfWidth() == null ? 0 : sum.scoreCiHalfWidth()));
        }
        if (sum.pod() != null && sum.pod().meanPlacement() != null) {
            overall.append(String.format("   mean place %.2f of %d", sum.pod().meanPlacement(), sum.pod().players()));
        }
        h.add(overall.toString());

        int[] states = new int[TopModel.State.values().length];
        s.rows().forEach(r -> states[r.state().ordinal()]++);
        h.add(String.format("Matchups: %d playing, %d queued, %d done, %d capped", states[TopModel.State.PLAYING.ordinal()],
                states[TopModel.State.QUEUED.ordinal()], states[TopModel.State.DONE.ordinal()],
                states[TopModel.State.CAPPED.ordinal()]));
        return h;
    }

    private static int nameWidth(List<TopModel.Row> rows, int width) {
        int fixed = 5;
        for (String[] col : COLUMNS) {
            fixed += Integer.parseInt(col[1]) + 1;
        }
        int longest = rows.stream().mapToInt(r -> r.opponent().length()).max().orElse(8);
        return Math.max(10, Math.min(Math.min(Math.max(longest, 8), MAX_NAME), width - fixed));
    }

    private static String columnHeader(Settings view, int nameWidth) {
        StringBuilder sb = new StringBuilder();
        sb.append(label(view, SortKey.ORDER, "#", 4, false)).append(' ');
        sb.append(label(view, SortKey.NAME, "OPPONENT", nameWidth, true));
        for (String[] col : COLUMNS) {
            SortKey key = switch (col[0]) {
                case "GAMES" -> SortKey.GAMES;
                case "WIN%" -> SortKey.WIN_RATE;
                case "ROUNDS" -> SortKey.ROUNDS;
                default -> null;
            };
            sb.append(' ').append(label(view, key, col[0], Integer.parseInt(col[1]), false));
        }
        return sb.toString();
    }

    /** A column header, padded to its width. The sort column's carries an arrow, and is bold on a terminal. */
    private static String label(Settings view, SortKey key, String name, int width, boolean left) {
        boolean sorted = key != null && view.sort() == key;
        String text = sorted ? name + (view.descending() ? "▼" : "▲") : name;
        String padded = left ? pad(text, width) : String.format("%" + width + "s", text);
        return sorted && view.ansi() ? BOLD + padded + RESET + REVERSE : padded;
    }

    private static String row(TopModel.Row r, int nameWidth) {
        return String.format("%4d %s %6d %6s %12s %6s %14s %8d %4d %7s %8s",
                r.index() + 1, pad(r.opponent(), nameWidth), r.games(), r.planned() > 0 ? r.planned() : "-",
                r.wins() + "-" + r.losses() + "-" + r.draws(), percent(r.winRate()), ci(r.ciLow(), r.ciHigh()),
                r.timeouts(), r.errors(), r.avgRounds() == null ? "-" : String.format("%.1f", r.avgRounds()),
                r.state().name().toLowerCase(Locale.ROOT));
    }

    private static String percent(Double rate) {
        return rate == null ? "-" : String.format("%.1f", rate * 100);
    }

    private static String ci(Double low, Double high) {
        return low == null || high == null ? "-" : String.format("[%.1f–%.1f]", low * 100, high * 100);
    }

    private static String pad(String s, int width) {
        if (s.length() > width) {
            return s.substring(0, width - 1) + "…";
        }
        return s + " ".repeat(width - s.length());
    }

    /** Cuts a line to {@code width} visible characters, leaving ANSI escape sequences intact. */
    static String clip(String line, int width) {
        StringBuilder out = new StringBuilder();
        int visible = 0;
        boolean cut = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '\u001b') {
                int end = line.indexOf('m', i);
                if (end < 0) {
                    break;
                }
                out.append(line, i, end + 1);
                i = end;
            } else if (visible < width) {
                out.append(ch);
                visible++;
            } else {
                cut = true;
            }
        }
        return cut && out.indexOf("\u001b") >= 0 ? out + RESET : out.toString();
    }

    /** "45s", "12m 03s", "3h 20m", "2d 4h". */
    static String duration(long ms) {
        long s = ms / 1000;
        if (s < 60) {
            return s + "s";
        }
        if (s < 3600) {
            return String.format("%dm %02ds", s / 60, s % 60);
        }
        if (s < 86_400) {
            return String.format("%dh %02dm", s / 3600, (s % 3600) / 60);
        }
        return String.format("%dd %dh", s / 86_400, (s % 86_400) / 3600);
    }
}
