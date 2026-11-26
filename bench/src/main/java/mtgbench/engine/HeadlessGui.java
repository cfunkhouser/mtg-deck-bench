package mtgbench.engine;

import forge.GuiDesktop;

/**
 * Forge's desktop GUI interface, reused headless. The only change is where Forge looks for its
 * assets: GuiDesktop hard-codes "../forge-gui/" for git builds and "" otherwise, so we take the
 * directory (the one that contains {@code res/}) from configuration instead.
 */
public class HeadlessGui extends GuiDesktop {
    private final String assetsDir;

    public HeadlessGui(String assetsDir) {
        this.assetsDir = assetsDir.endsWith("/") ? assetsDir : assetsDir + "/";
    }

    @Override
    public String getAssetsDir() {
        return assetsDir;
    }
}
