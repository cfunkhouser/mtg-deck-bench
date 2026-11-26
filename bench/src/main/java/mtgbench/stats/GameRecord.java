package mtgbench.stats;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One line of {@code games.jsonl}: everything recorded about a finished (or failed) game.
 *
 * @param outcome    from the target deck's point of view: win, loss, draw, timeout or error
 * @param stopReason why the harness stopped the game early: "turnCap", "timeout", or null
 * @param winnerSeat seat that won; null for draws, timeouts and errors
 * @param turns      Forge's turn counter (counts every player's turn)
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record GameRecord(
        String runId,
        int gameId,
        String mode,
        String format,
        int matchup,
        String matchupName,
        long seed,
        List<PlayerRecord> players,
        Integer winnerSeat,
        String outcome,
        String stopReason,
        int turns,
        long durationMs,
        String forgeVersion,
        String error) {

    public static final String WIN = "win";
    public static final String LOSS = "loss";
    public static final String DRAW = "draw";
    public static final String TIMEOUT = "timeout";
    public static final String ERROR = "error";

    /**
     * @param lossReason              Forge's GameLossReason (e.g. LifeReachedZero, Milled, Poisoned,
     *                                CommanderDamage), or null if the player didn't lose
     * @param commanderCasts          Commander only: times this player cast a commander
     * @param firstCommanderCastRound Commander only: round of the first cast (1 = everyone's first turn), or null
     * @param commanderDamageTaken    Commander only: most combat damage taken from any single commander
     * @param placement               3+ players: finishing place (1 = winner); null for draws and timeouts
     * @param eliminatedRound         3+ players: round in which this player lost
     * @param eliminatedBySeat        3+ players: seat that dealt this player the last damage, when they
     *                                lost to life or commander damage
     */
    public record PlayerRecord(int seat, String deck, boolean target, boolean onPlay, int mulligans,
                               Integer finalLife, String lossReason,
                               @JsonInclude(JsonInclude.Include.NON_NULL) Integer commanderCasts,
                               @JsonInclude(JsonInclude.Include.NON_NULL) Integer firstCommanderCastRound,
                               @JsonInclude(JsonInclude.Include.NON_NULL) Integer commanderDamageTaken,
                               @JsonInclude(JsonInclude.Include.NON_NULL) Integer placement,
                               @JsonInclude(JsonInclude.Include.NON_NULL) Integer eliminatedRound,
                               @JsonInclude(JsonInclude.Include.NON_NULL) Integer eliminatedBySeat) {

        /** Constructed games: no commander fields. */
        public PlayerRecord(int seat, String deck, boolean target, boolean onPlay, int mulligans,
                            Integer finalLife, String lossReason) {
            this(seat, deck, target, onPlay, mulligans, finalLife, lossReason, null, null, null, null, null, null);
        }

        /** Two-player games: no multiplayer fields. */
        public PlayerRecord(int seat, String deck, boolean target, boolean onPlay, int mulligans,
                            Integer finalLife, String lossReason,
                            Integer commanderCasts, Integer firstCommanderCastRound, Integer commanderDamageTaken) {
            this(seat, deck, target, onPlay, mulligans, finalLife, lossReason, commanderCasts, firstCommanderCastRound,
                    commanderDamageTaken, null, null, null);
        }
    }

    public PlayerRecord targetPlayer() {
        return players.stream().filter(PlayerRecord::target).findFirst().orElseThrow();
    }
}
