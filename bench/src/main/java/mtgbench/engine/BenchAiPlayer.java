package mtgbench.engine;

import java.util.function.Function;

import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.player.Player;

/**
 * An AI lobby player whose in-game controller lets us decide who takes the first turn.
 *
 * Forge picks a random player (or, after game 1, the previous loser) and asks that player's
 * controller {@code chooseStartingPlayer}. The stock AI always answers "me". Our controller answers
 * with whatever {@code startingPlayer} returns, so whoever gets asked, the result is forced.
 */
public class BenchAiPlayer extends LobbyPlayerAi {
    private final Function<Game, Player> startingPlayer;

    public BenchAiPlayer(String name, String aiProfile, Function<Game, Player> startingPlayer) {
        super(name, null);
        if (aiProfile != null && !aiProfile.isEmpty()) {
            setAiProfile(aiProfile);
        }
        this.startingPlayer = startingPlayer;
    }

    @Override
    public Player createIngamePlayer(Game game, int id) {
        Player p = new Player(getName(), game, id);
        p.setFirstController(new Controller(game, p, this));
        return p;
    }

    private final class Controller extends PlayerControllerAi {
        Controller(Game game, Player p, BenchAiPlayer lp) {
            super(game, p, lp);
        }

        @Override
        public Player chooseStartingPlayer(boolean isFirstGame) {
            Player forced = startingPlayer.apply(getGame());
            return forced != null ? forced : super.chooseStartingPlayer(isFirstGame);
        }
    }
}
