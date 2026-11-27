package mtgbench.top;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The controlling terminal, driven with {@code stty} and ANSI escape codes (no native library). Raw mode
 * (keys arrive one at a time, unechoed) and the alternate screen are undone by {@link #close}, which
 * also runs from a shutdown hook so Ctrl-C leaves a usable shell.
 */
public final class Terminal implements AutoCloseable {
    /** Keys {@link #readKey} reports besides plain characters. */
    public static final int UP = -2, DOWN = -3, PAGE_UP = -4, PAGE_DOWN = -5, HOME = -6, END = -7, NONE = -1;

    private final InputStream in;
    private final PrintStream out;
    private final String savedMode;
    private final Thread restoreOnExit = new Thread(this::restore, "mtgbench-top-restore");
    private boolean restored;

    private Terminal(InputStream in, PrintStream out, String savedMode) {
        this.in = in;
        this.out = out;
        this.savedMode = savedMode;
    }

    /** Puts the terminal in raw mode on the alternate screen, or returns null if there's no usable tty. */
    public static Terminal open(InputStream in, PrintStream out) {
        if (System.console() == null) {
            return null;
        }
        String saved = stty("-g");
        if (saved == null || stty("-icanon -echo min 1") == null) {
            return null;
        }
        Terminal t = new Terminal(in, out, saved.strip());
        Runtime.getRuntime().addShutdownHook(t.restoreOnExit);
        out.print("\u001b[?1049h\u001b[?25l");
        out.flush();
        return t;
    }

    /** Rows and columns, from {@code stty size}; 24x80 if that fails. */
    public int[] size() {
        String s = stty("size");
        if (s != null) {
            String[] parts = s.strip().split("\\s+");
            if (parts.length == 2) {
                try {
                    return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
                } catch (NumberFormatException e) {
                    // fall through
                }
            }
        }
        return new int[] {24, 80};
    }

    /** Replaces the screen with {@code lines}. */
    public void draw(List<String> lines) {
        StringBuilder sb = new StringBuilder("\u001b[H");
        for (int i = 0; i < lines.size(); i++) {
            sb.append(lines.get(i)).append("\u001b[K");
            if (i < lines.size() - 1) {
                sb.append("\r\n");
            }
        }
        sb.append("\u001b[J");
        out.print(sb);
        out.flush();
    }

    /** Waits up to {@code timeoutMs} for a key. @return the key, or {@link #NONE} */
    public int readKey(long timeoutMs) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (in.available() == 0) {
            if (System.currentTimeMillis() >= deadline) {
                return NONE;
            }
            Thread.sleep(25);
        }
        int c = in.read();
        if (c != 0x1b) {
            return c;
        }
        // An escape sequence such as ESC [ A arrives all at once; a lone ESC doesn't.
        Thread.sleep(10);
        if (in.available() == 0 || in.read() != '[') {
            return NONE;
        }
        StringBuilder seq = new StringBuilder();
        while (in.available() > 0) {
            int b = in.read();
            seq.append((char) b);
            if (Character.isLetter(b) || b == '~') {
                break;
            }
        }
        return switch (seq.toString()) {
            case "A" -> UP;
            case "B" -> DOWN;
            case "5~" -> PAGE_UP;
            case "6~" -> PAGE_DOWN;
            case "H", "1~" -> HOME;
            case "F", "4~" -> END;
            default -> NONE;
        };
    }

    @Override
    public void close() {
        restore();
        try {
            Runtime.getRuntime().removeShutdownHook(restoreOnExit);
        } catch (IllegalStateException e) {
            // already shutting down
        }
    }

    private synchronized void restore() {
        if (restored) {
            return;
        }
        restored = true;
        out.print("\u001b[?25h\u001b[?1049l");
        out.flush();
        stty(savedMode);
    }

    /** Runs {@code stty ARGS} on /dev/tty. @return its output, or null if it failed */
    private static String stty(String args) {
        try {
            Process p = new ProcessBuilder("sh", "-c", "stty " + args + " < /dev/tty")
                    .redirectErrorStream(true).start();
            String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(5, TimeUnit.SECONDS) || p.exitValue() != 0) {
                return null;
            }
            return text;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
