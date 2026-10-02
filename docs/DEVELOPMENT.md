# Development

Contributor-facing internals: building, the CI gates, the native dependency
spike, and the architecture rules. For contribution mechanics (DCO sign-off,
commit style, dependency policy) see [CONTRIBUTING.md](../CONTRIBUTING.md). The current-state backend map is
[here](backend-architecture.md).

## Building

```sh
./gradlew build                          # backend + frontend + tests (universal JAR floor)
./gradlew -p buildSrc test              # shared-build contract and isolated Gradle wiring tests
./gradlew :server:run --args=serve       # run the server on the JVM
./gradlew :server:run --args=spike       # full-stack native dependency spike (JVM)
./gradlew :server:run --args="root list" # the topology CLI: root add|remove|list (docs/configuration.md)
./gradlew :server:nativeCompile          # native binary (requires CE 25.3.4.1 / JDK 25.0.4.1 on JAVA_HOME/GRAALVM_HOME)
```

Ordinary source builds require JDK 25+ (the build auto-provisions the 25 toolchain for bytecode).
Shared preparation/consumption and `./gradlew -p buildSrc test` require the Gradle daemon to run on Java 25 exactly;
use `JAVA_HOME` to select it. Node is downloaded by the Gradle build - no local install needed.

The root `build` does not run `buildSrc` tests. Run the explicit command above when changing build logic, and run
`python3 -B -m unittest discover -s scripts/ci -p 'test_prepare_runtime_context.py'` for the runtime transport helper.

### Shared CI compilation

CI and release prepare application, frontend, JVM-test and native-test outputs once in the read-only `compile` job.
`prepareSharedRuntime` runs `prepareSharedBuild`, boots the installed launcher to check `/healthz` against the resolved
version, verifies the runtime tar has exactly the installed distribution's file bytes and executable flags, and exports
both payloads. The bundle includes the JAR, both distribution archives, generated sources and processed resources;
it excludes tool caches, test results, native images and native UID lists.

Gradle consumers receive `ORG_GRADLE_PROJECT_ciSharedBuild` (an absolute ZIP path),
`ORG_GRADLE_PROJECT_ciSharedBuildSha256` and `CI_SHARED_BUILD_REQUIRED=true`. Every invocation validates the producer hash,
commit, source fingerprint, workflow run, resolved version, Java 25/Gradle 9.7.1 compatibility and exact external dependency
inventories for main, JVM-test and native-test runtimes before any reader runs. Compiler and frontend build actions are
disabled only in this explicit mode. Tests, coverage, migration verification, lint, dependency allowlist, native builds and
process gates retain their normal checks. Native UID discovery runs freshly on each consumer platform.
`ciSharedBuildProfile` is obsolete and rejected: all three dependency inventories are always validated.
Shared tasks use cross-project resolution and task-graph callbacks; configuration cache and isolated projects are unsupported.
Keep Gradle parallel project execution disabled for this workflow.

Artifact downloads select IDs exported by `compile`, with one fixed file per payload. Consumer-only retries reuse those
successful producer outputs; rerunning the producer creates new IDs for that attempt. An absent/expired artifact, input
mutation, changed dependency or incorrect version fails without source fallback. Internal artifacts use `ci-build-bundle-`
and `ci-runtime-dist-` names so release assembly's `plainbase-*` selection cannot ship them.

