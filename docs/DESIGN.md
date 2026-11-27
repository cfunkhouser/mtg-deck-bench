# mtg-deck-bench — Design

A headless, containerized playtest simulator that benchmarks a target deck **T** against
one or more opponent decks **B₁…Bₖ** by having Forge's AI play **N** games per matchup
under Forge's rules engine, then reporting per-matchup statistics and one overall score.

## 1. Requirements (as agreed)

| # | Requirement | Decision |
|---|---|---|
| R1 | Rules engine | [Card-Forge/forge](https://github.com/Card-Forge/forge), embedded as a library (not shelled out to) |
| R2 | Inputs | One target deck T, ≥1 opponent decks B, game count N |
| R3 | Deck formats | Plain text (MTGO / Arena export) **and** Forge `.dck` |
| R4 | Game structure | Two modes: **gauntlet** (T vs each Bᵢ head-to-head) and **pod** (T + all B at one free-for-all table) |
| R5 | Starting position | **Balanced**: gauntlet → exactly ⌈N/2⌉ on the play / ⌊N/2⌋ on the draw per matchup; pod → T occupies every seat equally often |
| R6 | Output | Per-game records, per-matchup stats, overall benchmark score for T |
| R7 | Runtime | Runs non-interactively inside a container |
| R8 | Stack | Java 17, Maven, linking Forge modules directly |
| R9 | Formats | **Constructed** (60-card, 20 life) and **Commander** (100-card singleton, commander in the command zone, 40 life, 21 commander damage), both in gauntlet and pod mode. See §2.2 |

## 2. Architecture

```
               ┌──────────────────────────────────────────────────────────┐
  decks/*.txt  │ mtgbench (single JVM)                                    │
  decks/*.dck ─┼─▶ DeckLoader ──▶ Deck objects (validated vs card DB)     │
  bench.yaml   │                     │                                    │
  CLI flags  ──┼─▶ BenchConfig ──▶ Scheduler ──▶ GameSpec queue           │
               │                     (play/draw + seat balancing, seeds)  │
               │                                   │                      │
               │                       ┌───────────┴──────────┐           │
               │                       ▼          ▼           ▼           │
               │                  GameRunner  GameRunner  GameRunner      │
               │                  (worker threads, each runs one Forge    │
               │                   Match/Game with AI vs AI, timeout)     │
               │                       └───────────┬──────────┘           │
               │                                   ▼                      │
               │                     GameRecord stream ──▶ games.jsonl    │
               │                                   ▼                      │
               │                     Aggregator ──▶ Scoring ──▶ Reports   │
               └──────────────────────────────────────────────────────────┘
                     ▲ ForgeBootstrap: headless GUI stub, card DB, AI profiles
```

### 2.1 Components

**ForgeBootstrap** — One-time JVM initialization.
- Installs a headless `IGuiBase` implementation (no Swing; `-Djava.awt.headless=true`).
- Points Forge at its `res/` directory (card scripts, editions, AI profiles) and at a writable
  user dir (`FORGE_USER_DIR`, default `/data/forge-user`) so Forge never writes to `$HOME`.
- Calls `FModel.initialize(...)` to load the card database once. It is shared read-only by all workers.

**DeckLoader** — Converts input files into Forge `Deck` objects.
- `.dck` → `DeckSerializer.fromFile`.
- Plain text → Forge's own `DeckRecognizer` (it already handles `4 Lightning Bolt`,
  `4 Lightning Bolt (M11) 149`, `Sideboard`/`Commander` sections, and Arena exports).
- Validation, which fails fast with a list of problems:
  - unknown card names (checked by us: Forge substitutes a placeholder card and plays on);
  - cards Forge doesn't implement;
  - deck legality for the chosen format (optional, `--check-legality`).
- Warnings for cards flagged `AI:RemoveDeck`, which the Forge AI cannot play well. Results for
  decks with these cards are less trustworthy.

