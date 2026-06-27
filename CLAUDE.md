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

## Build workflow (how chunks get built)

**Default per-chunk loop — run this without being asked:**

1. **Tighten the plan** — `/crew:measure-twice` on the chunk: an advisor drafts an executor-ready
   addendum bound to the *built* reality (cite real files/lines, not the master plan's prose), a
   second advisor reviews until APPROVED (fix BLOCKING, accept MINOR). Resolve genuine owner-forks
   with ONE `AskUserQuestion` before drafting; never relitigate a settled decision. Phase plans get
   measure-twice before their chunk loops begin.
2. **Build** — `/crew:build`: an executor implements (read the addendum + cited code, match the
   cleanest surrounding idiom). Verify via an advisor; **fix MINOR findings by default**, re-delegate
   BLOCKING.
3. **Verify** — always through Gradle (§Verification): JVM floor `./gradlew build`; **server changes
   also run the native gate** (`:server:nativeTest` → `:server:nativeCompile` → `plainbase spike`,
   8/8). Run the full floor, not just the native gate — a cross-cutting refactor can break a
   JVM-only test the native subset never compiles.
4. **Commit on the owner's explicit word only** — logical commits (one concern each: decision /
   feature+its tests / tooling), conventional style, on `main`. Gitignored `.crew/` addenda stay
   local. Push when asked.

**Escalations — proactively RECOMMEND, run on the owner's go-ahead** (they're token-heavy; flag the
moment + the reason, don't auto-run). Trigger for **frozen-contract, concurrency-heavy, or
phase-closing** chunks:

- **Before building:** a four-model `/octo:debate` on design soundness. (S7/S8: caught a silent
  data-correctness bug that measure-twice had approved.)
- **After building:** **review-until-clean**, two complementary fan-outs:
  - **First pass — diverse fan-out, run in parallel to drain the latent-bug backlog in fewer rounds.**
    Across *models* (cross-model diversity is the highest-yield: on W5 Codex caught a `git diff` RCE via
    repo-local `diff.external` while Gemini caught an empty-message log-parser field-shift — neither caught
    the other's, and the advisor verify caught neither) AND across *lenses* (≥3 reviewers with distinct
    mandates — e.g. security/RCE · correctness+frozen-contract+concurrency · deployment/ops/scale —
    since same-model instances share blind spots, the lens diversity buys back the correlation). Models:
    `/codex:review` (or focused `codex exec -s read-only` per lens) + a Gemini-3.1-Pro pass via `agy`
    (the `gemini` CLI is retired; run `agy` from the repo cwd with `--dangerously-skip-permissions` +
    review instructions — it runs `git diff` itself; do NOT inline the diff).
  - **Confirm rounds — fix → re-review until a round returns empty/cosmetic.** Some findings are
    causally sequential (a fix exposes/creates the next — W5: throw-on-failure → diff-route taxonomy →
    git-version gate), so a few rounds are inherent; parallelism can't collapse that tail. Drop a model
    once it plateaus clean (W5: Gemini went clean by R2; Codex kept finding real items to R7). A final
    parallel diverse-lens burst all-clean is strong evidence of earned-clean. Validate every finding
    against real code before fixing; fix the CLASS not the instance.
  (S8: `/codex:review` caught a misleading operator doc + a CLI exit-code bug.)

The owner can say "always debate"/"always review" to make either automatic. **When a debate or
review finds a hole the spec missed, widen the spec AND its tests — not just the code.**
