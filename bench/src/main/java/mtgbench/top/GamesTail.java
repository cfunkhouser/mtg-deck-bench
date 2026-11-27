package mtgbench.top;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import mtgbench.report.Reports;
import mtgbench.stats.GameRecord;

/**
 * Follows a games.jsonl that another process appends to, reading only what was added since the last
 * call. A line still being written is kept back until its newline arrives. If the file shrinks (it was
 * rewritten, e.g. by {@code resume} dropping a cut-off last line), it is read again from the start.
 */
public final class GamesTail {
    private final Path file;
    private final List<GameRecord> games = new ArrayList<>();
    private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
    private long offset;
    private int badLines;

    public GamesTail(Path file) {
        this.file = file;
    }

    /** Reads what was appended. @return true if any game was added (or the file was re-read) */
    public boolean poll() throws IOException {
        long size;
        try {
            size = Files.size(file);
        } catch (NoSuchFileException e) {
            return false;
        }
        boolean reset = false;
        if (size < offset) {
            games.clear();
            partial.reset();
            offset = 0;
            badLines = 0;
            reset = true;
        }
        if (size == offset) {
            return reset;
        }
        int before = games.size();
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ch.position(offset);
            ByteBuffer buf = ByteBuffer.allocate(1 << 16);
            int n;
            while ((n = ch.read(buf)) > 0) {
                offset += n;
                buf.flip();
                while (buf.hasRemaining()) {
                    byte b = buf.get();
                    if (b == '\n') {
                        line(partial.toString(StandardCharsets.UTF_8));
                        partial.reset();
                    } else {
                        partial.write(b);
                    }
                }
                buf.clear();
            }
        }
        return reset || games.size() != before;
    }

    private void line(String text) {
        String line = text.strip();
        if (line.isEmpty()) {
            return;
        }
        try {
            games.add(Reports.parseGame(line));
        } catch (IOException e) {
            badLines++;
        }
    }

    public List<GameRecord> games() {
        return Collections.unmodifiableList(games);
    }

    /** Complete lines that weren't valid game records (skipped). */
    public int badLines() {
        return badLines;
    }
}
