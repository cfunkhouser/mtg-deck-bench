# mtg-deck-bench

Benchmark a Magic: The Gathering deck by having [Forge](https://github.com/Card-Forge/forge)'s rules
engine and AI play it against a set of opponent decks, many times, with play/draw balanced exactly.

Design: [docs/DESIGN.md](docs/DESIGN.md). Phase 0 findings about embedding Forge:
[docs/phase0-findings.md](docs/phase0-findings.md).

## Quick start (Docker)

```bash
docker build -f docker/Dockerfile -t mtgbench .
docker run --rm -v "$PWD/examples/decks:/decks:ro" -v "$PWD/results:/data/results" mtgbench \
  run -T /decks/mono-red-burn.txt -O /decks/mono-green-stompy.txt -O /decks/mono-white-humans.txt -n 100
```

To check a built image: `scripts/smoke-test.sh mtgbench` (about a minute). CI runs the same script
(`.github/workflows/ci.yml`).

## From a checkout

Needs JDK 17+, Maven and git.

```bash
git submodule update --init --depth 1
scripts/build-forge.sh            # patch and install the pinned Forge (a few minutes, once)
scripts/mtgbench validate examples/decks/*.txt
scripts/mtgbench run -T examples/decks/mono-red-burn.txt -O examples/decks/mono-green-stompy.txt -n 100
```

`--mode pod` puts the target and all opponents at one free-for-all table instead (at least two
opponents; `-n` is then the total number of games). The target sits in every seat equally often, and
the report shows the win share against the fair share, mean placement and win rate by seat.

Commander works the same way with `-f commander`:

```bash
scripts/mtgbench run -f commander -T examples/decks/commander/krenko-goblins.txt -O examples/decks/commander/ezuri-elves.txt -n 40
```

Commander lists name the commander under a `Commander` header (as in Arena exports), with a `*CMDR*`
marker, with an Archidekt-style `[Commander]` tag, or in the `[Commander]` section of a `.dck` file.
Legality covers 100 cards, singleton, colour identity and the banned list. Commander games take
seconds each, so plan for longer runs than with 60-card decks.

Decks can be plain text lists (`4 Lightning Bolt`, Arena and MTGO exports) or Forge `.dck` files.
Every card is checked against Forge's card database, and decks are checked for format legality
(`--no-legality-check` skips that). `mtgbench run --help` lists all options; options can also come from
a YAML file (`--config`, see DESIGN.md §3.2).

Results go to `results/<runId>/`: `summary.md`, `summary.json`, one JSON line per game in
`games.jsonl`, and the resolved config with deck hashes.

Working with runs:

```bash
scripts/mtgbench resume results/<runId>            # finish an interrupted run
scripts/mtgbench report results/<runId>            # rebuild summary.md / summary.json
scripts/mtgbench replay results/<runId> --game 17  # replay one game and print Forge's game log
```

To spread a run over several machines, give each the same command plus `--shard i/k`, then combine:

```bash
scripts/mtgbench merge results/<shard run 1> results/<shard run 2> ... --out results/merged
```

Add `--deterministic` (one game at a time) when you need every game to be reproducible, for example
for `replay`. Without it, runs use all CPUs: the totals are valid, but single games can't be replayed
exactly.

**Caveat:** both sides are played by Forge's AI. The numbers show how well Forge's AI pilots each deck,
which can differ a lot from human play.

## License

Not chosen yet. Forge is GPL-3.0 and this project links it, so a distributed build (including a public
container image) must use a GPL-3.0-compatible license.
