# Plainbase project guidance

Plainbase is filesystem-native, agent-native documentation software. User-owned content files are
authoritative; the application must not become a second authority for their content.

This is the canonical project instruction file for every coding harness. `CLAUDE.md` only imports it.
The linked documents are instructions, not automatically expanded imports: **open the applicable
documents before doing the work**.

| When | Read |
| --- | --- |
| Every task | [Workflow and evidence](docs/agents/workflow.md) |
| Code, architecture, dependencies, storage or configuration | [Engineering rules](docs/agents/engineering.md) |
| Code changes, tests, builds or verification claims | [Verification](docs/agents/verification.md) |
| UI and reader navigation | [UI handoff and owner decisions](docs/design/handoff-v2/README.md), [engineering rules](docs/agents/engineering.md) |
| Release preparation or tagging | [Release preparation](docs/DEVELOPMENT.md#release-preparation) |
| Harness setup or missing instructions | [Harness compatibility](docs/agents/harnesses.md) |

## Rules to keep in view

- Preserve unrelated changes and other sessions' work. Use the branch/worktree agreed with the owner;
  isolate concurrent work. Do not reset, stash or clean changes you do not own.
- Commit and push only when authorized. Every commit needs DCO sign-off (`git commit -s`), including
  delegated commits. Use conventional messages and one logical concern per commit.
- Server dependencies default to no. Netty, Jackson, Gson and Exposed are banned. Ktor CIO,
  kotlinx.serialization and the SQLDelight/raw-JDBC split are deliberate native-image constraints.
- Run frontend tests through Gradle's managed Node toolchain, never raw Vitest/Playwright/npm commands.
- Server and offline commands default to `$HOME/.plainbase`, independently of the desktop app.
  Use explicit disposable state/content for development launches; do not disturb a live instance.
- Trace a test's real input and assertion, or run it, before claiming it proves a behavior. An approved
  plan, successful compilation, skipped task or historical CI run is not execution evidence.
- Re-pinning a deliberately flipped test is RED-first, one rule at a time, especially when it records
  a past crash. Observe the intended failure before changing its expected result.
- Run shell commands separately; do not chain them with `&&` or `;`. Prefer `rg` for searches.

## Required workflow and verification

This summary intentionally repeats the critical rules from the workflow and verification guides so
they are present in automatically loaded context. Keep both copies synchronized when policy changes;
the summary does not relax the detailed requirements.

Run the default implementation chunk loop **without being asked**: Crew measure-twice (plan/advisor
review) -> Crew build (executor/advisor verification) -> verification gates -> commit only when
authorized. Fix validated minor build findings by default; resolve blocking findings before proceeding.
Read [workflow details](docs/agents/workflow.md) for scope, host capabilities and review escalations.

- The code-change floor is `./gradlew build` (JVM/JAR compilation, tests, lint and dependency allowlist).
- Server changes also require `./gradlew :server:nativeTest`, then `./gradlew :server:nativeCompile`,
  then `server/build/native/nativeCompile/plainbase spike` (9/9 required). Run separate shell commands.
- Verify the **in-image** native JUnit summary: nonzero tests found and successful, zero failures.
  A discovery/listing report, skipped task or `BUILD SUCCESSFUL` alone is not native execution evidence.
- Native tests use kotlin.test/JUnit with `@Tag("native")`. Native-tag divergence surfaces by default:
  NIO edge behavior, process execution, xerial JDBC/JNI, charset/Unicode decoding and SecureRandom/crypto.
  Pure domain logic stays on the JVM; keep the native suite lean. Do not put `#` in backtick test names
  (JUnit 6 native ID parsing). See [verification](docs/agents/verification.md) for toolchain and commands.

## Project map

- [Project language and glossary](CONTEXT.md)
- [Development](docs/DEVELOPMENT.md), [contributing and DCO](CONTRIBUTING.md)
- [Backend architecture](docs/backend-architecture.md), [architecture decisions](docs/decisions/README.md)
- [Configuration](docs/configuration.md), [operating Plainbase](docs/operating-plainbase.md)
- [HTTP API](docs/http-api.md), [agent workflow](docs/http-agent-workflow.md), [theming](docs/theming.md)

Local Crew plans, when present, live under `.crew/plans/`. The original master plan is
`.crew/plans/draft-implementation-plans-to-get-plainbase-design.md` (architecture sections 4-5,
packages 5.8, theming 5.9). These gitignored files record rationale and task agreements; reconcile
them with current source and committed docs. Their absence in a fresh clone is expected.
