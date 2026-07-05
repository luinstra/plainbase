# ---- Build stage: Gradle builds backend + frontend (node is downloaded by the build) ----
# Base pinned by digest (C5 item 8) for a reproducible release image; re-resolve with
# `docker buildx imagetools inspect eclipse-temurin:21-jdk` when bumping the JDK line.
FROM eclipse-temurin:21-jdk@sha256:1eeacc8c295ed4805f6ffead2417b1936aad296b02ea9e56b457230befc9e98d AS build
WORKDIR /src

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
# `docker buildx imagetools inspect eclipse-temurin:21-jre` when bumping the JRE line.
FROM eclipse-temurin:21-jre@sha256:d2b9f8f12212cadcfdf889461531784e8fd097feade954d65b31ee7a71c473ec
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl git \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /src/server/build/install/plainbase /opt/plainbase

ENV CONTENT_DIR=/content \
    DATA_DIR=/data \
    PLAINBASE_PORT=8080
VOLUME ["/content", "/data"]
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=15s --retries=5 \
    CMD curl -fsS http://127.0.0.1:8080/healthz || exit 1

ENTRYPOINT ["/opt/plainbase/bin/plainbase"]
CMD ["serve"]
