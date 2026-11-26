#!/usr/bin/env bash
# Copy Forge's res/ tree without the parts a headless simulation never reads
# (game modes, UI assets, other languages). Usage: trim-res.sh SRC_RES DEST_RES
set -euo pipefail
src=$1
dest=$2
rm -rf "$dest"
mkdir -p "$dest"
cp -R "$src"/. "$dest"/
cd "$dest"
rm -rf adventure quest conquest puzzle tutorial \
       music sound skins fonts \
       cube draft sealed geneticaidecks
# deckgendecks stays: Forge reads it at startup and logs errors (with an NPE) when it is missing.
# Keep English UI strings only; card-name translations are UI-only too.
find languages -type f ! -name 'en-US.properties' -delete
