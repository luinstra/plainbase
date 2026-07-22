# Detekt cleanup plan

Detekt is configured but temporarily disabled while the current linting PR is finished. Re-enable it on a dedicated
cleanup branch after this PR lands; do not mix the backlog cleanup into unrelated feature work.

## Baseline

The baseline below was generated with Detekt `2.0.0-alpha.5` after the initial hygiene fixes, using
`config/detekt/detekt.yml` with `buildUponDefaultConfig = true`:

| Rule | Severity | Findings |
| --- | --- | ---: |
| `TooGenericExceptionCaught` | error | 56 |
| `CyclomaticComplexMethod` | error | 29 |
| `LoopWithTooManyJumpStatements` | error | 12 |
| `ThrowsCount` | error | 8 |
| `SwallowedException` | error | 4 |
| `ComplexCondition` | error | 3 |
| `NestedBlockDepth` | error | 3 |
| `ReturnCount` | warning | 93 |
| `MagicNumber` | warning | 70 |
| `TooManyFunctions` | warning | 31 |
| `LongMethod` | warning | 21 |
| `LargeClass` | warning | 1 |

Total: **115 errors and 216 warnings**. Of the 331 findings, 328 are in production sources and 3 are in tests.

`MaxLineLength` remains disabled in Detekt. Kotlinter/ktlint owns the project's 140-column limit so the tools do not
duplicate or contradict each other.

## Cleanup order

### 1. Exception correctness

Address `TooGenericExceptionCaught` and `SwallowedException` first. Audit each catch boundary instead of mechanically
replacing `Exception`: preserve deliberate process, storage, transaction, and CLI boundaries; restore interruption
where applicable; and retain useful failure context in logs or domain errors. Add focused regression tests whenever a
catch is narrowed or an ignored exception becomes observable.

### 2. Risky control flow

Work through `CyclomaticComplexMethod`, `LoopWithTooManyJumpStatements`, `ThrowsCount`, `NestedBlockDepth`, and
`ComplexCondition` in behavior-sized batches. Prioritize the indexing/write pipeline, object storage, CLI entry points,
and request routing. Extract named decisions or operations only where that makes invariants clearer; do not split code
solely to satisfy a numeric threshold. Run the relevant focused tests before and after each refactor.

### 3. Stylistic and size debt

Review `ReturnCount`, `MagicNumber`, `TooManyFunctions`, `LongMethod`, and `LargeClass` rather than auto-fixing them.
Keep guard clauses and protocol constants when they improve clarity. Refactor genuinely mixed-responsibility classes
and methods, replace unexplained literals with domain-named constants, and leave justified findings configured as
warnings or narrowly suppressed. This pass is intentionally retained: the warnings expose real pockets of sloppy
structure even though they should not block the build yet.

### 4. Tighten policy and enable the gate

After each category reaches an acceptable baseline, update `config/detekt/detekt.yml` with explicit project policy.
Promote high-signal rules to errors, document any narrow exclusions, uncomment Detekt in `server/build.gradle.kts`,
and run the full JVM floor. Keep line-length enforcement exclusively in Kotlinter.

## Suggested batches

1. Application and CLI exception boundaries.
2. Object-store and S3 error handling/control flow.
3. Indexing, proposal, and write-pipeline complexity.
4. Ktor routes, authentication, and MCP boundaries.
5. Persistence/search adapters and remaining domain services.
6. Stylistic/size review, final policy decisions, and Detekt gate activation.

For every batch, run focused tests during iteration and finish with `./gradlew build`. Server behavior changes also need
the native gate required by `AGENTS.md` before the cleanup is merged.
