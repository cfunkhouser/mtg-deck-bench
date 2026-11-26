# Phase 0 findings: how Forge behaves when embedded

Date: 2026-11-26 · Forge `forge-2.0.16` (commit `583714b8`) · JDK 27 (Homebrew), Apple Silicon, 8 cores, 16 GB

Test program: a throwaway `SpikeMain` class, replaced in Phase 1 by
[`GameRunner`](../bench/src/main/java/mtgbench/engine/GameRunner.java), which keeps the same techniques.
It played N AI-vs-AI games between two decks and printed one line per game. Unless noted otherwise, test decks
are [`examples/decks/`](../examples/decks/) (mono-red burn vs mono-green stompy).

## Summary

| Spike | Question | Result |
|---|---|---|
| S1 | Headless start from our own `main` | ✅ Works. Forge starts in about 3.7 s, and the card database takes about 250–550 MB of heap |
| S2 | Force the starting player | ✅ Works without patching Forge. Correct in 100% of games (several hundred) |
| S3 | Several games at once in one JVM | ⚠️ Works with no errors, but **only after a small Forge patch** (static ID counters). 8 threads give 2.9× the single-thread throughput |
| S4 | Reproducible games from a seed | ⚠️ **Reproducible when each JVM runs one game at a time.** Multi-threaded runs are statistically valid but not reproducible game by game |
| S5 | Stop stalled or very long games | ✅ Turn limit and wall-clock watchdog both work. Forge reports these forced draws misleadingly, so the harness must classify them itself |

## S1: Headless start

- `ForgeBootstrap` sets `java.awt.headless=true` and installs `HeadlessGui`, a `GuiDesktop` subclass that changes
  only `getAssetsDir()`. Then it calls `FModel.initialize(null, null)`. No Swing window is ever created.
- Forge finds everything through the **assets directory**, the directory containing `res/`. It also reads
  `forge.profile.properties` from there, with `userDir=` and `cacheDir=`, so we can keep Forge from writing to
  `~/Library/Application Support/Forge`. In the container this file will point to `/data/forge-user`.
- **Forge's own `Main` sends crash reports to Forge's Sentry server** (`Sentry.init` with a built-in DSN). Our
  bootstrap never calls it, and it must stay that way.
- Forge prints a lot of harmless noise to stderr at startup ("card … not assigned to any set", `sun.misc.Unsafe`
  warnings). The real harness should send stderr to `logs/forge.log`.

## S2: Forced starting player

Forge picks a random player, or the previous game's loser, and asks that player's controller
`chooseStartingPlayer()`. The stock AI always answers "me".

`BenchAiPlayer` is a `LobbyPlayerAi` subclass. It overrides `createIngamePlayer` to install a
`PlayerControllerAi` subclass whose `chooseStartingPlayer()` returns the player the scheduler chose. Whichever
player Forge asks, the answer is the same, so the result is forced. **No Forge patch is needed.**
`game.getStartingPlayer()` matched the requested player in every game.

## S3: Concurrent games in one JVM

Throughput, 80 games, mono-red vs mono-green:

| Setup | ms per game | Speed-up |
|---|---|---|
| 1 thread | 315 | 1× |
| 4 threads | 157 | 2.0× |
| 8 threads | 161 | 2.0× |
| 8 threads + `-XX:+DisableExplicitGC` | **110** | **2.9×** |
| 4 separate single-threaded JVMs (2 GB heap each) | ~260 per JVM total | 1.2× |
| 8 separate single-threaded JVMs (1 GB heap each) | ~3,000 | ❌ |

- **`Match.startGame` calls `System.gc()` after every game.** With concurrent games this turns into
  stop-the-world collections that serialize the workers. `-XX:+DisableExplicitGC` removes the bottleneck.
- Even a single game already uses several cores: the AI runs block and play evaluation on "Game AI Eval" threads
  and attack evaluation on a thread pool sized to the CPU count. That is why 8 threads give about 3× rather
  than 8×.
- Threads in one JVM beat separate JVMs clearly. Separate JVMs pay for startup, JIT warm-up and a card database
  each.
- **Correctness bug in Forge when games run concurrently:** `SpellAbility`, `Trigger`, `ReplacementEffect`,
  `StaticAbility`, `SpellAbilityStackInstance`, `CombatView` and `Game` get IDs from
  `private static int maxId; ++maxId` counters shared by all games and not synchronized.
  - The first four use the ID for `equals`/`hashCode`. A lost update between two threads can give two
    abilities in the *same* game the same ID, and Forge then treats them as equal.
  - `Match` also calls `Trigger.resetIDs()` at the start of every game, which resets the counter for games
    already running on other threads.
  - We saw no errors in these runs, but the race is real.
- **Fix:** [`patches/forge/0001-per-game-id-sequences.patch`](../patches/forge/0001-per-game-id-sequences.patch)
  adds `forge.game.GameIds`.
  - All of these counters become atomic, which fixes the race even for Forge's desktop app.
  - A harness can call `GameIds.beginScope()` on the thread that runs a game. That game then gets its own ID
    sequences, starting at 2³⁰ so they never collide with IDs handed out during startup, and its AI threads
    inherit them.
  - **Game** IDs stay global, because `AITimeoutTracker` keys a static registry by game ID. Scoping them made
    concurrent games share a timeout slot, which the first version of the patch did by mistake.
  - The patch is small (~190 lines) and could be offered upstream.

## S4: Reproducibility

Forge draws all game randomness from one global `MyRandom.getRandom()`. To make seeded games repeatable:

