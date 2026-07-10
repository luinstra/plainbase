# 10. Object-storage backend: an S3-compatible bucket as the content authority

- **Status:** Accepted
- **Date:** 2026-07-08
- **Deciders:** luinstra (after two 2026-07-05 multi-model debates on wire-freeze, mirror-state, and DR;
  design hardened through the storage-revamp measure-twice + review-until-clean loops)
- **Context:** The storage-layer revamp (chunks C0-C6). Adds a second `storage.backend` (`object`)
  alongside the default `local`, letting a Plainbase instance serve an S3-compatible bucket as its
  authoritative content store. Freezes the contracts that revamp introduced. Builds on ADR-0004
  (derived-state discipline) and ADR-0008 (TLS at the proxy / outbound-HTTPS native concern).

## Context

Plainbase's thesis is a plain content tree you can walk away with (ADR-0004, DESIGN_SUMMARY). Local mode
makes `CONTENT_DIR` that tree. Cheap object storage (S3, Cloudflare R2) is an equally plain tree - a flat
set of objects any S3 tool reads - and is the natural cloud home for the same content. The design
question was how to serve a bucket as the authority WITHOUT breaking the native-image dependency bet (no
Netty/Jackson/Gson/Exposed, no AWS SDK reflection graph), without a second content contract, and without
a data-correctness regression in the write path.

The load-bearing tension: an S3 bucket is remote, has no filesystem watch, and each provider's
conditional-write and LIST-encoding semantics differ subtly. (Object mode REQUIRES a strongly-consistent
bucket - see the Decision; the design deliberately does NOT tolerate eventual-LIST consistency, and modern
R2 and AWS S3 are strongly consistent.) A naive "mount the bucket as a directory" approach either pulls in
a heavy dependency or silently loses updates under concurrency.

## Decision

**An S3-compatible bucket can be the authoritative content store behind the SAME `ContentStore` port**
(`server/src/main/kotlin/com/plainbase/domain/content/ContentStore.kt:14`), selected by
`storage.backend=object`. The design:

- **Bucket = authority, mirror = derived cache.** `DATA_DIR/mirror` (the worktree) and
  `DATA_DIR/mirror-state` are a derived, deletable local cache (the D2 caching-hybrid); delete them and
  they self-heal from the bucket on the next boot. This extends ADR-0004's derived-state law to the
  mirror. Reads serve the local mirror; the bucket enters the mirror only via boot `hydrate()` or the
  background poll.
- **Backend consistency requirement (provider precondition).** Object mode REQUIRES a bucket with strong
  **read-after-write AND strong LIST consistency** - both **R2 and AWS S3 conform**. The design does NOT
  tolerate an eventually-consistent-LIST backend: the background poll diffs the bucket LIST against
  `mirror-state`, so a stale LIST could transiently reap a just-saved page from the mirror/index and (with
  `git.enabled=true`) mint a spurious delete-then-restore commit before the LIST catches up. Documented for
  operators in `configuration.md` and `operating-plainbase.md`.
- **Hand-rolled SigV4 over the allowlisted Ktor CIO engine.** The client
  (`frameworks/objectstore/S3ObjectClient.kt`) is a hand-written SigV4 signer over the already-present
  CIO client - **zero new dependencies**. LIST responses are parsed by a hand-rolled five-element
  extractor (`frameworks/objectstore/ListResponseParser.kt`) rather than pulling in an XML library. The
  library scout that disqualified MinIO / aws-sdk-kotlin / bluetape4k (and AWS SDK v2 +
  url-connection-client) is recorded in the owner decisions below.
- **Git over the mirror + bundle DR (G2).** With `git.enabled=true`, saves commit over the mirror
  worktree exactly as local mode does, and the `.git` history ships to the bucket as
  `<prefix>/.plainbase/history.bundle` (`frameworks/git/GitBundleDr.kt`), so a lost `DATA_DIR` recovers
  commit-grained history, not just content. The bundle transfer STREAMS to/from a file (`getToFile`/
  `putFromFile`, stream-hashed for SigV4), so a full-history bundle can never OOM a small replacement host
  and defeat the boot-refusal invariant that protects the C5 restore sequence.
- **The `MirrorState` invariant + the M1 choke point.** A `mirror-state` entry asserts "the mirror copy
  of this key equals the bucket generation that produced this etag; an unknown key is treated as absent"
  (`frameworks/objectstore/MirrorState.kt`). It is mutated only through the two-mutator choke point in
  `ObjectContentStore`, under the **no-network-under-monitor rule** (the `applyLock` topology, with the
  `applyLockForTests` tripwire seam, `frameworks/objectstore/ObjectContentStore.kt:79-80`).

