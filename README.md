# mtg-deck-bench

Benchmark a Magic: The Gathering deck by having [Forge](https://github.com/Card-Forge/forge)'s rules
engine and AI play it against a set of opponent decks, many times, with play/draw balanced exactly.

Design: [docs/DESIGN.md](docs/DESIGN.md). Phase 0 findings about embedding Forge:
[docs/phase0-findings.md](docs/phase0-findings.md).

## Quick start

Tools are pinned with [mise](https://mise.en.dev/) in `mise.toml`: Temurin 17, Maven and actionlint, with exact
versions and checksums in `mise.lock`. Install mise, then from the repo root:

```bash
mise trust                 # once per checkout: allow this repo's mise.toml
mise run setup             # install the pinned tools, then patch and install Forge (a few minutes, once)
mise run validate          # check the example decks
mise run mtgbench -- run -T examples/decks/mono-red-burn.txt -O examples/decks/mono-green-stompy.txt -n 100
```

`mise run mtgbench -- ARGS` runs the CLI with the pinned JDK, building it first when the sources changed.
Everything after `--` goes to mtgbench. mise runs tasks from the repo root, so relative paths start there.
`mise run mtgbench -- run --help` lists every option.

Development tasks (`mise tasks` lists them all):

```bash
mise run test              # unit and Forge-backed tests (about 2 minutes)
mise run lint              # lint the GitHub Actions workflows
mise run ci                # everything the CI test job runs
mise run smoke             # build the container image and smoke-test it
```

`--mode pod` puts the target and all opponents at one free-for-all table instead (at least two
opponents; `-n` is then the total number of games). The target sits in every seat equally often, and
the report shows the win share against the fair share, mean placement and win rate by seat.

Commander works the same way with `-f commander`:

```bash
mise run mtgbench -- run -f commander -T examples/decks/commander/krenko-goblins.txt \
  -O examples/decks/commander/ezuri-elves.txt -n 40
```

Commander lists name the commander under a `Commander` header (as in Arena exports), with a `*CMDR*`
marker, with an Archidekt-style `[Commander]` tag, or in the `[Commander]` section of a `.dck` file.
Legality covers 100 cards, singleton, colour identity and the banned list. Commander games take
seconds each, so plan for longer runs than with 60-card decks.

Decks can be plain text lists (`4 Lightning Bolt`, Arena and MTGO exports) or Forge `.dck` files.
To get a deck from [Archidekt](https://archidekt.com/), give `fetch` its URL and a file to write:

```bash
mise run mtgbench -- fetch https://archidekt.com/decks/123456/my-deck examples/decks/my-deck.txt
```

`fetch` writes the commander, main deck and sideboard as a text list, then checks it like `validate` does.
Cards in Archidekt categories not counted in the deck, such as Maybeboard, go in the sideboard, except in
Commander decks, where they are left out: Commander legality allows at most 10 sideboard cards and counts
them for singleton. `--maybeboard` and `--no-maybeboard` choose either way. The deck must be public.

Every card is checked against Forge's card database, and decks are checked for format legality
(`--no-legality-check` skips that). Options can also come from a YAML file (`--config`, see DESIGN.md §3.2).

Results go to `results/<runId>/`: `summary.md`, `summary.json`, one JSON line per game in
`games.jsonl`, and the resolved config with deck hashes.

Working with runs:

```bash
mise run mtgbench -- resume results/<runId>            # finish an interrupted run
mise run mtgbench -- report results/<runId>            # rebuild summary.md / summary.json
mise run mtgbench -- replay results/<runId> --game 17  # replay one game and print Forge's game log
```

To spread a run over several machines, give each the same command plus `--shard i/k`, then combine:

```bash
mise run mtgbench -- merge results/<shard run 1> results/<shard run 2> ... --out results/merged
```

Not sure how many games you need? `--target-ci 0.05` keeps adding games until every matchup's 95% CI is
within ±5 percentage points. `-n` is then the first batch, and `--max-games` caps each matchup. Lopsided matchups
stop early, and close ones keep going. The summary says which matchups, if any, hit the cap first.

Add `--deterministic` (one game at a time) when you need every game to be reproducible, for example
for `replay`. Without it, runs use all CPUs: the totals are valid, but single games can't be replayed
exactly.

**Caveat:** both sides are played by Forge's AI. The numbers show how well Forge's AI pilots each deck,
which can differ a lot from human play.

## Container

The image needs no network access when it runs. Build it with `mise run image:build` (tag `mtgbench`). The
build uses Docker when its daemon is reachable and podman otherwise; set `CONTAINER_ENGINE=docker` or
`CONTAINER_ENGINE=podman` to choose. `mise run smoke` builds the image and checks it in about a minute with
`scripts/smoke-test.sh`, the same script CI runs with Docker.

With Docker:

```bash
docker run --rm -v "$PWD/examples/decks:/decks:ro" -v "$PWD/results:/data/results" mtgbench \
  run -T /decks/mono-red-burn.txt -O /decks/mono-green-stompy.txt -O /decks/mono-white-humans.txt -n 100
```

With rootless podman, which needs no daemon and no `docker` group, add `--userns=keep-id:uid=10001,gid=10001`.
That maps the image's user to you, so it can write to `results/` and the files it writes are owned by you:

```bash
mkdir -p results
podman run --rm --userns=keep-id:uid=10001,gid=10001 \
  -v "$PWD/examples/decks:/decks:ro" -v "$PWD/results:/data/results" mtgbench \
  run -T /decks/mono-red-burn.txt -O /decks/mono-green-stompy.txt -O /decks/mono-white-humans.txt -n 100
```

## Without mise

The mise tasks wrap the scripts in `scripts/`, which also work on their own. They need JDK 17+, Maven 3.8.1+
and git on the PATH:

```bash
scripts/build-forge.sh                  # same as mise run forge:build
scripts/mtgbench validate examples/decks/*.txt
scripts/mtgbench run -T examples/decks/mono-red-burn.txt -O examples/decks/mono-green-stompy.txt -n 100
```

## License

mtg-deck-bench is free software, licensed under the GNU General Public License, version 3 only
(`GPL-3.0-only`); see [LICENSE](LICENSE). It links [Forge](https://github.com/Card-Forge/forge), which is
also GPL-3.0. Anyone who distributes mtg-deck-bench, including as a container image, must do so under the
same terms, which means offering the corresponding source of both mtg-deck-bench and the patched Forge it
includes.