For an explicit local round trip, use a unique `-PciSharedBuildRunId=local-<id>` on both preparation and consumption,
and pass `-PciSharedBuild=<absolute-zip>` and `-PciSharedBuildSha256=<producer-sha256>` to the consumer.
Use physical absolute checkout, archive, installed-distribution and output paths for the runtime helper; it rejects
symlink ancestors, including macOS `/tmp` and `/var` aliases. Resolve local working paths with `pwd -P` and use
`/private/tmp` for temporary runtime contexts.
These local run overrides are forbidden inside Actions. Preparation refuses frontend `.env*` files and `VITE_*`
environment overrides, uses production `NODE_ENV`, and fails if a generator changes an input, including the lockfile.
Gradle's hermetic `npmInstall` uses `npm ci` to leave the tracked lockfile stable.
To regenerate the lockfile after editing `frontend/package.json`, run `./gradlew :frontend:npm_install` through Gradle's
managed Node/npm toolchain, then review `frontend/package-lock.json` before preparing a shared bundle.
The fingerprint reads every file under explicit source roots even if ignored, including `frontend/public/dist` and
nested source packages named `build`; only known generated/tool roots are excluded. Root and frontend `.npmrc` files
are included even when untracked. Unrelated untracked files are left unread.

Ordinary local Gradle commands retain source compilation and need neither Git nor CI identity/Python transport helpers.
No marker persists after consumption. The default Dockerfile and `docker compose up --build` also retain their source build.
The shared image jobs override the Dockerfile's `build` stage with the validated named runtime context, using the same
pinned runtime base. Application compilation for shared images originates on the producer's Java 25 runner rather than
the Docker build JDK; the image job still attests same-run packaging. Validation logs report cost per Gradle invocation;
compile, artifact transfer, job overlap and the separate source Docker build must be measured independently.

The platform-neutral runner self-test and Linux-only PID1 regression gates are separate from ordinary test discovery:

```sh
./gradlew :server:gitChildProcessCleanupRunnerTest  # platform-neutral runner validators
./gradlew :server:gitZombieJvmPid1       # Java 25 JVM launcher
./gradlew :server:gitZombieNativePid1    # GraalVM nativeTestCompile executable
./gradlew :server:gitZombieForcedTimeoutPid1
```

The JVM and native tasks use the Java 25 source runner. The forced-timeout task is a separate checkpoint that verifies
TERM observation, timeout exit, and termination of captured process identities; it does not prove that every namespace
process was observed and is not an always-on CI campaign.

The runner self-test is platform-neutral, uses the same validators as the gates, and is wired into the server
`check` task (and therefore `build`).

The Linux PID1 tasks require permissions/capabilities to create the privileged PID, mount, and network namespaces used by
the launcher. Separately, they require noninteractive `sudo -n` and trusted executable `sudo`; `unshare` and
`setpriv` are from util-linux, while `timeout` and `id` are from coreutils, all in `/usr/bin` or `/bin`. No
separate `kill` helper is a prerequisite. The fixtures also require executable `/bin/sh` and `sleep` with
fractional-second support on `PATH`. The JVM gate uses Java 25; the native gate uses the
documented GraalVM toolchain below. CI gives its JVM and native PID1 steps a five-minute ceiling. Fresh run reports are
under `server/build/reports/g3z/jvm/<run-id>/`, `server/build/reports/g3z/native/<run-id>/`, or
`server/build/reports/g3z/forced/<run-id>/`. Successful validation removes runner-owned home/tmp/staging paths;
failed runs retain those paths locally for diagnosis, while CI uploads only command, exit, stdout/stderr, summary,
and native XML diagnostics. On non-Linux hosts, ordinary test discovery may
report a topology-required skip/abort, but that is distinct from the required one-body PID1 successes.

