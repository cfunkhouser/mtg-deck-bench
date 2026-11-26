package mtgbench.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.google.common.eventbus.Subscribe;

import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameIds;
import forge.game.GameLogEntry;
import forge.game.GameOutcome;
import forge.game.GameRules;
import forge.game.Match;
import forge.game.event.GameEventPlayerDamaged;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.Player;
import forge.game.player.PlayerStatistics;
import forge.game.player.RegisteredPlayer;
import mtgbench.config.Format;
import mtgbench.deck.LoadedDeck;
import mtgbench.schedule.GameSpec;
import mtgbench.stats.GameRecord;
import mtgbench.stats.GameRecord.PlayerRecord;

/**
 * Plays one {@link GameSpec} with Forge AIs in every seat and turns the result into a
 * {@link GameRecord}. Thread-safe: one instance is shared by all workers; each call runs its game
 * on the calling thread.
 *
 * <p>Techniques established in Phase 0 (docs/phase0-findings.md):
 * <ul>
 *   <li>The starting player is forced through {@link BenchAiPlayer}.
 *   <li>Each game reseeds its own random stream ({@link PerThreadRandom}) and gets private
 *       Forge id sequences ({@link GameIds#beginScope()}, from our Forge patch).
 *   <li>Turn and wall-clock limits end the game with {@code setGameOver(Draw)}. Forge then reports
 *       surviving players as winners, so draws are decided from the end reason, never from
 *       {@code GameOutcome.isDraw()}.
 * </ul>
 */
public final class GameRunner {
    /** After the wall-clock limit, how long a game gets to notice before its thread is interrupted. */
    private static final long INTERRUPT_GRACE_SEC = 30;

    private final String runId;
    private final String mode;
    private final Format format;
    private final List<LoadedDeck> decks;
    private final String aiProfile;
    private final int maxRounds;
    private final int timeoutSec;
    private final ScheduledExecutorService watchdog;
    private final Path gameLogDir;

    /**
     * @param decks      index 0 is the target deck, then the opponents in config order
     * @param gameLogDir if not null, each game's full Forge log is written there
     */
    public GameRunner(String runId, String mode, Format format, List<LoadedDeck> decks, String aiProfile,
                      int maxRounds, int timeoutSec, ScheduledExecutorService watchdog, Path gameLogDir) {
        this.runId = runId;
        this.mode = mode;
        this.format = format;
        this.decks = List.copyOf(decks);
        this.aiProfile = aiProfile;
        this.maxRounds = maxRounds;
        this.timeoutSec = timeoutSec;
        this.watchdog = watchdog;
        this.gameLogDir = gameLogDir;
    }

    public String matchupName(GameSpec spec) {
        List<String> names = new ArrayList<>();
        for (int deckIndex : spec.seatDecks()) {
            names.add(decks.get(deckIndex).name());
        }
        return String.join(" vs ", names);
    }

    public GameRecord play(GameSpec spec) {
        long start = System.currentTimeMillis();
        PerThreadRandom.reseedCurrentThread(spec.seed());
        GameIds.beginScope();
        Game game = null;
        List<RegisteredPlayer> seats = new ArrayList<>();
        try {
            int playerCount = spec.seatDecks().size();
            int maxTurns = maxRounds * playerCount;

            GameRules rules = new GameRules(format.gameType());
            rules.setAppliedVariants(EnumSet.of(format.gameType()));
            rules.setGamesPerMatch(1);

            for (int seat = 0; seat < playerCount; seat++) {
                LoadedDeck d = decks.get(spec.seatDecks().get(seat));
                RegisteredPlayer rp = format == Format.COMMANDER
                        ? RegisteredPlayer.forCommander(d.deck())
                        : new RegisteredPlayer(d.deck());
                final int startingSeat = spec.startingSeat();
                rp.setPlayer(new BenchAiPlayer(seatName(seat, d), aiProfile,
                        g -> findPlayer(g, seats.get(startingSeat))));
                seats.add(rp);
            }

            Match match = new Match(rules, seats, runId + "#" + spec.gameId());
            Game g = match.createGame();
            game = g;
            g.setNoGUIUser();

            int[] lastTurn = {0};
            boolean[] turnCapped = {false};
            Tracker tracker = new Tracker(g, seats);
            g.subscribeToEvents(new Object() {
                @Subscribe
                public void onTurn(GameEventTurnBegan e) {
                    // Casts and eliminations since the last check happened during the turn that just ended.
                    tracker.noteTurnEnded(e.turnNumber() - 1);
                    lastTurn[0] = e.turnNumber();
                    if (e.turnNumber() > maxTurns) {
                        turnCapped[0] = true;
                        g.setGameOver(GameEndReason.Draw);
                    }
                }

                @Subscribe
                public void onDamage(GameEventPlayerDamaged e) {
                    tracker.noteDamage(e);
                }
            });

            AtomicReference<String> timedOut = new AtomicReference<>();
            Thread worker = Thread.currentThread();
            ScheduledFuture<?> stop = watchdog.schedule(() -> {
                timedOut.set("timeout");
                g.setGameOver(GameEndReason.Draw);
            }, timeoutSec, TimeUnit.SECONDS);
            ScheduledFuture<?> interrupt = watchdog.schedule(worker::interrupt,
                    timeoutSec + INTERRUPT_GRACE_SEC, TimeUnit.SECONDS);
            try {
                match.startGame(g);
            } finally {
                stop.cancel(false);
                interrupt.cancel(false);
                Thread.interrupted(); // clear a late interrupt so it can't hit the next game
            }

            tracker.noteTurnEnded(lastTurn[0]);
            String stopReason = timedOut.get() != null ? "timeout" : turnCapped[0] ? "turnCap" : null;
            return record(spec, g, seats, stopReason, lastTurn[0], null, start, tracker);
        } catch (Throwable t) {
            if (game != null && !game.isGameOver()) {
                try {
                    game.setGameOver(GameEndReason.Draw);
                } catch (RuntimeException ignored) {
                    // the game is already broken; the error below is what matters
                }
            }
            String msg = t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
            return record(spec, game, seats, null, 0, msg, start, null);
        } finally {
            if (game != null) {
                writeGameLog(spec, game);
            }
            GameIds.endScope();
        }
    }

