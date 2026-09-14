# ---- Build stage: Gradle builds backend + frontend (node is downloaded by the build) ----
# Base pinned by digest (C5 item 8) for a reproducible release image; re-resolve with
# `docker buildx imagetools inspect eclipse-temurin:25-jdk` when bumping the JDK line.
FROM eclipse-temurin:25-jdk@sha256:dcf835e52330939b6c9f90ecab8aafcbcaa8fbf48423db44de884cf978c10144 AS build
WORKDIR /src

# Build-only libatomic1 for Node 26 linux-arm64; use the same distro package-update policy as runtime curl/git.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libatomic1 \
    && rm -rf /var/lib/apt/lists/*

# C5: the release workflow passes the tag-derived version through so the image's binary
# self-reports it too (root build.gradle.kts `-PreleaseVersion`, item 8); empty = dev SNAPSHOT.
ARG RELEASE_VERSION=""

# Warm the dependency cache first for better layer reuse.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
RUN ./gradlew --version --no-daemon

COPY server/ server/
COPY frontend/ frontend/
RUN ./gradlew :server:installDist --no-daemon ${RELEASE_VERSION:+-PreleaseVersion=$RELEASE_VERSION}

# ---- Runtime stage: JRE + the universal distribution (the release floor) ----
# Native-image variants are produced by CI per platform; the compose tier ships the JAR.
# Base pinned by digest (C5 item 8); re-resolve with
# `docker buildx imagetools inspect eclipse-temurin:25-jre` when bumping the JRE line.
FROM eclipse-temurin:25-jre@sha256:15090d159279e5c158473eccb48cd87f57b3e3a47511a797eb5a7a7ea6f86b0f

# OCI labels GHCR reads for the package page: `source` connects the package to this repo (so the
# page shows the repo README + inherits its visibility), `description` is the one line of
# image-specific text shown below the package name (<= 512 chars, plain text), `licenses` is the
# SPDX id. Static values — no re-resolution needed.
LABEL org.opencontainers.image.source="https://github.com/luinstra/plainbase" \
      org.opencontainers.image.description="Plainbase: a filesystem-native, agent-native docs server - your content stays plain Markdown on disk. This is the JVM container tier (a JRE image built via installDist); for the fast-start path prefer a native binary from the GitHub Releases. Runs auth-off over plaintext by default, so front it with an authenticating reverse proxy for any multi-user or public deployment." \
      org.opencontainers.image.licenses="Apache-2.0"

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl git \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /src/server/build/install/plainbase /opt/plainbase

ENV CONTENT_DIR=/content \
    DATA_DIR=/data \
    PLAINBASE_PORT=8080 \
    PLAINBASE_OPTS="-Dlogback.configurationFile=logback-container.xml -Dplainbase.commandEvents=json"
VOLUME ["/content", "/data"]
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=15s --retries=5 \
    CMD curl -fsS http://127.0.0.1:8080/healthz || exit 1

ENTRYPOINT ["/opt/plainbase/bin/plainbase"]
CMD ["serve"]
