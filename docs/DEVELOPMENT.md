# Development

Contributor-facing internals: building, the CI gates, the native dependency
spike, and the architecture rules. For contribution mechanics (DCO sign-off,
commit style, dependency policy) see [CONTRIBUTING.md](../CONTRIBUTING.md).

## Building

```sh
./gradlew build                      # backend + frontend + tests (universal JAR floor)
./gradlew :server:run --args=serve   # run the server on the JVM
./gradlew :server:run --args=spike   # full-stack native dependency spike (JVM)
./gradlew :server:nativeCompile      # native binary (requires GraalVM 25+ on JAVA_HOME/GRAALVM_HOME)
```

Requirements: JDK 21+ (the build auto-provisions the 21 toolchain for
bytecode). Node is downloaded by the Gradle build - no local install needed.

For native builds, the repo pins GraalVM via [asdf](https://asdf-vm.com/) -
`.tool-versions` selects `graalvm-community-25.0.2`, so inside the repo
`java` and `native-image` resolve to the same GraalVM the CI native gate
uses:

```sh
asdf install        # one-time: installs the pinned GraalVM
```

## What CI checks

Five jobs gate `main` (`.github/workflows/ci.yml`):

- **`build-test`** - the JVM universal-JAR floor: `./gradlew build` + the full-stack dependency
  spike.
- **`enforced-auth-smoke`** - the builtin auth/CSRF matrix on loopback (anon `401`, bootstrap, CSRF
  present/absent/cross-origin, a PB-WRITE-1 save, an agent-bearer read + REST revoke). Every other
  job here boots `auth.mode=off` by default, so this is the one job that actually exercises
  enforced-mode auth.
- **`docker-image`** - the compose-tier image build plus a non-loopback proxy/transport smoke (a
  `421` transport refusal and the full proxy CSRF path - only reachable from outside loopback).
- **`native-gate` (linux-x64)** - `nativeCompile` → `nativeTest` → the spike (9/9) → the
  enforced-auth smoke again, against the native binary → the native-startup regression tripwire.
- **`frontend-smoke`** - Playwright, booting both an auth-off and an enforced-builtin server;
  carries the CSP zero-violation gate (`csp.spec.ts`) and the enforced-builtin approval flow
  (`review.spec.ts`). Deliberately outside `./gradlew build` - a browser-download flake must never
  paint the JAR floor red.

Release builds (`.github/workflows/release.yml`) produce the universal JAR
plus native binaries for linux-x64, linux-arm64, macos-arm64, and
windows-x64.

## The native dependency spike

`plainbase spike` exercises every load-bearing dependency with real
assertions (9 checks) - Ktor CIO client TLS round-trip (a pinned self-signed
loopback cert; the standing native-HTTPS regression guard), Koin DSL wiring,
SQLDelight query, FTS5 MATCH, flexmark render, argon2 hash/verify, an MCP SDK
stub handshake, the in-binary MCP SSE-on-CIO handshake, and offline SigV4
signing vectors. It prints PASS/FAIL per check and exits non-zero on failure.
CI runs it on the JVM **and** against the native binary (the native gate). All
9 checks pass on the JVM and inside the native binary; CI gates linux-x64 on
every push. If a
dependency ever fails irreparably under native-image, the documented escape
hatch is: ship JVM-only and move native to the next release - the JAR is
always the release floor.

The reachability metadata that makes flexmark (BitFieldSet enum universes),
JGit (config enums), the MCP SDK (polymorphic JSONRPC serializers), and
kotlinx DTO lookups work under native-image lives in
`server/src/main/resources/META-INF/native-image/`.

Native startup (cold exec → first `200 /healthz`, against an empty content
dir): ~467 ms measured local median - see
[Performance & the startup gate](operating-plainbase.md#performance--the-startup-gate)
(the ~3 ms figure sometimes quoted is the Ktor module-init slice, a narrower
window, not cold-start).

## Architecture

Hexagonal, two top-level packages under `com.plainbase` (see the design
summary, §5.8):

- `domain/` - models, ports (`XxxProvider`, `ContentStore`), services. Depends on nothing.
- `frameworks/` - adapters grouped by technology (`ktor/`, `sqldelight/`, `git/`,
  `markdown/`, `koin/`, `config/`, `security/`, `spike/`).

Native-image constraints are load-bearing stack choices, not preferences:
Ktor **CIO** (never Netty), **kotlinx.serialization** only (no Jackson/Gson),
**SQLDelight** (not Exposed), Koin **constructor DSL** only.