### The six owner decisions (2026-07-05), recorded

1. **Wire freeze.** PB-WRITE-1 stays frozen; the write-conflict disambiguation is observability-only; a
   future wire thaw is deferred, not taken now.
2. **Lock + doc, not a lease.** `DataDirLock`-on-adopt plus the one-instance-per-bucket doc rule IS the
   v1 exclusion posture; a bucket-lease heartbeat is deferred to a future v0.x.
3. **No branching artifact.** ONE config-selected artifact serves object mode in both the native and
   container tiers; there is never a second, object-mode-only build.
4. **R2 primary, AWS S3 secondary/compat.** R2 is the first-class target; S3 is the compatibility
   variant.
5. **Hand-rolled SigV4 client stays.** Library scout recorded: MinIO / aws-sdk-kotlin / bluetape4k
   disqualified; AWS SDK v2 + the url-connection-client is the recorded escape hatch on the container
   tier - a client swap behind the `ObjectStoreClient` SPI, never an artifact branch.
6. **Backups are operator-owned** (the rev-3.4 DR line below).

### DR responsibility (rev-3.4)

Mechanism-in-the-app / schedule-in-ops. The app owns the mechanisms (the dirty-page journal, bundle
shipping, export); the operator owns the schedule (backups, per-backend guidance in the operating
guide). **S3 versioning may never become a dependency**: it is an operator convenience, not a Plainbase
mechanism. **R2 has no native object versioning** (lifecycle management is GA, but lifecycle is not
versioning; versioning is on Cloudflare's public roadmap only - re-verified 2026-07-08 against the
Cloudflare docs), so the R2 backup recipe is a scheduled external copy, never in-bucket versioning. The
git-disabled startup WARN (`Application.kt:235-241`) names the current-state-only exposure. (The
rev-3.3 snapshot scheduler + manifest writer were dropped; the manifest is deferred, not banned.)

### R16 fail-closed posture