1. **Per-game random stream.** `PerThreadRandom` is installed as Forge's global `Random`. It delegates to an
   `InheritableThreadLocal<Random>`, which is reseeded from `(runSeed, gameId)` at the start of each game. The
   AI eval threads inherit the game's stream. Tracing with `-Dforge.undo.debug=true` confirmed that every draw
   comes from the game thread or "Game AI Eval" threads.
2. **`-XX:+UnlockExperimentalVMOptions -XX:hashCode=2`.** Forge iterates over hash sets and maps keyed by
   identity hash codes, which change from run to run. Without this flag, **17–21 of 30** same-seed
   single-threaded games came out differently. With it, **0** did. The cost is about 10%.

Results:

| Comparison (same seeds) | Games differing |
|---|---|
| 1 thread vs 1 thread, default JVM | 17 / 30 |
| 1 thread vs 1 thread, `hashCode=2` | **0 / 80** |
| Games 40–79 replayed in a fresh JVM vs the same games inside an 80-game run | **0 / 40** |
| Game 57 replayed alone | identical |
| 8 threads vs 8 threads (with patch and `hashCode=2`) | 16–17 / 80 |
| 8 threads vs 8 threads, AI time limit raised to 600 s | 17 / 80 (so the AI time limit is not the cause) |

- Comparing full game logs shows multi-threaded games diverging at AI decisions, for example whether to cast a
  pump spell mid-combat.
- The remaining cause is probably that AI evaluations running in parallel within one game consume the shared
  random stream in an order that depends on timing. It's possible, but less likely, that some static state is
  still shared between games. Closing this gap would mean changing Forge's AI threading, so it's not worth
  doing now.

**What this means for the design:**
- Normal runs use threads, for speed. The aggregate statistics are valid, but individual games are not
  reproducible.
- `--deterministic` uses one game at a time per JVM, scaled out with `--shard`. Every game can then be replayed
  exactly with `mtgbench replay --game <id>`.

## S5: Stopping games

Tested with 60 Forest vs 60 Island, which naturally ends by decking on turn 112:

- **Turn limit:** a `@Subscribe` listener on `GameEventTurnBegan` calls `game.setGameOver(GameEndReason.Draw)`.
  The game stops at turn limit + 1.
- **Wall-clock watchdog:** a scheduled task calls `game.setGameOver(Draw)` from another thread. The game loop
  sees it within about 10–80 ms, and the worker thread returns normally.
- ⚠️ **Forge reports these forced draws misleadingly.** A player who hasn't lost counts as having "won", so after
  `setGameOver(Draw)` `GameOutcome.isDraw()` is **false** and `getWinningLobbyPlayer()` returns the first player.
  The harness must decide draws from `getWinCondition() == GameEndReason.Draw`. It must also record its own stop
  reason (`turnCap` / `timeout`) rather than inferring it from the outcome.
- Not covered: an AI stuck in a loop inside one decision that never checks `isGameOver()`. If that happens, the
  watchdog should give up on the thread after a grace period and mark the game `error`. Forge's AI threads are
  daemon threads, so they won't keep the JVM alive.

## Recommended JVM options

```
-Djava.awt.headless=true
-XX:+DisableExplicitGC                                  # Forge calls System.gc() after every game
-XX:+UnlockExperimentalVMOptions -XX:hashCode=2         # reproducible hash iteration order
-Xmx: about 1 GB + 150 MB per worker thread (8 threads peaked at ~1.9 GB used)
```

## Other notes

- Speed: about 110 ms per game with 8 threads for these two fast aggro decks, and about 2 s for a 112-turn game.
  The design's estimate of 1–10 s per game was pessimistic for aggro but is probably about right for control.
- The local build used JDK 27 with `release 17`, and Forge built and ran without problems. The container will
  stay on Temurin 17 to match Forge's target.
- Forge's Maven build must be run from `forge/`, because its `.mvn/maven.config` uses a relative path.
  [`scripts/build-forge.sh`](../scripts/build-forge.sh) handles this and applies the patches idempotently.
- Forge's `.dck` text format accepts `4 Card Name|SET`, so the quick text loader just maps plain-text lines
  onto it. Phase 1 still needs proper validation. **An unknown card name is neither rejected nor dropped:** Forge
  keeps a placeholder "unsupported" card, so the deck still counts 60, logs one stderr line ("An unsupported card
  was requested") and plays the game. DeckLoader has to check every name against the card database itself and
  fail the run.

## Container check

The image is built from [`docker/Dockerfile`](../docker/Dockerfile) (`docker build -f docker/Dockerfile -t mtgbench .`).
It runs the test program as its entrypoint until Phase 1 adds the real CLI. Tested on OrbStack, linux/arm64,
with 8 CPUs and 8 GB:

| Run | Result |
|---|---|
| Build | ✅ About 1 min with a warm Maven cache. The image is **1.18 GB**, most of it Forge's full `res/` tree (Phase 3 can trim unused folders such as music, skins and adventure) |
| 80 games, 1 thread, `--network none` | ✅ 293 ms per game. **Identical to the macOS/JDK 27 run with the same seed (0 of 80 games differ)**, so seeded results carry across OS, CPU and JDK 17 vs 27 |
| 80 games, 8 threads | ✅ 136 ms per game, no errors |
| Decks mounted read-only, `--memory 2g --cpus 4`, 4 threads | ✅ 215 ms per game, no errors |
| Runs as non-root user `bench`, Forge's user and cache dirs under `/data` | ✅ |

Building on arm64 Linux needed a second patch,
[`0002-skip-launch4j.patch`](../patches/forge/0002-skip-launch4j.patch). Forge's desktop module always
wraps a Windows `.exe` with launch4j. Launch4j's bundled `windres` is x86-only, and plugin 2.5.1 has no
command-line switch to skip it.
