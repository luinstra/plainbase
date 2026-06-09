# ---- Build stage: Gradle builds backend + frontend (node is downloaded by the build) ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Warm the dependency cache first for better layer reuse.
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
RUN ./gradlew --version --no-daemon

COPY server/ server/
COPY frontend/ frontend/
RUN ./gradlew :server:installDist --no-daemon

# ---- Runtime stage: JRE + the universal distribution (the release floor) ----
# Native-image variants are produced by CI per platform; the compose tier ships the JAR.
FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /src/server/build/install/plainbase /opt/plainbase

ENV CONTENT_DIR=/content \
    DATA_DIR=/data \
    PLAINBASE_PORT=8080
VOLUME ["/content", "/data"]
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=15s --retries=5 \
    CMD curl -fsS http://localhost:8080/healthz || exit 1

ENTRYPOINT ["/opt/plainbase/bin/plainbase"]
CMD ["serve"]
