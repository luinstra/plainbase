# 9. The config file is HOCON (`plainbase.conf`), not YAML, layered under env

- **Status:** Accepted
- **Date:** 2026-06-20
- **Deciders:** luinstra
- **Context:** Phase 4 (Principals, auth, roles, audit). Auth introduces many config knobs (`auth.mode`,
  trusted-proxy CIDRs, the insecure-bind override, session TTL, login rate-limit, password-login-disable,
  the public origin), which makes env-var-only configuration unwieldy. `PlainbaseConfig` is **env-only**
  today (`fromEnv`, `PlainbaseConfig.kt:85-98`); the docstring (`:12-13`) already anticipates a file layer
  ("`plainbase.yaml` in DATA_DIR is layered in by later phases (env always wins)"). Phase 4 is where that
  layer must land. This ADR fixes the **format**, which the master plan named as `plainbase.yaml`.

## Context

The master plan calls the config file `DATA_DIR/config/plainbase.yaml` (§89, §386). But the built reality
complicates YAML:

- **YAML at runtime is a new dependency.** `org.yaml:snakeyaml` is in the catalog **`testImplementation`-only**,
  deliberately (`gradle/libs.versions.toml:96` — "DIFFERENTIAL-ORACLE ONLY"). Serving YAML config at runtime
  would mean promoting it (or adding `kaml`/`snakeyaml-engine`) to the runtime classpath — a new allowlist
  entry plus native-gate justification, against an existing decision to keep YAML out of the runtime.
- **HOCON is already on the runtime classpath, for free.** `com.typesafe:config` is allowlisted
  (`dependency-allowlist.txt:13`) and present transitively via Ktor. A HOCON config file therefore adds
  **zero new runtime dependency** and respects the dependency-discipline gate.
- **HOCON fits the need better than YAML or JSON.** It is the idiomatic Ktor config format (Ktor's own
  `application.conf` is HOCON), supports comments and `include`, and — load-bearing here — has built-in
  **env-var substitution** (`${?PLAINBASE_HOST}`), which directly serves the "lots of knobs, env should still
  win/override" requirement. JSON (native-proven via kotlinx.serialization) was rejected for config because it
  has no comments and is hostile to hand-editing.

The constraint "serialization is kotlinx.serialization only" is about **wire/DTO serialization** (the Jackson/
Gson ban); reading typed config keys via Typesafe Config's `Config` API (`config.getString("auth.mode")`) is
config parsing, not wire serialization, and involves no reflection on app types.

## Decision

**The Plainbase config file is HOCON, named `DATA_DIR/plainbase.conf`, read via the already-present
`com.typesafe:config`.** Precedence is **env-always-wins**: the file supplies values, environment variables
override them (12-factor; preserves `fromEnv()` as the env-only fast path for the CLIs and the native spike).
**Secrets stay in the environment, never the committed file.** Values are read through Typesafe Config's typed
getters (no reflection on app types). Because Ktor uses Typesafe Config programmatically today rather than
loading a `.conf` from disk, A1 must **native-confirm the file-parse path** as part of its native-gate proof
(the library is GraalVM-known-good; the on-disk parse path is the unexercised bit).

This **supersedes the master plan's `plainbase.yaml`** (§89, §386); those references should be read as
`plainbase.conf` (HOCON).

## Consequences

**Positive**

- Zero new runtime dependency; the native bet is untouched (no YAML parser, no new reflection surface).
- Env-substitution + env-override come built in, directly answering the auth-config-knob explosion.
- Honors the existing "snakeyaml is test-only" stance and the dependency-discipline gate.
- Comments + `include` make an operator-edited config file pleasant — better than JSON, no new dep vs YAML.

**Trade-offs**

- Diverges from the master plan's stated `plainbase.yaml`; readers of the old plan must map yaml→conf (recorded
  here and in the Phase 4 plan's master-plan-deltas section).
- HOCON is less universally familiar than YAML to some operators; mitigated by shipping a commented reference
  `plainbase.conf` in the docs.
- The on-disk HOCON parse path is native-unproven until A1's spike confirms it (low risk; the library is widely
  used under GraalVM).

**Reversibility — high.** Config is read behind `PlainbaseConfig`'s construction path; the file format is an
implementation detail of one loader. Switching formats later (or supporting multiple) is a localized change with
no domain or contract impact.
