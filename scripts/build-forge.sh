#!/usr/bin/env bash
# Apply our patches to the pinned Forge submodule (idempotent) and install the Forge modules the
# harness needs into the local Maven repository.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

# Fetch the submodule only if it isn't there yet. In a Docker build (or CI after checkout with
# submodules) forge/ is already present, and there may be no usable .git.
if [ ! -f forge/pom.xml ]; then
    git submodule update --init --depth 1 forge
fi

# Patches are applied from the repo root with --directory, which also works without a repository.
for p in patches/forge/*.patch; do
    if git apply --directory=forge --reverse --check "$p" 2>/dev/null; then
        echo "already applied: $p"
    else
        git apply --directory=forge "$p"
        echo "applied: $p"
    fi
done

# Run from forge/: its .mvn/maven.config points at settings by a relative path.
(cd forge && mvn -B -q -pl forge-gui-desktop -am install -DskipTests -Dcheckstyle.skip)
