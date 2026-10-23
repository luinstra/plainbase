# Engineering rules

Read for code, architecture, dependency, storage and configuration work. The
[backend map](../backend-architecture.md) and [ADRs](../decisions/README.md) describe the implemented
boundaries; source and tests decide whether an old plan still describes the current checkout.

## Content and state

- Ordinary Markdown/assets in configured local roots or object storage remain current-content
  authority. Agent reads return raw file Markdown, not rendered text or search-index artifacts.
- Page identity is rooted: `(RootName, PageId)`. Generate new page IDs through the UUIDv7 provider;
  retain the existing version-agnostic UUID reader. See [ADR-0012](../decisions/0012-per-root-page-identity.md).
- `plainbase.db` holds durable application state, including identity and recovery records. It is not
  disposable merely because page files are authoritative. `search.db` and `discussions.db` are derived
  indexes. Discussion files live under each enabled root's `.plainbase/discussions/`; see
  [ADR-0013](../decisions/0013-discussions-are-files-in-a-per-root-collection.md).
- A scan proves what it read, not that unread pages were deleted. Unwitnessed bindings require the
  existing absence-proof/freshness protocol, not percentages, empty-directory heuristics or watcher
  events alone. Preserve limbo and recovery records. Review `IndexBuilder.AbsencePass`, `AbsenceProof`
  and the retirement transaction together when changing deletion or publication.
- Safety/authority inputs get no implicit defaults. Require callers to supply evidence explicitly,
  especially where an empty witness set permits more deletion. Put these requirements in types and
  port signatures rather than relying on one caller to remember a filter; see `RetirementRepository.applyProofs`.
- File formats should tolerate unknown keys and unrelated files while validating known values.
  Rewriters preserve unknown content and unrelated bytes. Keep explicit security/format boundaries;
  do not silently broaden accepted syntax. See [OKF compatibility](../okf-documents.md).
- Server and offline commands default directly to `$HOME/.plainbase`. An explicit nonblank `DATA_DIR`
  wins, including relative paths and container `/data`. The common loader guards legacy launch-CWD
  `data` state; do not silently migrate, merge or relocate documents. Shared state does not change
  relative content-path semantics. Use absolute configured root paths across launch directories.
  See [ADR-0015](../decisions/0015-server-home-directory.md) and [configuration](../configuration.md).

## Database concurrency and resource bounds

- Identify the driver before making a concurrency claim. `BeginImmediateSqliteDriver` uses a shared
  connection for memory URLs and a thread-local connection manager for ordinary file URLs, releasing
  connections outside transaction lifetimes. An in-memory concurrency test does not establish the
  file-backed failure mode. Prefer one SQL statement for a hot read requiring one consistent snapshot.
- Every app-DB transaction uses `BEGIN IMMEDIATE`, including read-only bodies, and holds the write
  lock from BEGIN. Keep expensive non-DB work (hashing, file I/O, network) outside it while retaining
  necessary transactional checks. Audit callers and cleanup whenever a change moves where exceptions
  can occur. See [SQLite operations](../operating-plainbase.md) and the
  [driver](../../server/src/main/kotlin/com/plainbase/frameworks/sqldelight/BeginImmediateSqliteDriver.kt).
- Size memory bounds against the configured heap and concurrency. The native 256 MiB default is the
  local, low-concurrency profile; server deployments can raise it. Avoid hard-coded budgets that assume
  every deployment has that heap. See [heap configuration](../configuration.md#heap-size-native-binary---not-an-env-var).

## Evolving contracts and reader addresses

- Pre-1.0 DTOs can evolve when the feature needs a better contract. Change server/client shapes and
  their goldens together, explain the change, and inspect actual consumers. Do not infer that there
  are no users from an old memory or treat a historical "frozen" label as the sole reason to reject
  a change. Preserve explicit published compatibility commitments, including the
  [versioned theme contract](../theming.md#compatibility).
- When a pre-1.0 namespace decision arises, propose useful reservations while they are cheap to remove;
  adding them later can refuse existing installations. Check actual registrations and refusal paths.
  Update test names/rationale with the behavior, not just booleans. See
  [ReservedSegments](../../server/src/main/kotlin/com/plainbase/domain/root/ReservedSegments.kt).
- Reader addresses come from server-provided page/folder URLs. Match a folder URL verbatim after a
  by-path 404; never slugify or invent it client-side. An authored index/README replaces the generated
  listing, and its bare-page address canonicalizes to the folder. Preserve the current-address guard
  before redirects so an outgoing page cannot undo a newer navigation. See the amendments in
  [ADR-0003](../decisions/0003-folder-landing-pages.md) and `DocsPage`/`folderByUrl`.

## Dependencies and boundaries

- **Server dependencies default to no.** Justify an addition against GraalVM native-image
  (reflection-free or supplied metadata). Record runtime-classpath changes with
  `./gradlew :server:writeDependencyAllowlist`; commit `server/dependency-allowlist.txt` with the
  catalog change. Do not weaken the allowlist to make a build pass.
- Netty, Jackson, Gson and Exposed are banned, including transitive resolution. Ktor uses CIO;
  serialization uses kotlinx.serialization. App state uses SQLDelight. Derived search/discussion
  databases use raw JDBC over the same xerial driver, never SQLDelight or a second driver. They have
  no migrations; schema changes use drop/recreate.
  See [ADR-0004](../decisions/0004-raw-jdbc-for-derived-search-db.md).
- Future compute-heavy services such as embeddings/OCR belong outside this binary. Existing embedded
  FTS5 is part of the current design, not permission to add a large in-process compute stack.
- Keep `domain/` free of framework imports and `frameworks/` adapters grouped by technology. Ports use
  natural nouns or `XxxProvider`; implementations use `<Tech><Port>`. Preserve the architecture tests
  (`DomainPurityTest`, `ChokePointArchitectureTest`) and shared guarded authorization boundaries.
- Extract pure decision logic where it clarifies ownership, without layering abstractions merely to
  satisfy a diagram. Use immutable snapshots/build-then-swap where appropriate; do not add speculative
  concurrency controls to single-owner code.

## Code style

- Match the cleanest nearby implementation. Use idiomatic Kotlin: standard guards for argument/state
  validation, collection/string builders, nullable results rather than sentinels, and direct iteration.
  Preserve domain-specific exception types when the contract requires them; avoid gratuitous rewrites.
- Kotlinter/ktlint and `.editorconfig` own formatting. Preserve the author's layout; 140 columns.
- Use kotlin-logging with lazy lambdas. Prefer companion-object loggers for classes and top-level
  loggers where file composition fits. `println` is for CLI output contracts only, never diagnostics.
  The level knob is `PLAINBASE_LOG_LEVEL`.
- Explain decisions and invariants in comments, not control flow. Avoid build-cycle labels and
  references that only make sense alongside gitignored plans. Give each non-obvious invariant one
  maintained explanation instead of repeating proof essays.
- Use the existing semantic CSS tokens for frontend changes; read [theming](../theming.md) before
  changing theme definitions or adding token values.
- For UI work, read the [v2 handoff](../design/handoff-v2/README.md) and its owner decisions before
  proposing alternatives. It already records typography, dark/light surfaces, editor preview, search,
  tree page-icon placement and the desktop scope of that design pass. Reopen them when the owner asks.
- Keep prose plain and direct. Use commas, parentheses or simple hyphens instead of em dashes.
