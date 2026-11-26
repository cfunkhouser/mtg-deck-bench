package mtgbench.cli;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import forge.deck.DeckSection;
import mtgbench.config.Format;
import mtgbench.deck.DeckException;
import mtgbench.deck.DeckLoader;
import mtgbench.deck.LoadedDeck;
import mtgbench.engine.ForgeBootstrap;
import mtgbench.run.ForgeOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "validate", mixinStandardHelpOptions = true,
        description = "Check deck files without playing: every card is known to Forge and the deck is legal.")
public final class ValidateCommand implements Callable<Integer> {
    @Mixin
    BenchCli.ForgeOptions forge = new BenchCli.ForgeOptions();

    @Parameters(paramLabel = "DECK", arity = "1..*", description = "Deck files (.txt lists or Forge .dck).")
    List<Path> decks;

    @Option(names = {"-f", "--format"}, description = "constructed (default) or commander.")
    Format format = Format.CONSTRUCTED;

    @Option(names = "--no-legality-check", description = "Only check that cards are known.")
    boolean noLegalityCheck;

    @Override
    public Integer call() throws Exception {
        Path res = forge.resolveRes();
        Path home = forge.resolveHome();
        PrintStream console = ForgeOutput.redirectTo(home.resolve("logs").resolve("validate.log"));
        ForgeBootstrap.init(res, home);
        DeckLoader loader = new DeckLoader(format, !noLegalityCheck);
        int bad = 0;
        for (Path p : decks) {
            try {
                LoadedDeck d = loader.load(p);
                int main = d.deck().getMain().countAll();
                int side = d.deck().has(DeckSection.Sideboard) ? d.deck().get(DeckSection.Sideboard).countAll() : 0;
                String commanders = d.deck().getCommanders().isEmpty() ? ""
                        : ", commander: " + String.join(" + ", d.deck().getCommanders().stream().map(c -> c.getName()).toList());
                console.printf("OK    %s  (%s: %d main, %d sideboard%s)%n", p, d.name(), main, side, commanders);
                for (String w : d.warnings()) {
                    console.println("      warning: " + w);
                }
            } catch (DeckException e) {
                bad++;
                console.println("FAIL  " + p);
                for (String problem : e.problems()) {
                    console.println("      " + problem);
                }
            }
        }
        return bad == 0 ? 0 : BenchCli.EXIT_DECK;
    }
}