The object-mode boot's first bucket LIST doubles as the TLS + SigV4 + credential self-check and REFUSES
actionably on rejection (`ObjectContentStore.kt:338-343,990-1004`, "never disable certificate validation
to fix this"). Honest limit: it proves READ, not WRITE - read-only credentials pass boot, then every PUT
403s and maps to the frozen retryable 503 forever, so operators must grant PUT. A TLS regression answers
with config fail-closure plus the escape hatch, never an artifact branch; the standing NativeSpike
TLS-loopback check (spike gate 9/9) is the permanent tripwire.

### ADR-0008 partial retirement

ADR-0008's "outbound HTTPS is native-unproven" finding is **retired** by the credential-free linux-x64
spike (`ci.yml`, 9/9 on every PR) plus the macos-arm64 real-R2 proof (2026-07-06, cert validation on).
The residual Linux-real-system-CA-trust-against-R2 leg stays a documented nice-to-have (owner-deferred
2026-07-07), not a gate. ADR-0008's inbound-TLS-at-the-proxy decision is unchanged.

### The SP1 conditional-write provider semantics (frozen source of truth)

The Q8 outcome mapping (`PutOutcome.PreconditionFailed.status`, `ObjectStoreClient.kt:102-111`) keys off
this table. It is copied verbatim from the C0/SP1 real-provider findings; this ADR is the durable
record (the `.crew/` capture log is local/gitignored). **R2 returns 412 for BOTH precondition failures**
(create-conflict AND stale-CAS) - a single code, not a 409/412 split. **AWS S3 columns are PENDING** (no
credentialed S3 run yet) - never fabricated.

| Probe | R2 | AWS S3 |
|---|---|---|
| `PUT If-None-Match:*` on existing key (status code) | **412** | PENDING |
| `PUT If-Match` stale etag (status code) | **412** | PENDING |
| Concurrent-create race (409-vs-412, if probed) | not separately raced; create-on-existing = **412** (R2 uses 412, NOT 409, for both precondition failures) | PENDING |
| ETag quoting / weak forms observed | strong, double-quoted, MD5-shaped hex (e.g. `"420d4694ba708a5fc1042dcac1507177"`); no weak `W/` forms seen | PENDING |
| Refused CAS left object untouched (`cas-stale-intact`) | PASS (bytes + etag untouched) | PENDING |
| DELETE of a missing key | idempotent success (2nd DELETE of the same key returned success) | PENDING |
| LIST `encoding-type=url` key encoding observed (space as `+` or `%20`?) | **space = `%20`, slash = `%2F`, `&`=`%26`, `$`=`%24`, `+`=`%2B`, non-ASCII = UTF-8 %-bytes** (never `+`-for-space). This is why whole-key decode MUST allow `%2F`->`/` and MUST NOT map `+`->space. | PENDING |

## Consequences

**Positive**

- The same `ContentStore` contract, the same routes, and the same PB-WRITE-1 / PB-READ-2 goldens serve
  both backends - one content contract, one code path above the port.
- Zero new dependencies for the native bet: SigV4 + LIST parsing are hand-rolled over CIO +
  kotlinx.serialization; the local default stays byte-identical (R9 zero-construction, proven by a
  counter, `ObjectContentStore.kt:1014-1015`).
- Conditional writes make content lost-update-safe under a racing writer; the mirror is a deletable
  cache, so recovery is "delete and re-hydrate."

**Trade-offs**

- One instance per bucket (v1): a second writer degrades the loser's mirror coherence and races the
  bundle key (instance-local staleness, never content loss). A lease is deferred.
- Read-only credentials pass boot but fail every write (the honest IAM limit above), by deliberate
  design - no per-boot PUT probe that would write on every boot.
- AWS S3 conditional-write semantics are still PENDING a credentialed run; R2 is fully characterized.

**Reversibility - high.** The `ObjectStoreClient` SPI isolates the client (the Q7 escape hatch: swap in
an SDK-backed client behind the SPI on the container tier, never an artifact branch); the mirror is
deletable; `storage.backend=local` remains the untouched default.

## What freezes with this decision

This section is the storage-revamp re-freeze record (its durable home - `.crew/` plans are gitignored).
Each item cites real code:

1. **The backend-neutral `ContentStore` contract** prose and the port surface (minus
   `resolveRepoRelativePath`), plus the defaulted `CasResult.Unreadable.targetMutated`
   (`ContentStore.kt:158-167`) and `CreateResult.Unreadable.targetMutated` (`ContentStore.kt:196-203`).
2. **The `ObjectStoreClient` SPI** + `PutCondition` / `PutOutcome` (`ObjectStoreClient.kt:14-114`); the
   **SP1 conditional-semantics table** copied into this ADR above (the durable frozen source of truth);
   and the **`ListResponseParser` golden set** (`ListResponseParser.kt` + its golden captures under
   `server/src/test`).
3. **The M1 mirror-state invariant** ("an entry asserts mirror == bucket-generation; unknown => absent")
   enforced through the `MirrorState` two-mutator choke point, the no-network-under-monitor rule
   (`applyLock`, `ObjectContentStore.kt:79-80`), and the hybrid-monitor lock topology.
4. **The C5 restore sequence** (completeness gate -> init -> fetch -> reset --mixed -> strict hydrate ->
   one hermetic plumbing reconcile commit, `GitBundleDr.kt` + `GitPlumbing.kt`), including the bounded
   `.git.pre-restore-*` husk reap (keep-newest-3) added in C6.
5. **The rev-3.4 DR responsibility line** - mechanism-in-the-app (journal, bundles, export) /
   schedule-in-ops (backups operator-owned, per-backend guidance in the ops doc), the
   S3-versioning-never-a-dependency rule (R2 has none), and the git-disabled startup WARN
   (`Application.kt:235-241`). (The rev-3.3 manifest schema + snapshot layout/knobs were removed; the
   manifest is deferred, not banned.)
6. **The Q10 canonical-content promise** (one authority per deployment: directory locally, bucket in
   cloud) and the **Q9 config matrix** (the object-mode env keys + defaults,
   `docs/configuration.md:38-46` / `PlainbaseConfig`); plus this ADR recording all six owner decisions
   (incl. the deferred bucket lease, the deferred wire thaw, the Q7 escape hatch) and the ADR-0008
   outbound-HTTPS retirement.
7. **The rev-3.2 R16 fail-closed posture** (object mode on native refuses without proven TLS; a
   tripwire failure answers with config fail-closure + the escape hatch, never an artifact branch) plus
   the standing NativeSpike TLS-loopback check as a permanent spike-gate member.
8. **PB-WRITE-1 / PB-READ-2 remain frozen throughout** (never actually thawed - owner-locked at the
   debate; verified untouched at close-out): the golden suites `WriteGoldenTest.kt`, `ReadGoldenTest.kt`,
   `ForeverApiGoldenSuite.kt`, and the object-mode parity run `ObjectHybridRouteParityTest.kt`.
