# Package the complete JVM distribution; Java 25 and frontend compilation run through Gradle first.
# Prepare successfully before building the image: ./gradlew :server:installDist --console=plain
# Rebuild that distribution after source changes. Missing output fails at COPY.
# Base pinned by digest (C5 item 8); re-resolve with
# `docker buildx imagetools inspect eclipse-temurin:25-jre` when bumping the JRE line.
FROM eclipse-temurin:25-jre@sha256:15090d159279e5c158473eccb48cd87f57b3e3a47511a797eb5a7a7ea6f86b0f

# OCI labels GHCR reads for the package page: `source` connects the package to this repo (so the
# page shows the repo README + inherits its visibility), `description` is the one line of
# image-specific text shown below the package name (<= 512 chars, plain text), `licenses` is the
# SPDX id. Static values — no re-resolution needed.
LABEL org.opencontainers.image.source="https://github.com/luinstra/plainbase" \
      org.opencontainers.image.description="Plainbase: a filesystem-native, agent-native docs server - your content stays plain Markdown on disk. This is the JVM container tier (a JRE image packaging the Gradle-built JVM distribution); for the fast-start path prefer a native binary from the GitHub Releases. Runs auth-off over plaintext by default, so front it with an authenticating reverse proxy for any multi-user or public deployment." \
      org.opencontainers.image.licenses="Apache-2.0"

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl git \
    && rm -rf /var/lib/apt/lists/*

COPY server/build/install/plainbase/ /opt/plainbase/

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
