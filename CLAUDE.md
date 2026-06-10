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
  Persistence is **SQLDelight only**.
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
