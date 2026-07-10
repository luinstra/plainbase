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

## Pre-release checklist

CI covers everything that runs without credentials or a real bucket. The object-storage backend adds a
handful of gates CI structurally CANNOT run - they need real credentials and a real bucket, so they are
owner-run before a release, not part of any automated floor. None of these block a normal PR merge; they
gate a *release* that ships (or touches) the object-storage backend.

1. **Credentialed `plainbase s3-smoke` from the NATIVE binary, per release platform** (R2 primary; S3
   compat when creds exist), certificate validation ON (the command has no insecure flag). Record green
   runs in the deploy guide's [platform table](deploy/object-storage.md#platform-support-honestly).
   Current honest state: macos-arm64 PROVEN 2026-07-06 (real R2); linux-x64 credential-free TLS+SigV4
   spike banked in CI; the linux-x64 **real-R2** credentialed smoke is a documented nice-to-have
   (owner-deferred, run when convenient, **NOT a release blocker**); linux-arm64 / windows-x64 docs-only
   until proven.
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
   each for real and fill in its "not yet rehearsed" placeholder in the ops doc.
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
