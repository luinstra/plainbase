<picture>
  <source media="(prefers-color-scheme: dark)" srcset="assets/brand/plainbase-logo-dark.svg" />
  <img src="assets/brand/plainbase-logo.svg" alt="Plainbase" width="360" />
</picture>

_Internal docs humans enjoy and agents can actually work with._

Filesystem-native, agent-native: the source of truth is a plain tree of Markdown
files, Git is an optional layer, and every index is derived, rebuildable state.
See the [design summary](docs/DESIGN_SUMMARY.md).

## Quickstart (Docker Compose) — 2 commands

```sh
git clone https://github.com/luinstra/plainbase && cd plainbase
docker compose up --build
```

Open http://localhost:8080 — the SPA shell is served against the bundled
demo docs (`fixtures/demo-docs`). Point the bind mount in
`docker-compose.yml` at your own Markdown tree to adopt it.

The compose file also starts **Meilisearch, the OPTIONAL search tier**.
Plainbase's default search is embedded SQLite FTS5 and needs no containers at
all; you run the compose tier when you want a deliberately better engine
(typo tolerance, superior relevance, CJK tokenization).

## Quickstart (single binary — the default tier)

```sh
./plainbase serve   # CONTENT_DIR=./content DATA_DIR=./data by default
```

Native binaries (no JRE required) are produced by the CI native gate for
linux-x64; the platform matrix grows by Phase 6. The universal JAR
(`server/build/distributions/`) is the release floor and runs anywhere with
Java 21+.

## Configuration

| Env var | Default | Meaning |
|---|---|---|
| `CONTENT_DIR` | `./content` | Canonical, user-owned Markdown tree. Plainbase only writes here on explicit save/approve. |
| `DATA_DIR` | `./data` | App-owned state: SQLite DB, config, caches, search index. |
| `PLAINBASE_HOST` | `0.0.0.0` | Bind address. |
| `PLAINBASE_PORT` | `8080` | HTTP port. |
| `PLAINBASE_LOG_LEVEL` | `INFO` | Root log level (`ERROR`/`WARN`/`INFO`/`DEBUG`). |

## Development

```sh
./gradlew build                      # backend + frontend + tests (universal JAR floor)
./gradlew :server:run --args=serve   # run the server on the JVM
./gradlew :server:run --args=spike   # full-stack native dependency spike (JVM)
./gradlew :server:nativeCompile      # native binary (requires GraalVM 25+ on JAVA_HOME/GRAALVM_HOME)
```

Requirements: JDK 21+ (the build auto-provisions the 21 toolchain for
bytecode). Node is downloaded by the Gradle build — no local install needed.

For native builds, the repo pins GraalVM via [asdf](https://asdf-vm.com/) —
`.tool-versions` selects `graalvm-community-25.0.2`, so inside the repo
`java` and `native-image` resolve to the same GraalVM the CI native gate
uses:

```sh
asdf install        # one-time: installs the pinned GraalVM
```

### The native dependency spike

`plainbase spike` exercises every load-bearing dependency with real
assertions — Ktor CIO round-trip, Koin DSL wiring, SQLDelight query, FTS5
MATCH, JGit init/commit/log/diff, flexmark render, argon2 hash/verify, and an
MCP SDK stub handshake. It prints PASS/FAIL per check and exits non-zero on
failure. CI runs it on the JVM **and** against the native binary (the native
gate). If a dependency ever fails irreparably under native-image, the
documented escape hatch is: v0.1 ships JVM-only and native moves to v0.2 —
the JAR is always the release floor.

**Status (Phase 0):** all 8 checks pass on the JVM and inside the native
binary (verified locally on macos-arm64 with GraalVM CE 25, Kotlin bytecode
targeting 21; CI covers linux-x64). The reachability metadata that made
flexmark (BitFieldSet enum universes), JGit (config enums), the MCP SDK
(polymorphic JSONRPC serializers), and kotlinx DTO lookups work under
native-image lives in `server/src/main/resources/META-INF/native-image/`.
Native startup: ~3ms to first request handler.

### Architecture

Hexagonal, two top-level packages under `com.plainbase` (see the plan, §5.8):

- `domain/` — models, ports (`XxxProvider`, `ContentStore`), services. Depends on nothing.
- `frameworks/` — adapters grouped by technology (`ktor/`, `sqldelight/`, `git/`,
  `markdown/`, `koin/`, `config/`, `security/`, `spike/`).

Native-image constraints are load-bearing stack choices, not preferences:
Ktor **CIO** (never Netty), **kotlinx.serialization** only (no Jackson/Gson),
**SQLDelight** (not Exposed), Koin **constructor DSL** only.

## State separation (hard rule)

- `CONTENT_DIR` — canonical, portable, user-owned. Reinstall Plainbase
  anywhere against the same tree and nothing is lost.
- `DATA_DIR` — app-owned workflow/security state. Never canonical content.
- Search indexes — fully derived; delete them any time and rebuild.
