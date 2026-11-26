#!/usr/bin/env bash
# Smoke test for a built mtgbench image: validate decks, play a short constructed and Commander
# gauntlet, and replay a game. Usage: scripts/smoke-test.sh IMAGE
set -euo pipefail
image=${1:?usage: smoke-test.sh IMAGE}
data=$(mktemp -d)
chmod 777 "$data"   # the image runs as a non-root user
trap 'rm -rf "$data"' EXIT
decks=/opt/mtgbench/examples/decks
run() { docker run --rm --network none -v "$data:/data" "$image" "$@"; }
fail() { echo "SMOKE TEST FAILED: $*" >&2; exit 1; }

echo "== version"
run --version

echo "== validate example decks"
run validate $decks/mono-red-burn.txt $decks/mono-green-stompy.txt $decks/mono-white-humans.txt
run validate -f commander $decks/commander/krenko-goblins.txt $decks/commander/ezuri-elves.txt

echo "== validate rejects an unknown card (expects exit code 2)"
printf '4 Notacard McFakerson\n56 Mountain\n' > "$data/bad.txt"
set +e; run validate /data/bad.txt; code=$?; set -e
[ "$code" -eq 2 ] || fail "validate of a bad deck exited $code, expected 2"

echo "== constructed gauntlet: 10 games"
run run -T $decks/mono-red-burn.txt -O $decks/mono-green-stompy.txt -O $decks/mono-white-humans.txt \
    -n 5 --seed 1 --deterministic -o /data/constructed
echo "== commander gauntlet: 4 games"
run run -f commander -T $decks/commander/krenko-goblins.txt -O $decks/commander/ezuri-elves.txt \
    -n 4 --seed 1 --deterministic -o /data/commander

python3 - "$data" <<'PY'
import glob, json, sys
data = sys.argv[1]
for name, expected in (("constructed", 10), ("commander", 4)):
    run_dir = glob.glob(f"{data}/{name}/*")[0]
    games = [json.loads(l) for l in open(f"{run_dir}/games.jsonl")]
    summary = json.load(open(f"{run_dir}/summary.json"))
    assert len(games) == expected, f"{name}: {len(games)} games, expected {expected}"
    assert sorted(g["gameId"] for g in games) == list(range(expected)), f"{name}: game ids"
    bad = [g for g in games if g["outcome"] in ("error", "timeout")]
    assert not bad, f"{name}: {len(bad)} games failed or timed out: {bad[0]}"
    assert summary["gamesPlayed"] == expected and summary["score"] is not None, f"{name}: summary"
    if name == "commander":
        assert all(p["commanderCasts"] is not None for g in games for p in g["players"]), "commander stats"
    print(f"{name}: {len(games)} games OK, score {summary['score']:.1f}")
PY

echo "== replay reproduces a recorded game"
run_dir=$(cd "$data/constructed" && ls -d */ | head -1)
out=$(run replay "/data/constructed/$run_dir" --game 3 --quiet)
echo "$out" | tail -1
echo "$out" | grep -q "Same result as recorded" || fail "replay did not reproduce game 3"

echo "SMOKE TEST PASSED"
