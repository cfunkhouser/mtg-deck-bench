# Build from the repo root:  docker build -f docker/Dockerfile -t mtgbench .

# ---- Stage 1: build patched Forge + harness ------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
# git is used by build-forge.sh to apply patches (works without a repository).
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends git >/dev/null && rm -rf /var/lib/apt/lists/*

# Maven's local repository lives in the image layers, not in a BuildKit cache mount: build-forge.sh
# installs the patched Forge there, and a cache mount's contents aren't exported with the layer cache
# (e.g. type=gha in CI). A build-forge layer restored from cache would then come back without Forge.
ENV MAVEN_ARGS="-Dmaven.repo.local=/src/.m2"

COPY forge/ forge/
COPY patches/ patches/
COPY scripts/build-forge.sh scripts/trim-res.sh scripts/
RUN scripts/build-forge.sh
# Card data without game modes, UI assets and other languages (~170 MB instead of ~470 MB).
RUN scripts/trim-res.sh forge/forge-gui/res /out/res

COPY bench/ bench/
RUN cd bench && mvn -B -q package -DskipTests \
    && mvn -B -q dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=/out/lib \
    && cp target/mtgbench-*.jar /out/lib/

# ---- Stage 2: runtime -----------------------------------------------------------------------
FROM eclipse-temurin:17-jre
LABEL org.opencontainers.image.title="mtgbench" \
      org.opencontainers.image.licenses="GPL-3.0-only" \
      org.opencontainers.image.source="https://github.com/cfunkhouser/mtg-deck-bench"
RUN useradd --create-home --uid 10001 bench \
    && mkdir -p /data && chown bench /data

# Forge's card data (read-only). ForgeBootstrap builds Forge's writable home under $FORGE_HOME.
COPY --from=build /out/res /opt/forge/res
COPY --from=build /out/lib /opt/mtgbench/lib
COPY examples/decks /opt/mtgbench/examples/decks

ENV FORGE_RES_DIR=/opt/forge/res \
    FORGE_HOME=/data/forge-home \
    JAVA_TOOL_OPTIONS="-Djava.awt.headless=true -XX:MaxRAMPercentage=75 -XX:+DisableExplicitGC -XX:+UnlockExperimentalVMOptions -XX:hashCode=2"

USER bench
WORKDIR /data
VOLUME ["/data"]
# Results go to /data/results by default (relative to WORKDIR).
ENTRYPOINT ["java", "-cp", "/opt/mtgbench/lib/*", "mtgbench.cli.BenchCli"]
CMD ["--help"]
