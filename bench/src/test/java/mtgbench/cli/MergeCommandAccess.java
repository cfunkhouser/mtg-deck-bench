package mtgbench.cli;

import mtgbench.run.RunManifest;
import mtgbench.stats.GameRecord;

/** Test access to MergeCommand's package-private rules. */
public final class MergeCommandAccess {
    private MergeCommandAccess() {}

    public static String incompatibility(RunManifest a, RunManifest b) {
        return MergeCommand.incompatibility(a, b);
    }

    public static boolean sameGame(GameRecord a, GameRecord b) {
        return MergeCommand.sameGame(a, b);
    }
}