    private GameRecord record(GameSpec spec, Game game, List<RegisteredPlayer> seats, String stopReason,
                              int lastTurn, String error, long start, Tracker tracker) {
        GameOutcome out = game != null ? game.getOutcome() : null;
        Map<RegisteredPlayer, Player> inGame = new IdentityHashMap<>();
        if (game != null) {
            for (Player p : game.getRegisteredPlayers()) {
                inGame.put(p.getRegisteredPlayer(), p);
            }
        }
        Map<RegisteredPlayer, PlayerStatistics> stats = new IdentityHashMap<>();
        if (out != null) {
            for (Map.Entry<RegisteredPlayer, PlayerStatistics> e : out) {
                stats.put(e.getKey(), e.getValue());
            }
        }

        boolean ended = error == null && out != null;
        boolean drawn = ended && out.getWinCondition() == GameEndReason.Draw;
        Integer winnerSeat = null;
        if (ended && !drawn) {
            RegisteredPlayer winner = out.getWinningPlayer();
            int idx = indexOfIdentity(seats, winner);
            winnerSeat = idx >= 0 ? idx : null;
        }

        // Who actually went first, as Forge recorded it (not just what we asked for).
        int startedSeat = spec.startingSeat();
        if (game != null && game.getStartingPlayer() != null) {
            int idx = indexOfIdentity(seats, game.getStartingPlayer().getRegisteredPlayer());
            if (idx >= 0) {
                startedSeat = idx;
            }
        }
        if (error == null && game != null && game.getStartingPlayer() != null && startedSeat != spec.startingSeat()) {
            error = "Forge started seat " + startedSeat + " but seat " + spec.startingSeat() + " was requested";
        }

        // Multiplayer only: finishing places from Forge's elimination order (winner 1st, last one out 2nd, ...).
        boolean multiplayer = seats.size() > 2;
        Integer[] placement = new Integer[seats.size()];
        if (multiplayer && ended && !drawn && winnerSeat != null && stopReason == null && game != null) {
            placement[winnerSeat] = 1;
            List<Player> lost = new ArrayList<>(game.getLostPlayers());
            for (int i = 0; i < lost.size(); i++) {
                int seat = indexOfIdentity(seats, lost.get(i).getRegisteredPlayer());
                if (seat >= 0 && seat != winnerSeat) {
                    placement[seat] = seats.size() - i;
                }
            }
        }

        List<PlayerRecord> players = new ArrayList<>();
        for (int seat = 0; seat < seats.size(); seat++) {
            RegisteredPlayer rp = seats.get(seat);
            PlayerStatistics ps = stats.get(rp);
            Player p = inGame.get(rp);
            String lossReason = ps != null && ps.getOutcome() != null && ps.getOutcome().lossState != null
                    ? ps.getOutcome().lossState.name() : null;
            int deckIndex = spec.seatDecks().get(seat);
            Integer casts = null, firstCast = null, cmdDamage = null;
            if (format == Format.COMMANDER && p != null) {
                casts = p.getTotalCommanderCast();
                firstCast = tracker != null ? tracker.firstCastRound[seat] : null;
                int most = 0;
                for (Map.Entry<forge.game.card.Card, Integer> d : p.getCommanderDamage()) {
                    most = Math.max(most, d.getValue());
                }
                cmdDamage = most;
            }
            Integer eliminatedRound = null, eliminatedBy = null;
            if (multiplayer && tracker != null && !drawn && lossReason != null) {
                eliminatedRound = tracker.eliminatedRound[seat];
                if ("LifeReachedZero".equals(lossReason) || "CommanderDamage".equals(lossReason)) {
                    eliminatedBy = tracker.lastDamagedBy[seat];
                }
            }
            players.add(new PlayerRecord(seat, decks.get(deckIndex).name(), deckIndex == 0,
                    seat == startedSeat, ps != null ? ps.getMulliganCount() : 0,
                    p != null ? p.getLife() : null, drawn ? null : lossReason, casts, firstCast, cmdDamage,
                    placement[seat], eliminatedRound, eliminatedBy));
        }

        String outcome;
        if (error != null) {
            outcome = GameRecord.ERROR;
        } else if (out == null) {
            outcome = GameRecord.ERROR;
            error = "game ended without an outcome";
        } else if (stopReason != null) {
            outcome = GameRecord.TIMEOUT;
        } else if (drawn || winnerSeat == null) {
            outcome = GameRecord.DRAW;
        } else {
            outcome = winnerSeat == spec.targetSeat() ? GameRecord.WIN : GameRecord.LOSS;
        }
        if (outcome.equals(GameRecord.TIMEOUT)) {
            winnerSeat = null;
        }
        int turns = out != null ? out.getLastTurnNumber() : lastTurn;

        return new GameRecord(runId, spec.gameId(), mode, format.name().toLowerCase(), spec.matchup(),
                matchupName(spec), spec.seed(), players, winnerSeat, outcome, stopReason, turns,
                System.currentTimeMillis() - start, ForgeBootstrap.forgeVersion(), error);
    }

