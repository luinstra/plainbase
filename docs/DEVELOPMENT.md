# Development

Contributor-facing internals: building, the CI gates, the native dependency
spike, and the architecture rules. For contribution mechanics (DCO sign-off,
commit style, dependency policy) see [CONTRIBUTING.md](../CONTRIBUTING.md).

## Building

```sh
./gradlew build                          # backend + frontend + tests (universal JAR floor)
./gradlew :server:run --args=serve       # run the server on the JVM
./gradlew :server:run --args=spike       # full-stack native dependency spike (JVM)
./gradlew :server:run --args="root list" # the topology CLI: root add|remove|list (docs/configuration.md)
./gradlew :server:nativeCompile          # native binary (requires GraalVM 25+ on JAVA_HOME/GRAALVM_HOME)
```

Requirements: JDK 21+ (the build auto-provisions the 21 toolchain for
bytecode). Node is downloaded by the Gradle build - no local install needed.

The Linux-only PID1 regression gates are separate from ordinary test discovery:

```sh
./gradlew :server:gitZombieJvmPid1       # Java 21 JVM launcher
./gradlew :server:gitZombieNativePid1    # GraalVM nativeTestCompile executable
./gradlew :server:gitZombieForcedTimeoutPid1
```

CI runs the JVM and native positive tasks; the forced-timeout task verifies watchdog escalation and cleanup for checkpoint acceptance.

They require Linux permissions/capabilities to create the privileged PID, mount, and network namespaces used by
the launcher. Separately, they require noninteractive `sudo -n` and trusted executable `sudo`; `unshare` and
`setpriv` are from util-linux, while `timeout` and `id` are from coreutils, all in `/usr/bin` or `/bin`. No
separate `kill` helper is a prerequisite. The fixtures also require executable `/bin/sh` and `sleep` with
fractional-second support on `PATH`. The JVM gate uses Java 21; the native gate uses the
pinned GraalVM toolchain below. CI gives its JVM and native PID1 steps a five-minute ceiling. Run reports and retained
evidence are under `server/build/reports/g3z/jvm/<run-id>/`, `server/build/reports/g3z/native/<run-id>/`, or
`server/build/reports/g3z/forced/<run-id>/`,
with preparation/staging material under `server/build/g3z/`. On non-Linux hosts, ordinary test discovery may
report a topology-required skip/abort, but that is distinct from the required one-body PID1 successes.

For native builds, the repo pins GraalVM via [asdf](https://asdf-vm.com/) -
`.tool-versions` selects `graalvm-community-25.0.2`, so inside the repo
`java` and `native-image` resolve to the same GraalVM the CI native gate
uses:

```sh
asdf install        # one-time: installs the pinned GraalVM
```

## What CI checks

Five jobs gate `main` (`.github/workflows/ci.yml`):

- **`build-test`** - the JVM universal-JAR floor: `./gradlew build`, the positive `gitZombieJvmPid1`
  control, and the full-stack dependency spike.
- **`enforced-auth-smoke`** - the builtin auth/CSRF matrix on loopback (anon `401`, bootstrap, CSRF
  present/absent/cross-origin, a PB-WRITE-1 save, an agent-bearer read + REST revoke). Every other
  job here boots `auth.mode=off` by default, so this is the one job that actually exercises
  enforced-mode auth.
- **`docker-image`** - the compose-tier image build plus a non-loopback proxy/transport smoke (a
  `421` transport refusal and the full proxy CSRF path - only reachable from outside loopback).
- **`native-gate` (linux-x64)** - `nativeCompile` → `nativeTest` → the positive `gitZombieNativePid1`
  control → the spike (9/9) → the enforced-auth smoke again, against the native binary → the native-startup
  regression tripwire.
- **`frontend-smoke`** - Playwright, booting both an auth-off and an enforced-builtin server;
  carries the CSP zero-violation gate (`csp.spec.ts`) and the enforced-builtin approval flow
  (`review.spec.ts`). Deliberately outside `./gradlew build` - a browser-download flake must never
  paint the JAR floor red.

Release builds (`.github/workflows/release.yml`) produce the universal JAR
plus three native binaries: linux-x64, linux-arm64, and macos-arm64.

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
   Current honest state: macos-arm64 PROVEN 2026-07-06, re-proven 2026-07-29 after the Content-Type
   emission regression the second run caught (see the platform table); linux-x64 credential-free TLS+SigV4
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
3. **The two required DR drills**, if the release touched storage / git / DR code paths: content restore
   and bundle-history restore (recipes in
   [operating-plainbase.md](operating-plainbase.md#object-mode-dr-drills-operator-recipes)). Rehearse
   each for real and REFRESH its dated "Rehearsed for real" record in the ops doc (date, provider,
   what was observed) - the record reflects the most recent rehearsal, not a one-time checkbox.
4. **Existing floors** (already CI-automated, listed for completeness): `./gradlew build`, the native
   gate (`nativeCompile` -> `nativeTest` -> spike 9/9).

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
the local/gitignored absence-authority plan
`../.crew/plans/draft-implementation-plans-to-get-plainbase-design.md` and phase
records under `.crew/reports/` are supplemental execution context.
