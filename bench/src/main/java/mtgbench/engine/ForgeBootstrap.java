package mtgbench.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import forge.gui.GuiBase;
import forge.model.FModel;
import forge.util.MyRandom;

/**
 * One-time Forge initialization for a headless JVM.
 *
 * <p>Forge finds everything through an "assets directory" holding {@code res/} and
 * {@code forge.profile.properties}, which says where Forge may write (user and cache dirs). We build
 * that directory under a writable {@code forgeHome}, link {@code res/} to the read-only card data,
 * and send all of Forge's writes to {@code forgeHome} too. Nothing is written to the user's home.
 *
 * <p>Deliberately does less than forge.view.Main: in particular it never calls Sentry.init, so
 * crash reports are not sent to Forge's error-reporting server.
 */
public final class ForgeBootstrap {
    private static Path initializedWith;

    private ForgeBootstrap() {}

    /**
     * @param resDir    Forge's {@code res/} directory (contains {@code cardsfolder/}); read-only.
     * @param forgeHome writable directory for Forge's preferences, logs and caches.
     */
    public static synchronized void init(Path resDir, Path forgeHome) {
        resDir = resDir.toAbsolutePath().normalize();
        if (initializedWith != null) {
            if (!initializedWith.equals(resDir)) {
                throw new IllegalStateException("Forge already initialized with " + initializedWith);
            }
            return;
        }
        if (!Files.isDirectory(resDir.resolve("cardsfolder"))) {
            throw new IllegalArgumentException("Not a Forge res directory (no cardsfolder/): " + resDir);
        }
        Path assets = prepareAssetsDir(resDir, forgeHome.toAbsolutePath().normalize());

        System.setProperty("java.awt.headless", "true");
        GuiBase.setInterface(new HeadlessGui(assets.toString()));
        FModel.initialize(null, null);
        // Every game reseeds its own stream; see PerThreadRandom.
        MyRandom.setRandom(new PerThreadRandom());
        initializedWith = resDir;
    }

    private static Path prepareAssetsDir(Path resDir, Path forgeHome) {
        try {
            Path assets = forgeHome.resolve("assets");
            Files.createDirectories(assets);
            Path link = assets.resolve("res");
            if (Files.isSymbolicLink(link)) {
                if (!Files.readSymbolicLink(link).equals(resDir)) {
                    Files.delete(link);
                    Files.createSymbolicLink(link, resDir);
                }
            } else if (!Files.exists(link)) {
                Files.createSymbolicLink(link, resDir);
            } else {
                throw new IllegalStateException(link + " exists and is not a symlink; remove it");
            }
            String profile = "userDir=" + forgeHome.resolve("user") + "/\n"
                    + "cacheDir=" + forgeHome.resolve("cache") + "/\n";
            Files.writeString(assets.resolve("forge.profile.properties"), profile);
            return assets;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot prepare Forge home " + forgeHome, e);
        }
    }

    /** The Forge release this harness was built against (Forge's own jars carry no version). */
    public static String forgeVersion() {
        return BuildProps.get("forge.version");
    }
}