For native builds, use GraalVM CE 25.3.4.1 (JDK 25.0.4.1) from the
[official release archive](https://github.com/graalvm/graalvm-ce-builds/releases/tag/graal-25.3.4.1).
Set both `JAVA_HOME` and `GRAALVM_HOME` to the extracted `Contents/Home` (macOS) or
`bin` parent (Linux) before running the native gate. CI uses
`graalvm/setup-graalvm` with `version: 25.3.4.1` and `java-version: 25`.

`.tool-versions` retains `graalvm-community-25.0.2` only as a JVM-only asdf fallback:
it is explicitly unsupported for the updated native gate.

```sh
export JAVA_HOME=/path/to/graalvm-community-25.3.4.1+1.1/Contents/Home
# Linux: use the extracted archive directory directly, for example:
# export JAVA_HOME=/path/to/graalvm-community-25.3.4.1+1.1
export GRAALVM_HOME="$JAVA_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
native-image --version
```

The macOS release binary targets macOS 14.0 even though its release job runs on `macos-26`.
The workflow checks the emitted Mach-O `minos` header with `otool`; that validates deployment
metadata only and is not evidence that the binary ran on macOS 14.

## What CI checks

The always-run `ci-gate` aggregate in [`.github/workflows/ci.yml`](../.github/workflows/ci.yml) depends on nine jobs.
That source fact does not establish live branch protection:

- **`build-test`** - the JVM universal-JAR floor: `./gradlew build`, the positive `gitZombieJvmPid1`
  control, and the full-stack dependency spike.
- **`compile`** - explicit buildSrc/helper contract tests, shared compilation, executed version proof and validated payload uploads.
- **`consumer-portability`** - read-only validation of the Linux-produced bundle and executed launcher on macOS arm64 and Linux arm64
  with GraalVM CE 25.3.4.1; no additional native images or publication.
- **`enforced-auth-smoke`** - the builtin auth/CSRF matrix on loopback (anon `401`, bootstrap, CSRF
  present/absent/cross-origin, a PB-WRITE-1 save, an agent-bearer read + REST revoke). The builtin lane
  is the focused enforced-mode matrix; Docker, frontend and native lanes also exercise their own
  enforced/proxy paths.
- **`multi-root-smoke`** - the JVM-distribution multi-root topology smoke, separate from the native artifact lane.
- **`docker-image`** - the compose-tier image build plus a non-loopback proxy/transport smoke (a
  `421` transport refusal and the full proxy CSRF path - only reachable from outside loopback).
- **`docker-source-compatibility`** - an independent, parallel source Docker build and health/SPA/structured-log smoke.
  This is the explicit second application compilation that protects the default local Docker path.
- **`native-gate` (linux-x64)** - `nativeCompile` → `nativeTest` → the positive `gitZombieNativePid1`
  control → the spike (9/9) → the enforced-auth smoke again, against the native binary → the native-startup
  regression tripwire.
- **`frontend-smoke`** - Playwright, with a fresh scenario server per test attempt;
  carries the CSP zero-violation gate (`csp.spec.ts`) and the enforced-builtin approval flow
  (`review.spec.ts`). Deliberately outside `./gradlew build` - a browser-download flake must never
  paint the JAR floor red.

Focused smoke runs accept `-PsmokeArgs` as whitespace-separated tokens; arguments containing spaces are not preserved as one token.

Release builds (`.github/workflows/release.yml`) produce the universal JAR
plus three native binaries: linux-x64, linux-arm64, and macos-arm64.
The release's read-only `compile` job prepares the tag-stamped bundle once; the three native jobs and universal-JAR job
consume it directly. The image job validates the runtime tar before registry login and runs no host Gradle or source stage
under write/OIDC privileges. Assembly keeps its required-platform, checksum, attestation and immutable-publication gates.

Native Windows is intentionally deferred until demand justifies the platform-specific filesystem
contract and a green Windows CI lane. Direct Windows JVM operation is not a documented fallback: the
JVM tarball ships no Windows launcher (the Gradle-generated `.bat` is excluded, and the release
workflow asserts the exclusion held).
Windows users should run the Linux binary under WSL2 with its content and data in the distribution's
Linux filesystem. See the
[platform guidance](operating-plainbase.md#platform-note---the-5-second-promise-binds-linux).

## Pre-release checklist

CI covers everything that runs without credentials or a real bucket. The object-storage backend adds a
handful of gates CI structurally CANNOT run - they need real credentials and a real bucket, so they are
owner-run before a release, not part of any automated floor. None of these block a normal PR merge; they
gate a *release* that ships (or touches) the object-storage backend.

1. **Credentialed `plainbase s3-smoke` from the NATIVE binary, per release platform** (R2 primary; S3
   compat when creds exist), certificate validation ON (the command has no insecure flag). Record green
   runs in the deploy guide's [platform table](deploy/object-storage.md#platform-support-honestly).
   Current honest state: macos-arm64 most recently PROVEN 2026-09-19 for v0.3.0
   (see the platform table for earlier runs); linux-x64 credential-free TLS+SigV4
   spike banked in CI; the linux-x64 **real-R2** credentialed smoke is a documented nice-to-have
   (owner-deferred, run when convenient, **NOT a release blocker**); linux-arm64 is docs-only until
   proven.
   - **AWS `%20`-vs-`+` space-encoding is an EXPLICIT gate here** (ADR-0010 SP1, PENDING AWS column).
     `S3WireKey` decodes LIST keys on the R2-proven assumption that `encoding-type=url` emits `%20` for a
     space and never `+`; AWS S3 is unverified and may emit `+`. The smoke's `list-decode-get` probe
     (LIST -> `S3WireKey.decode` -> GET-back) plus `cleanup`'s decode-independent re-LIST emptiness assert
     (delete the decoded keys, then re-LIST the prefix raw and FAIL on any survivor) will FAIL a real-AWS
     run if the decode is wrong. If it does, adjust `S3WireKey` (and its goldens) for the `+`-for-space
     case before marking the AWS column of the SP1 table green.
2. **`scripts/ops/cloud-startup-budget.sh` against a real, seeded ~1k-corpus (prefix-scoped) R2 bucket**
   (warm under 3 s / cold under 10 s, a strict bound; the script's corpus-floor preflight must pass). This is the ONLY check
   on cloud startup budgets anywhere - the CI native-startup tripwire covers the local backend only.
   Seed the corpus per the recipe in the script header (no ~1k fixture is checked in). `PLAINBASE_BUDGET_OBJECT_COUNT`
   is the credential-free escape for the corpus-floor preflight (asserts the seeded count without a bucket round-trip).
   Latest rehearsal: 2026-09-19, macos-arm64 native binary against R2 with 1000 content objects:
   cold median **9.160 s** (3 runs), warm median **1.571 s** (5 runs). Every cold run fetched all
   1000 objects; warm runs fetched none; all runs reported zero unhealed objects.
3. **The two required DR drills**, if the release touched storage / git / DR code paths: content restore
   and bundle-history restore (recipes in
   [operating-plainbase.md](operating-plainbase.md#object-mode-dr-drills-operator-recipes)). Rehearse
   each for real and REFRESH its dated "Rehearsed for real" record in the ops doc (date, provider,
   what was observed) - the record reflects the most recent rehearsal, not a one-time checkbox.
4. **Existing floors** (already CI-automated, listed for completeness): `./gradlew build` and the native
   artifact floor (`nativeCompile` -> `nativeTest` -> spike 9/9). CI additionally runs the two Linux-only
   positive PID1 tasks, `gitZombieJvmPid1` and `gitZombieNativePid1`; `gitZombieForcedTimeoutPid1` remains
   checkpoint-only.

## The native dependency spike

`plainbase spike` exercises every load-bearing dependency with real
assertions (9 checks) - Ktor CIO client TLS round-trip (a pinned self-signed
loopback cert; the standing native-HTTPS regression guard), Koin DSL wiring,
SQLDelight query, FTS5 MATCH, flexmark render, argon2 hash/verify, an MCP SDK
stub handshake, the in-binary MCP SSE-on-CIO handshake, and offline SigV4
signing vectors. It prints PASS/FAIL per check and exits non-zero on failure.
CI runs it on the JVM **and** against the native binary (the native gate). All
9 checks pass on the JVM and inside the native binary; CI gates linux-x64 on
every push. If a dependency ever fails irreparably under native-image, the
documented escape hatch remains: ship JVM-only and move native to the next
release - the JAR is always the release floor. Current release automation does
not support that escape hatch: assembly also requires linux-x64, linux-arm64,
macos-arm64 and the image to succeed. See the [backend map](backend-architecture.md#verification-artifacts-and-deferred-policy).

The reachability metadata that makes flexmark (BitFieldSet enum universes),
the MCP SDK (polymorphic JSONRPC serializers), and kotlinx DTO lookups work
under native-image lives in
`server/src/main/resources/META-INF/native-image/`.
Production Git uses the system `git` binary; JGit is JVM-test-only (see
[ADR-0006](decisions/0006-git-via-system-binary-not-jgit.md)).

Native startup (cold exec → first `200 /healthz`, against an empty content
dir): ~467 ms measured local median - see
[Performance & the startup gate](operating-plainbase.md#performance--the-startup-gate)
(the ~3 ms figure sometimes quoted is the Ktor module-init slice, a narrower
window, not cold-start).

## Architecture

Hexagonal, two top-level packages under `com.plainbase` (see the design
summary, §5.8):

- `domain/` - framework-free models, ports (`XxxProvider`, `ContentStore`), and services.
- `frameworks/` - adapters grouped by technology (`ktor/`, `sqldelight/`, `git/`,
  `markdown/`, `koin/`, `config/`, `security/`, `spike/`).

Build implementation lives in [`buildSrc`](../buildSrc/src/main/kotlin/com/plainbase/buildlogic/);
the server Gradle file retains product and dependency settings.

Native-image constraints are load-bearing stack choices, not preferences:
Ktor **CIO** (never Netty), **kotlinx.serialization** only (no Jackson/Gson),
**SQLDelight** (not Exposed), Koin **constructor DSL** only.

### Indexing and direct snapshot publication

The indexing coordinator performs one serialized pass per rebuild. Its current
sequence is: capture the previous snapshot and checkpoint, capture observation
and binding freshness, read each source eagerly, establish witnesses and
confirmation data, mint proof tokens, apply those proofs, persist and accumulate
filtered scan issues, resolve identity, assemble the immutable `PageIndex`, register move
aliases, atomically store that single snapshot, then apply limbo/diagnostic
effects and invoke publication listeners. A successful empty rebuild is still
a real snapshot; the initial `PageIndex.EMPTY` value is only the pre-build
sentinel.

`IndexSourceReader` owns source materialization and buffers scan issues; the
coordinator records those buffered issues only after every source
materialization returns.
`AbsencePass` owns the fixed freshness capture and proof-source plumbing;
`IndexIdentityAssignments` owns identity resolution and binding; and
`IndexSnapshotAssembler` owns immutable page/index assembly. Alias, checkpoint,
and search collaborators retain their own ports and persistence responsibilities.
All non-null scans, including incomplete scans, contribute their materialized
data and diagnostics; a skipped source is null, so its last-good section is
carried forward and does not become deletion authority. A matching-root object
scan also requires selected page reads to be complete before it can support
object-list absence evidence.

The capture still performs the required per-root singular observation and
binding-epoch reads, and required live-authority checks remain. The old late bulk `observations()` read was unused
publication metadata and is gone; no rollback path or new authority policy was
introduced. The holder is the one atomic publication point, and listener
failures leave that published snapshot standing. Targeted `reindex` loads one
previous snapshot, stores its one-page replacement before `SearchIndexer.syncPage`,
and deliberately does not fire the full checkpoint/search listener chain; a
targeted search-sync failure propagates after publication.

An unmarked live-root scan failure is skipped and retried on the next pass; a
root marked unavailable is sticky until restart. Deferred per-page recovery is
separate from that availability state. The safety rule remains that only explicit
EPOCH, OBJECT_LIST, GIT, or accepted OPERATOR authority can retire an absent
binding. See [ADR-0011](decisions/0011-multi-root-document-directories.md) and
[ADR-0012](decisions/0012-per-root-page-identity.md) for tracked rationale;
the [backend architecture map](backend-architecture.md) for the maintained current summary.
