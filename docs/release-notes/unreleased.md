# Unreleased

## Changed

- Docker images package the complete Gradle-built JVM distribution. CI reuses its tested distribution;
  release images reuse the same-run version-stamped JVM artifact instead of compiling source again.

## Before upgrading

- Local source-context Docker and Compose builds now require a successful Java 25
  `./gradlew :server:installDist --console=plain` first. Repeat it after source changes before rebuilding
  with BuildKit/Compose v2; Docker no longer compiles source. Published images are unaffected by this
  local build prerequisite.
