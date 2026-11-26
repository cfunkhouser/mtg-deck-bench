package mtgbench.testsupport;

import java.nio.file.Path;

import mtgbench.engine.ForgeBootstrap;

/** Initializes Forge once per test JVM from the submodule's res/ (paths set by surefire in pom.xml). */
public final class ForgeTestSupport {
    private ForgeTestSupport() {}

    public static void init() {
        ForgeBootstrap.init(Path.of(System.getProperty("mtgbench.test.forgeRes")),
                Path.of(System.getProperty("mtgbench.test.forgeHome")));
    }
}
