package mtgbench.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/** Values recorded at build time in {@code mtgbench.properties} (Maven resource filtering). */
public final class BuildProps {
    private static final Properties PROPS = load();

    private BuildProps() {}

    public static String get(String key) {
        return PROPS.getProperty(key, "unknown");
    }

    private static Properties load() {
        Properties p = new Properties();
        try (InputStream in = BuildProps.class.getResourceAsStream("/mtgbench.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }
}
