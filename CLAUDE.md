# Plainbase — Project Rules

Filesystem-native, agent-native internal docs product. Master plan:
`.crew/plans/draft-implementation-plans-to-get-plainbase-design.md` (architecture §4-5, package conventions §5.8, theming §5.9). Phase plans live alongside it.

## Dependency discipline (load-bearing for the native-image bet)

- **Server dependencies default to NO.** Every addition must be justified against the
  GraalVM native gate (reflection-free or metadata-provided), then recorded:
  `./gradlew :server:writeDependencyAllowlist` and commit `server/dependency-allowlist.txt`
  with the catalog change. CI fails on unrecorded runtime-classpath drift.
- **Banned outright** (fails resolution, even transitively): Netty, Jackson, Gson, Exposed.
- Ktor server engine is **CIO only**. Serialization is **kotlinx.serialization only**.
  The app DB is **SQLDelight only**; `DATA_DIR/search.db` is **raw JDBC over the same
  xerial driver** per ADR-0004 (derived state, deletable, no migrations — never SQLDelight,
  never a second driver).
- Compute-hungry features (search scoring, future embeddings/OCR) belong in external
  processes (the Meilisearch pattern), never in this binary.

## Code conventions

- Hexagonal, memoria-style: `domain/` (no framework imports) + `frameworks/` (adapters),
  ports named `XxxProvider`/natural nouns, impls `<Tech><Port>`. See master plan §5.8.
- Formatting: Spotless + ktlint; author's layout wins (six rules disabled — see
  `.editorconfig` and the `ktlintDisabledRules` map in `server/build.gradle.kts`; keep in sync).
  140-col limit.
- Logging: kotlin-logging facade, lazy lambdas. Companion-object logger for classes,
  top-level when file composition suits. `println` only for CLI output contracts
  (spike, dry-run output) — never diagnostics. Level knob: `PLAINBASE_LOG_LEVEL`.
- Shell habits for agents: don't chain commands with `&&`/`;` — separate invocations.

## Verification

- `./gradlew build` = JAR floor: compile, tests, spotlessCheck, dependency allowlist.
- `./gradlew :server:nativeCompile` then `server/build/native/nativeCompile/plainbase spike`
  = the native gate (8/8 required). GraalVM comes from asdf (`.tool-versions`).
- CI mirrors both; the universal JAR is the release floor — native failures block the
  native artifact only.

### Frontend tests — always go through Gradle, never raw `vitest`/`playwright`

The Gradle node plugin owns the hermetic Node/npm toolchain; a raw `./node_modules/.bin/vitest`
(or `cd frontend && …`) runs against the wrong toolchain and is an unwhitelistable, prompt-spamming
command string. Use these stable `./gradlew :*` invocations instead:

- `./gradlew :frontend:vitestFile -PtestFile=src/__tests__/<file>` — one file, fast iteration
  (omit `-PtestFile` to run all). No token-discipline gate.
- `./gradlew :frontend:npmTest` — full vitest suite + the §5.9 token-discipline gate. Always runs
  (not up-to-date-cached).
- `./gradlew :frontend:build` — adds `tsc --noEmit` + `vite build`; run this to catch type errors
  `npmTest` alone misses.
- `./gradlew :frontend:smokeTest` — Playwright against the real server (downloads Chromium first run;
  not part of `build`).