    private void writeGameLog(GameSpec spec, Game game) {
        if (gameLogDir == null) {
            return;
        }
        List<GameLogEntry> entries = new ArrayList<>(game.getGameLog().getLogEntries(null));
        Collections.reverse(entries); // Forge keeps newest first
        List<String> lines = new ArrayList<>(entries.size());
        for (GameLogEntry e : entries) {
            lines.add(e.toString());
        }
        try {
            Files.write(gameLogDir.resolve(String.format("game-%06d.log", spec.gameId())), lines);
        } catch (IOException e) {
            System.err.println("Could not write game log for game " + spec.gameId() + ": " + e);
        }
    }

    /**
     * Per-seat facts gathered while a game runs, on the game thread. Forge fires no event when a
     * player loses or casts a commander, so both are checked whenever a turn ends.
     */
    private final class Tracker {
        final Game game;
        final List<RegisteredPlayer> seats;
        final Integer[] firstCastRound;
        final Integer[] eliminatedRound;
        /** Seat whose permanent or spell last damaged this seat's player (damage from oneself ignored). */
        final Integer[] lastDamagedBy;
        final Map<Integer, Integer> seatByPlayerId = new java.util.HashMap<>();

        Tracker(Game game, List<RegisteredPlayer> seats) {
            this.game = game;
            this.seats = seats;
            firstCastRound = new Integer[seats.size()];
            eliminatedRound = new Integer[seats.size()];
            lastDamagedBy = new Integer[seats.size()];
            for (Player p : game.getRegisteredPlayers()) {
                seatByPlayerId.put(p.getId(), indexOfIdentity(seats, p.getRegisteredPlayer()));
            }
        }

        void noteTurnEnded(int turn) {
            if (turn < 1) {
                return;
            }
            int round = (turn - 1) / seats.size() + 1;
            for (Player p : game.getRegisteredPlayers()) {
                int seat = indexOfIdentity(seats, p.getRegisteredPlayer());
                if (seat < 0) {
                    continue;
                }
                if (format == Format.COMMANDER && firstCastRound[seat] == null && p.getTotalCommanderCast() > 0) {
                    firstCastRound[seat] = round;
                }
                if (eliminatedRound[seat] == null && p.hasLost()) {
                    eliminatedRound[seat] = round;
                }
            }
        }

        void noteDamage(GameEventPlayerDamaged e) {
            if (e.target() == null || e.source() == null || e.source().getController() == null || e.amount() <= 0) {
                return;
            }
            Integer target = seatByPlayerId.get(e.target().getId());
            Integer source = seatByPlayerId.get(e.source().getController().getId());
            if (target != null && source != null && !target.equals(source)) {
                lastDamagedBy[target] = source;
            }
        }
    }

    /** Player names must be unique within a game; Forge shows them in its logs. */
    private static String seatName(int seat, LoadedDeck d) {
        return "P" + (seat + 1) + "-" + d.name();
    }

    private static Player findPlayer(Game g, RegisteredPlayer rp) {
        for (Player p : g.getPlayers()) {
            if (p.getRegisteredPlayer() == rp) {
                return p;
            }
        }
        return null;
    }

    private static int indexOfIdentity(List<RegisteredPlayer> list, RegisteredPlayer rp) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == rp) {
                return i;
            }
        }
        return -1;
    }
}