**Scheduler** — Expands the config into a deterministic list of `GameSpec`s:
```
GameSpec { gameId, matchupId, players[] (deck + seat), startingSeat, seed }
```
- *Gauntlet:* for each Bᵢ, N specs. T starts in specs with even index, Bᵢ in odd ones.
  This guarantees the play/draw balance by construction rather than by chance.
- *Pod (P players):* N specs. T's seat cycles through 0..P-1, and the opponents' order is
  rotated or permuted so that each opponent spends equal time immediately to T's left and right.
  N is rounded up to a multiple of P, and the tool warns when it does this.
- Each spec gets `seed = hash(runSeed, gameId)`. In `--deterministic` runs (one game at a time per JVM),
  any game can be replayed exactly on its own (`replay --game <id>`). Multi-threaded runs are statistically
  valid but not reproducible game by game (see §9).

**GameRunner** — Runs one `GameSpec` to completion.
- Builds `GameRules` (`GameType.Constructed` or `GameType.Commander`), registers AI players
  (`GamePlayerUtil.createAiPlayer` with a configurable AI profile), creates a `Match`, and starts one `Game`.
- **Forces the starting player** from `GameSpec.startingSeat`. Forge normally flips a coin, so this
  needs a hook. See Spike S2.
- Applies limits. A game that hits either limit is recorded as `outcome=timeout`, not as a loss:
  - a round cap (default 20 rounds; Forge counts each player's turn, so this is converted to turns);
  - a wall-clock timeout (default 120 s for constructed, 600 s for Commander).
- Reads `GameOutcome` / `PlayerStatistic` into a `GameRecord`.

**Worker pool** — A fixed-size thread pool (`--threads`, default = CPU count). Games run independently.
If Spike S3 finds shared mutable state that isn't thread-safe, the fallback is **process sharding**:
`--shard i/k` runs only specs where `gameId % k == i`, and `mtgbench merge` combines the outputs.
Sharding also lets the run spread across containers, for example as a Kubernetes Indexed Job.

**Aggregator / Scoring / Reports** — These are pure functions over `GameRecord`s. They are testable
without Forge and can be re-run offline: `mtgbench report results/run-…/games.jsonl`.

### 2.2 Commander support

Forge's engine already implements the Commander rules: the `GameType.Commander` game type, the command
zone and commander tax, 40 starting life (`RegisteredPlayer.forCommander`), losing to 21 combat damage
from one commander (`GameLossReason.CommanderDamage`), and free-for-all games with more than two players.
The harness has to select that game type and feed it correctly. It must not reimplement any of these rules.

| Area | What the harness does |
|---|---|
| Format selection | `format: commander` picks `GameType.Commander` (plus `setAppliedVariants`) and registers every player with `RegisteredPlayer.forCommander(deck)` instead of `new RegisteredPlayer(deck)` |
| Deck input | Text lists: a `Commander` section, or per-line commander markers used by deck-site exports (e.g. `1 Name *CMDR*`). Forge's `DeckRecognizer` doesn't know these markers, so our loader handles them itself. Partner and Background pairs give two commanders. `.dck`: the `[Commander]` section. If a Commander-format deck names no commander, the run fails with a clear message. The loader never guesses |
| Validation | Before any game: 100 cards including the commanders, singleton except basic lands and cards that allow more copies, color identity, and the Commander banned list. All checks use Forge's own Commander rules, so they match the pinned card data. Forge's `getDeckConformanceProblem` skips the banned list, which lives in a per-card filter, so the loader also checks every card with `DeckFormat.isLegalCard`. `--no-legality-check` skips all of this for brews and proxies |
| Play/draw and seats | Gauntlet (1v1 Commander) uses the same exact play/draw balance as constructed. Pod mode rotates seats (§2.1 Scheduler). The forced-first-player hook from S2 is reused, but must be re-tested with more than 2 players |
| Turn limits | Forge's turn counter counts every player's turn. Limits are therefore set in **rounds** (`maxRounds`) and converted to turns × player count. Commander defaults are higher: 20 rounds, 10 min wall-clock per game |
| Out of scope | Commander-specific house rules (free mulligan, a rule-0 power bracket), Brawl, Oathbreaker and Planechase. Forge supports some of these, so they're possible later additions |

Commander games are much longer than 60-card aggro games: 40 life, larger boards and more players. Expect
several seconds to tens of seconds per game, rather than 0.1–0.3 s. Plan sample sizes accordingly (§4.4).

## 3. Data model

### 3.1 GameRecord (one JSON line per game)
```json
{
  "runId": "2026-11-26T14-03-11Z-ab12",
  "gameId": 137,
  "mode": "gauntlet",
  "matchup": "T:mono-red-aggro vs B:azorius-control",
  "seed": 884213377,
  "players": [
    {"seat": 0, "deck": "mono-red-aggro", "isTarget": true,  "onPlay": true,
     "mulligans": 0, "finalLife": 3, "lossReason": null, "eliminatedTurn": null},
    {"seat": 1, "deck": "azorius-control", "isTarget": false, "onPlay": false,
     "mulligans": 1, "finalLife": 0, "lossReason": "LIFE", "eliminatedTurn": 6}
  ],
  "winnerSeat": 0,
  "outcome": "win",            // win | draw | timeout | error  (from T's perspective in gauntlet)
  "turns": 6,
  "durationMs": 4120,
  "forgeVersion": "2.0.x",
  "error": null
}
```

### 3.2 Configuration (`bench.yaml`; every field can also be set with a CLI flag)
```yaml
mode: gauntlet            # gauntlet | pod
format: constructed       # constructed | commander
games: 200                # N: per matchup (gauntlet) or total (pod); the first batch when targetCi is set
targetCi: 0.05            # optional: add games until every matchup's 95% CI is within ±5 pp (§4.4)
maxGames: 500             # optional, with targetCi: cap per matchup (default: a 50% matchup's need + 25%)
target: decks/mono-red.txt
opponents:
  - path: decks/azorius-control.dck
    weight: 0.30          # optional metagame share (gauntlet scoring)
  - path: decks/golgari-midrange.txt
    weight: 0.70
ai:
  profile: Default        # Forge AI profile for all seats (per-deck override allowed)
limits:
  maxRounds: 20           # one round = every player takes a turn
  gameTimeoutSec: 120     # default 600 when format: commander
checkLegality: true       # format rules (Commander: 100 cards, singleton, color identity, banned list)
seed: 42
threads: 8
output: /data/results
```

## 4. Statistics and scoring

### 4.1 Per matchup (gauntlet)
- **Win rate** `w = (wins + 0.5·draws) / decided`. Timeouts and errors are left out of
  `decided` and reported separately. If they go above 5%, the matchup is flagged.
- 95% **Wilson** confidence interval on `w`.
- Split **on the play / on the draw**: win rate for each, and the difference.
- Average and distribution of game length in turns. Win turn histogram for T's wins.
- Mulligan rate for T and for the opponent, and win rate after a mulligan.
- How T loses (life / poison / decking / commander damage / other).
- Commander only (implemented in Phase 1b):
  - T's wins and losses decided by commander damage;
  - how often T casts its commander, casts per game, and the average round of the first cast. These come
    from `Player.getTotalCommanderCast()`, checked at each turn start. Per player, the game record also
    keeps the most commander damage taken from any one commander.

### 4.2 Overall gauntlet score
```
Score = 100 × Σᵢ ωᵢ · wᵢ        ωᵢ = weightᵢ / Σ weight   (equal weights by default)
```
- The CI is propagated as `√(Σ ωᵢ² · Var(wᵢ))`.
- The report also gives:
  - the **worst matchup**;
  - the **unweighted mean**;
  - the **min-max range**, so one strong matchup can't hide a bad one.

### 4.3 Pod mode (P players)
- **Win share** `s = wins_T / decided`, compared with the fair baseline `1/P`.
- **Pod score** = `100 × s · P / 2`, capped at 100. This maps the fair share to 50, the same scale
  as a 50% gauntlet win rate.
- **Mean placement**, from elimination order: 1 = winner, P = first eliminated.
- Win rate by seat, to show turn-order advantage.
- Head-to-head: when T is eliminated, which opponent dealt the lethal blow. This is the controller of
  the last card that damaged T (from Forge's damage events), recorded for losses to life or commander
  damage; other losses (milled, poison, alternate win) have none. Also reported: which deck won the games
  T lost.

### 4.4 Sample size guidance
The report prints the CI half-width. As a rule of thumb, N=100 gives about ±10 pp and N=400 gives
about ±5 pp at w≈50%. Lopsided matchups need far fewer games (±5 pp at w≈90% takes about 140).

**Adaptive N** (`--target-ci H`, Phase 5). Instead of a fixed N, keep adding games until every matchup's
95% Wilson half-width is at most H. In pod mode the one "matchup" is the table's win share.
- `--games` is the first batch per matchup. `--max-games` caps each matchup. The default cap is the
  decided games a 50% matchup needs, plus 25% for timeouts and errors: 482 games for H = 0.05.
- **Stages.** Each matchup grows in stages. When a stage is fully played, its CI is checked. If the CI is too wide, the next stage is
  projected from the observed win rate and share of decided games. Each stage grows by at least 25% and at most
  doubles, so batches stay big enough to keep every thread busy. Finished matchups stop while the others go on.
- **Balance.** Stages after the first are multiples of 2 (gauntlet) or of P (pod). Every stopping point
  therefore keeps the exact play/draw split or seat rotation.
- **Resume and reproducibility.** Only games inside fully played stages count, and the plan is recomputed from
  `games.jsonl` alone. `resume` needs no extra state, and a `--deterministic` run makes the same stopping
  decisions every time.
- **No sharding.** `--shard` can't be combined with `--target-ci`, because when to stop depends on every result.
- **Optional stopping.** Stopping on the CI's *width* (not on where the estimate is) barely biases the
  estimate. A matchup's stopping point does depend weakly on its observed rate, because rates near 50% need
  more games, so a reported CI's real coverage can be a little under 95%.
- **Report.** `summary.md`/`summary.json` say whether every matchup reached the target or which ones hit the
  cap first.

## 5. Outputs

```
results/<runId>/
  config.resolved.yaml     # fully expanded config, deck paths + SHA-256, Forge version
  games.jsonl              # one GameRecord per line, streamed and flushed (crash-safe)
  summary.json             # machine-readable aggregates + score
  summary.md               # human-readable report (tables per matchup)
  logs/forge.log           # everything Forge prints
  logs/games/game-NNNNNN.log   # full Forge game log per game, with --game-logs
```
Exit codes: 0 OK, 1 bad arguments or config, 2 a deck has problems, 3 more than `--max-error-rate`
(default 5%) of games failed, so CI pipelines can gate on it.
`resume`, `replay`, `report` and `merge` work from the run directory alone, using `config.resolved.yaml`
and `games.jsonl`. `resume` and `replay` re-read the deck files and refuse to continue if any file's
SHA-256 changed. `merge` refuses runs that differ in seed, decks, settings or Forge version.
`replay` exits with 4 if a deterministic run doesn't reproduce.

## 6. CLI

```bash
mtgbench run --target decks/red.txt --opponent decks/uw.dck --opponent decks/bg.txt \
             --weights 0.6,0.4 --games 200 --threads 8 --out /data/results     # Phase 1 ✅
mtgbench run --config bench.yaml                                              # Phase 1 ✅
mtgbench run ... --deterministic --seed 42   # one game at a time, every game reproducible  ✅
mtgbench validate decks/*.txt                # cards known to Forge + format legality      ✅
mtgbench fetch https://archidekt.com/decks/123456/x decks/x.txt   # download + validate   Phase 5 ✅
mtgbench run ... --shard 0/4                 # play every 4th game (machine 1 of 4)       ✅
mtgbench run ... --target-ci 0.05 [--max-games 500]   # add games until each CI is ±5 pp   Phase 5 ✅
mtgbench merge results/<shard runs>... --out results/merged                   # Phase 2 ✅
mtgbench resume results/<runId>              # play only the games missing from games.jsonl ✅
mtgbench replay results/<runId> --game 137   # replay one game, print Forge's game log    ✅
mtgbench report results/<runId>              # rebuild summary.md/json from games.jsonl     ✅
```
Built with picocli. `mtgbench run --help` lists every option. From a source checkout, use
`scripts/mtgbench`; in the container the image's entrypoint is `mtgbench`.

## 7. Repository layout

```
mtg-deck-bench/
  forge/                        # git submodule, pinned to a Forge release tag
  bench/                        # Maven module (Java 17)
    pom.xml                     # depends on forge-gui-desktop (installed from the submodule)
    src/main/java/mtgbench/
      cli/        BenchCli, RunCommand, ValidateCommand, FetchCommand, ...
      config/     BenchConfig (resolved), ConfigFile (bench.yaml), Format
      deck/       DeckLoader, LoadedDeck, DeckException
      fetch/      Archidekt (API client, URL parsing), ArchidektDeck (JSON to deck sections and text)
      engine/     ForgeBootstrap, HeadlessGui, GameRunner, BenchAiPlayer, PerThreadRandom
      schedule/   Scheduler, GameSpec
      run/        BenchRun (thread pool, outputs), ForgeOutput (log redirection)
      stats/      GameRecord, Aggregator (win rates, Wilson CI, score), Summary
      report/     Reports (JSON/YAML/JSONL/Markdown)
    src/test/java/...           # unit tests + Forge-backed tests (deck loading, real games)
  patches/forge/                # our Forge patches, applied by scripts/build-forge.sh
  scripts/                      # build-forge.sh, mtgbench (run from a checkout)
  docker/Dockerfile
  examples/decks/               # sample decks for smoke tests
  docs/                         # DESIGN.md, phase0-findings.md
  mise.toml, mise.lock          # pinned dev tools (JDK, Maven, actionlint) and tasks; CI uses them too
```

**Forge dependency.** Forge isn't published to a dependable public Maven repository, so the build
works like this:
1. Build Forge from the pinned submodule with our patches: `scripts/build-forge.sh`.
2. `bench/` resolves the Forge modules from the local repository.
3. Updating Forge means bumping the submodule tag, then re-running the smoke tests.

The `res/` tree (card scripts and so on) comes from the same submodule, so card data and engine code
always match.

## 8. Container

A multi-stage Dockerfile. The real one is [`docker/Dockerfile`](../docker/Dockerfile), built and tested in
Phase 0 (see [phase0-findings.md](phase0-findings.md#container-check)). Outline:

```dockerfile
# Stage 1: build Forge + harness
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY forge/ forge/
COPY patches/ patches/
COPY scripts/build-forge.sh scripts/
RUN scripts/build-forge.sh        # applies patches/forge/*.patch, then mvn install
RUN scripts/trim-res.sh forge/forge-gui/res /out/res   # drop unused res/ folders
COPY bench/ bench/
RUN mvn -q -f bench/pom.xml package -DskipTests     # shaded jar

# Stage 2: runtime
FROM eclipse-temurin:17-jre
RUN useradd -m bench
COPY --from=build /src/forge/forge-gui/res /opt/forge/res
COPY --from=build /src/bench/target/mtgbench.jar /opt/mtgbench/mtgbench.jar
ENV FORGE_RES_DIR=/opt/forge/res FORGE_USER_DIR=/data/forge-user \
    JAVA_TOOL_OPTIONS="-Djava.awt.headless=true -XX:MaxRAMPercentage=75 -XX:+DisableExplicitGC -XX:+UnlockExperimentalVMOptions -XX:hashCode=2"
USER bench
VOLUME ["/data"]
ENTRYPOINT ["java", "-jar", "/opt/mtgbench/mtgbench.jar"]
```

Usage:
```bash
docker run --rm -v "$PWD/decks:/decks:ro" -v "$PWD/results:/data/results" mtgbench \
  run --target /decks/red.txt --opponent /decks/uw.dck --games 200
```
- The card DB costs about 1–1.5 GB of heap before any game runs. Plan for 2 GB plus roughly
  100–200 MB per worker thread.
- The container needs no network access at runtime.
- Docker Compose and Kubernetes Job examples (including sharded runs) will live in `docker/`.
- **Docker or podman.** The image builds and runs under both. `scripts/container-engine` picks the engine for
  `mise run image:build` and `scripts/smoke-test.sh`: `$CONTAINER_ENGINE` if set, else Docker when its daemon
  is reachable, else podman. CI uses Docker. Under rootless podman the image's user (uid 10001) maps to a
  subuid, so pass `--userns=keep-id:uid=10001,gid=10001` to give a bind-mounted results directory to the
  host user.
- **Maven's local repository stays in the build stage's layers** (`MAVEN_ARGS=-Dmaven.repo.local=/src/.m2`),
  not in a BuildKit cache mount. A cache mount's contents aren't exported with the layer cache, so a cached
  `build-forge.sh` layer restored in CI would come back without the patched Forge it installed.

## 9. Phase 0 results (done)

Details and measurements: [phase0-findings.md](phase0-findings.md).

| Spike | Outcome | Design consequence |
|---|---|---|
| S1 Headless start | ✅ `HeadlessGui` + `FModel.initialize`. Starts in ~3.7 s | Never call `Sentry.init`. Point Forge's user and cache dirs at `/data` via `forge.profile.properties` |
| S2 Forced first player | ✅ `BenchAiPlayer` overrides `chooseStartingPlayer`, no patch needed | Play/draw balance is exact |
| S3 Concurrent games | ⚠️ Safe only with the patch [`0001-per-game-id-sequences`](../patches/forge/0001-per-game-id-sequences.patch) (shared static ID counters). 2.9× speed-up at 8 threads with `-XX:+DisableExplicitGC` | Threads are the default. `scripts/build-forge.sh` applies the patch |
| S4 Reproducibility | ⚠️ Exact with one game at a time per JVM (`PerThreadRandom` + `-XX:hashCode=2`), including single-game replay. Not reproducible game by game when multi-threaded | Add a `--deterministic` mode (1 thread, scale out with `--shard`). Replay is exact only for deterministic runs |
| S5 Stopping games | ✅ Turn-limit listener + watchdog calling `setGameOver(Draw)` | Classify draws from `GameEndReason.Draw` and record our own stop reason. `GameOutcome.isDraw()` is wrong for forced draws |

## 10. Delivery plan

1. **Phase 0 — Spikes S1–S5. ✅ Done** (see §9). The container image also builds and runs.
2. **Phase 1 — Gauntlet MVP (constructed). ✅ Done.**
   - DeckLoader (txt + dck), Scheduler, GameRunner, thread pool, `games.jsonl`, basic win-rate summary.
   - The `format` setting goes into the engine from the start, so Commander doesn't need rework later.
   - Also delivered early from Phase 2:
     - Wilson CIs and the play/draw split;
     - the weighted score with its CI;
     - `summary.md` / `summary.json`;
     - `--deterministic`;
     - the `validate` command.
   - The Dockerfile now runs the real CLI.
3. **Phase 1b — Commander 1v1. ✅ Done.** This comes early because it tests the engine side of Commander
   before any multiplayer work.
   - `format: commander` sets up `GameType.Commander` and uses `forCommander` registration.
   - Commander sections and commander markers in text lists. Collect real exports from the deck sites we
     want to support and use them as test fixtures.
   - Commander legality checks.
   - Limits in rounds.
   - Two example Commander decks in `examples/decks/commander/`.
   - Done when a 1v1 Commander gauntlet runs: commanders start in the command zone, players start at
     40 life, and at least one game in the test set ends by commander damage, or the commander-damage
     loss is shown in a unit test with a stacked deck.
   - Result:
     - `CommanderGameTest` stacks a game (Isamaru against an uncastable Progenitus). The opponent loses
       to commander damage at exactly 18 life after 22 commander damage, with Isamaru cast in round 1.
     - The example Krenko vs Ezuri gauntlet runs. Its 40 games took ~58 s on 8 threads, about 7 s of CPU
       per game, roughly 25× the 60-card aggro decks.
     - Deterministic Commander runs give identical games on macOS and in the container.
   - Still open:
     - The `*CMDR*` and Archidekt-tag formats were written from the formats' conventions. Add real
       exports from the deck sites you use as test fixtures.
     - Commander's free first mulligan (a multiplayer house rule) is not applied.
4. **Phase 2 — Statistics and reports. ✅ Done.**
   - Remaining statistics: mulligan rates and win rate after a mulligan, turn histograms, loss reasons
     (including Commander).
   - `report`, `replay`, `resume`, `merge` and `--shard` commands.
   - Result:
     - Game length is reported in **rounds** (one turn per player), not Forge turns.
     - `summary.md` gained Mulligans, How games ended and Game length sections; the console keeps
       the compact table.
     - Checked end to end on deterministic runs:
       - 3 shards merged give the same games and summary as one full run;
       - a run cut off mid-line and resumed matches the full run (24 of 24 games);
       - `replay` reproduces a recorded game.
5. **Phase 3 — Container in CI. ✅ Done** (not yet run on GitHub: the repo has no remote).
   - A GitHub Actions build;
   - a smoke test running a 10-game constructed gauntlet and a 4-game Commander gauntlet in the image;
   - a smaller image by trimming unused `res/` folders.
   - Result:
     - `.github/workflows/ci.yml` has two parallel jobs:
       - **test** builds patched Forge, cached in `~/.m2` and keyed on the Forge commit and our patches,
         then runs `mvn verify`;
       - **image** builds the Docker image with the GitHub Actions layer cache, then runs
         `scripts/smoke-test.sh`.
     - The smoke test validates the example decks and checks that a bad deck exits with code 2. It plays
       10 constructed and 4 Commander games, checks every game and both summaries, and checks that
       `replay` reproduces a game. It takes about 40 s locally.
     - `scripts/trim-res.sh` drops game modes, UI assets and other languages from `res/`: 473 MB down to
       168 MB, and the image from 1.19 GB to 707 MB. 20 seeded games came out identical with the trimmed
       and the full `res/`.
     - `deckgendecks/` stays, because Forge logs errors at startup without it.
     - The workflow passes actionlint.
     - The **test** job ran locally with `act`: 2 min for the Forge build, 35 s for the 48 tests.
     - The **image** job's steps were run directly (`docker build` + `scripts/smoke-test.sh`). It wasn't
       run under `act`, because the GitHub Actions layer cache only exists on GitHub.
     - Not done: publishing the image to a registry. The license is now chosen (GPL-3.0-only, §11), so
       publishing isn't blocked on that any more.
6. **Phase 4 — Pod mode (multiplayer), constructed and Commander. ✅ Done.**
   - Seat rotation, re-testing the forced first player with 3–4 players.
   - Placement and per-seat statistics, and who eliminated T.
   - Pod score (§4.3).
   - The main use case is Commander pods of 4.
   - Result:
     - **Seating:** `--mode pod` seats the target and all opponents at one table. Seat 0 always goes first,
       so seat = turn position. The target's seat cycles fastest, so every block of P games covers every
       seat. Opponent order rotates every P games, so every opponent sits directly before and after the
       target equally often over P×(P−1) games. Game counts are rounded up to a multiple of P, with a note.
     - **Forced first player with 3–4 players:** correct in every game of `PodGameTest`, which plays real
       4-player games.
     - **Per-player records:**
       - `placement`, from Forge's `Game.getLostPlayers()` order;
       - `eliminatedRound`, checked at each turn start, because Forge fires no "player lost" event;
       - `eliminatedBySeat`: the seat whose card last damaged that player, from `GameEventPlayerDamaged`,
         set only for losses to life or commander damage.
     - **Pod summary:**
       - win share against the fair share and the pod score (§4.3);
       - mean placement and how often each place was reached;
       - win rate by seat;
       - wins per deck;
       - who won and who dealt the last damage when the target lost;
       - the gauntlet's mulligan, end-of-game, game-length and Commander sections over the whole table.
     - **Example data:** two more Commander example decks (Talrand, Ayara) make a 4-player pod possible.
     - **Smoke test:** also plays a 3-game constructed pod.
7. **Phase 5 — Nice-to-haves.**
   - **Adaptive N (`--target-ci`). ✅ Done** (§4.4).
     - `AdaptivePlan` is a pure planner. Its tests simulate whole runs with scripted outcomes: lopsided matchups
       stop early, timeouts don't count toward the CI, the cap is respected, pods stop on whole rotations, and
       resuming from 25 random interruptions ends with exactly the games of the uninterrupted run.
     - Checked end to end with real games: a deterministic adaptive run, killed partway and resumed, gives
       the same games as the same run uninterrupted.
   - **Deck import from Archidekt (`fetch URL OUT`). ✅ Done.**
     - Reads Archidekt's public JSON API (`/api/decks/<id>/`) and writes a plain-text list with `Commander`,
       `Deck` and `Sideboard` headers, card names only (Forge picks the printing). It then runs the same
       checks as `validate`, unless `--no-validate`; the file is kept even when they fail.
     - Sections come from Archidekt's categories: the premier category is the commander; cards in a category
       not counted in the deck (Maybeboard and the like) go to the sideboard, except when the deck is checked
       as Commander, where they are left out (`--maybeboard` / `--no-maybeboard` override); the Sideboard
       category and the companion go to the sideboard; the rest is the main deck. Archidekt marks its Sideboard category as counted in the deck, so it is matched by name.
     - Two-faced cards are written by their front face, split and aftermath cards by both halves, which is
       how Forge names them. Custom cards are left out with a note.
     - Checked against the 60 most viewed Archidekt Commander decks: every card name resolved in Forge. The
       only failures were real deck problems, and Maybeboards over 10 cards, which Commander legality counts
       as a sideboard (hence leaving them out for Commander by default).
   - Still to do: per-deck AI profiles, an HTML report, and Bo3 with sideboarding.
   - Commander variants such as Brawl and Oathbreaker, plus house rules (free first mulligan).

## 11. Risks and caveats

- **The AI is the pilot.** Results measure how well *Forge's AI* plays each deck, not how good the
  deck is in human hands. Combo and control decks with complex lines are usually undervalued. The
  report states this, and it shows the AI-unplayable card warnings from DeckLoader.
- **Commander AI.** Forge's AI plays multiplayer noticeably worse than 1v1. It doesn't negotiate or
  do politics, and it chooses attack targets with simple heuristics. Pod results lean even more on
  AI piloting than gauntlet results, and the report says so.
- **Card coverage.** Very new sets or unusual cards may not be implemented in the pinned Forge
  version. Validation fails fast and names the cards.
- **Licensing.** Forge is **GPL-3.0**. A harness that links it and is distributed, including as a
  public container image, must be GPL-3.0-compatible. This repo is licensed **GPL-3.0-only** (`LICENSE`,
  declared in `bench/pom.xml` and as the image's `org.opencontainers.image.licenses` label). Publishing the
  image means also offering the corresponding source, including the pinned Forge commit and our patches.
- **Performance.** A typical AI-vs-AI game takes about 1–10 s. 5 opponents × 400 games ≈ 2,000 games,
  which is a few minutes on 8 cores if S3 succeeds.
- **Forge API drift.** Forge's internal APIs aren't stable. Pinning the submodule and keeping all
  Forge calls in `engine/` limits the impact of upgrades.
