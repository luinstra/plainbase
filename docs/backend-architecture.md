# Backend architecture and current behavior

Current-state review: 2026-09-18. This map includes the Plan06 transport ownership work; it does not claim those
changes are merged. The reviewed source stamp records the pre-commit base plus the code/config content fingerprint:
`HEAD 89a28b3e3fecff615560a8898b2c577114799461; non-Markdown content SHA-256 533330ed7b9f7390c893cbc699f09aca314bcaf3f0cd5d104f4564c07d95e809`.

## Authority and durable state

| State | Current authority and recovery boundary |
| --- | --- |
| Content bytes | Local configured root directories own Markdown/assets, or the object bucket owns them in object mode. `DATA_DIR/mirror` and `mirror-state` are derived object-mode caches. |
| Identity and application state | `DATA_DIR/plainbase.db` owns `id_map`, materialization, retirement history, aliases, users/tokens/sessions, roles, audit and proposal workflow. A materialized page ID also lives in frontmatter; an unmaterialized binding is DB-only and cannot be reconstructed with the same permalink after DB loss. Back up the content authority and this database; see [backup guidance](operating-plainbase.md#backups). |
| Search | `DATA_DIR/search.db` is raw-JDBC/FTS5 derived state and is deletable/rebuildable; [ADR-0004](decisions/0004-raw-jdbc-for-derived-search-db.md) is the boundary. |
| Published reads | `IndexBuilder.current` is the atomically published immutable `PageIndex`. It is a serving snapshot, not a transaction spanning the app DB, search DB and content source. |
| History | Git and object-mode history bundles preserve optional commit history, which cannot be rebuilt from current content alone. They do not replace current content authority. See [Git ADR](decisions/0006-git-via-system-binary-not-jgit.md), [object-storage ADR](decisions/0010-object-storage-backend.md) and [operating guidance](operating-plainbase.md). |

The authority split is implemented by [`PageIdentityService.resolve`](../server/src/main/kotlin/com/plainbase/domain/service/PageIdentityService.kt),
the [`IdMapRepository`](../server/src/main/kotlin/com/plainbase/domain/repository/IdMapRepository.kt),
the [SQLDelight identity schema](../server/src/main/sqldelight/com/plainbase/frameworks/sqldelight/IdMap.sq),
[`SearchDb`](../server/src/main/kotlin/com/plainbase/frameworks/search/SearchDb.kt),
and [`ObjectContentStore`](../server/src/main/kotlin/com/plainbase/frameworks/objectstore/ObjectContentStore.kt).
See [ADR-0012](decisions/0012-per-root-page-identity.md) for rooted identity and retirement guarantees.

## Composition and ownership

```text
CLI/config + root boot gates
        -> owned runtime/resource assembly
        -> one GuardedRead/Mutating/Proposal facade graph
        -> REST routes and MCP mount
        -> shared neutral protocol contracts where the B/C move placed them
```

[`runOwnedServer`](../server/src/main/kotlin/com/plainbase/Application.kt) owns startup resources and lock lifetime.
[`buildGuardedApplication`](../server/src/main/kotlin/com/plainbase/frameworks/ktor/GuardedApplicationFactory.kt) builds the one guarded graph;
[`RestModule`](../server/src/main/kotlin/com/plainbase/frameworks/koin/RestModule.kt) owns its route-context registration. Shared
wire vocabulary is under `frameworks/protocol`; HTTP-only response/status/cookie behavior remains in `frameworks/ktor`, and
MCP session/SSE behavior remains in `frameworks/mcp`. Build implementation is in
[`buildSrc`](../buildSrc/src/main/kotlin/com/plainbase/buildlogic/), with product/dependency settings remaining in the server Gradle files.
The configuration seam is documented in [configuration boundaries](design/configuration-boundaries.md) and the
[backend configuration compatibility report](reports/backend-config-compatibility.md).

The current source includes the earlier runtime, configuration, identity and lifecycle outcomes plus the present Plan06
neutral protocol extraction. Eligible single-local-root CREATE confirmation now uses one transaction. The measured
3,000-page workload improved by 71–74%; full scanning, rendering, link repair and publication still remain. These are
workload-specific results, not edit/search or large/multi-root guarantees; see the
[backend operating envelope](reports/backend-operating-envelope.md).

## Startup, recovery and shutdown

Startup proceeds in this order: configuration/filesystem gates; root availability/history preparation; the `DATA_DIR`
lock; app database and resource construction; object hydration/history restore; watcher setup; initial rebuild and
snapshot publication; dirty-page and applying-proposal reconciliation; then the shutdown hook immediately before the
server starts. The implementation and ordering comments are in [`Application.kt`](../server/src/main/kotlin/com/plainbase/Application.kt)
and [`GitBundleDr.kt`](../server/src/main/kotlin/com/plainbase/frameworks/git/GitBundleDr.kt).

Reads use one published snapshot per gated page read: [`GuardedReadFacade`](../server/src/main/kotlin/com/plainbase/frameworks/ktor/GuardedReadFacade.kt)
resolves and checks policy before passing that snapshot to [`PageService`](../server/src/main/kotlin/com/plainbase/domain/service/PageService.kt).
Writes, rebuild/publication and recovery remain serialized by their existing owners. The private
`IndexBuilder.AbsencePass` captures freshness before source evidence and mints only accepted absence proofs;
unwitnessed rows remain limbo rather than becoming deletion authority. A root outage, incomplete watcher coverage and
per-page limbo are separate states.

Shutdown closes admission and drains admitted work before watchers, schedulers, Git maintenance, disaster recovery, transport/database
resources and the `DATA_DIR` lock. [`ServerResourceOwner`](../server/src/main/kotlin/com/plainbase/frameworks/lifecycle/ServerResourceOwner.kt)
uses [`CompletionWait`](../server/src/main/kotlin/com/plainbase/frameworks/lifecycle/CompletionWait.kt) to wait through
interrupts. Forecast durations warn operators; they do not cap completion, so a stuck collaborator can leave shutdown
pending indefinitely. Production grace/default policy is unchanged; see the
[shutdown measurements and policy](reports/server-shutdown-and-git-measurements.md).

## Operating limits

- Object mode remains one instance per bucket; `DataDirLock` only excludes a shared local `DATA_DIR`. See [the deployment rule](deploy/object-storage.md#one-instance-per-bucket-the-v1-rule).
- Rebuilds/publication are serialized in [`IndexBuilder`](../server/src/main/kotlin/com/plainbase/domain/service/IndexBuilder.kt); `search.db` has one synchronized writer and bounded reader pool in [`SearchDb`](../server/src/main/kotlin/com/plainbase/frameworks/search/SearchDb.kt).
- `/healthz` is unauthenticated liveness: `status` stays `ok` while per-root `available`, watcher `coverage` and `limbo` report different facts. Root unavailability is sticky until restart; watcher coverage can recover.
- Enforced roles are global: VIEWER reads, EDITOR also edits/creates, and ADMIN also approves/manages. Agent READ_ONLY maps to VIEWER; PROPOSE/COMMIT map to EDITOR and cannot approve. Token mode is re-read per guarded call. These are not page ACLs; `auth.mode=off` bypasses the role matrix, while root editability still gates writes.
- Search is embedded FTS5. Search engine totals/ranking/snippets can lag the current page snapshot; the 256-entry FTS rebuild batch bounds queued JDBC entries/flushes, not total retirement work or a cumulative SQLite-variable count. See [FTS5 operations](operating-plainbase.md#searchdb-is-derived-state).
- Meilisearch, OCR and embeddings remain future/out-of-process proposals; current search is FTS5.

## REST and MCP ownership

Both transports use the same guarded facades and per-call policy. Shared successful DTO/result shapes are kept in the neutral
protocol package, but transport errors and session behavior remain intentionally distinct.

| Surface | REST | MCP |
| --- | --- | --- |
| Successful shared results | HTTP status/body/header contract | Tool result over the MCP session; successful shared JSON is characterized against REST where specified |
| Unknown read root | HTTP 404 with `page_not_found` after the read gate | `invalid_root` for the connect-authenticated root-pin path |
| Ordinary failure | HTTP status and `internal_error`/HTTP envelopes | MCP `isError` and `internal`/`not_found` envelopes |
| Ambiguity | HTTP status plus candidate URLs | Candidate root arguments and `isError` |
| Proposal creation | Shared response structure; each insert mints a new proposal ID | Same structure; a separate insert naturally has a different ID |

Direct writes remain REST-only under COMMIT-token policy; MCP writes use the proposal flow.

The detailed tool guide is [Connect your agent](connect-your-agent.md#parity-with-the-rest-api). The bounded current
characterization lives in [`McpSurfaceTest`](../server/src/test/kotlin/com/plainbase/frameworks/mcp/McpSurfaceTest.kt),
[`McpRootPinTest`](../server/src/test/kotlin/com/plainbase/frameworks/mcp/McpRootPinTest.kt), and the maintained
[contract ownership report](reports/backend-contract-ownership.md). No broader MCP modernization is implied here.

## Verification, artifacts and deferred policy

The documented policy in [AGENTS.md](../AGENTS.md) and [CLAUDE.md](../CLAUDE.md) makes the universal JAR the release floor
and says native failures block only native artifacts. Current automation is stricter. The CI workflow has
separate JVM/native jobs, but its always-run `ci-gate` depends on six jobs and fails when a dependency is `failure`,
`cancelled` or `skipped`; its completeness script checks that every job is represented. Release assembly starts only
when the JAR job succeeds, then refuses publication unless linux-x64, linux-arm64, macos-arm64, JAR and image all succeed.
See [`ci.yml`](../.github/workflows/ci.yml) and [`release.yml`](../.github/workflows/release.yml).

Therefore the documented JVM-only native escape hatch in [DEVELOPMENT](DEVELOPMENT.md#the-native-dependency-spike)
is not supported by current release automation. These files do not establish live branch-protection settings or owner
intent to reconcile the artifact, aggregate and release contracts. That policy question remains deferred; this map changes
no workflow, AGENTS/CLAUDE rule or gate.

The current revision and claim-level traces are frozen in the [ownership report](reports/backend-contract-ownership.md).
