# Operating Plainbase

Operator notes for running Plainbase in production and on a dev box. This document covers search
freshness, the manual-reindex paths, the filesystem-native virtue, adopting an existing repo,
backups and disaster recovery, performance budgets and the startup gate, and the recorded v0.1
limitations.

For **single-sign-on behind a reverse proxy** (`auth.mode=proxy`), see
[`deploy/reverse-proxy-sso.md`](deploy/reverse-proxy-sso.md) and the standalone Caddy + oauth2-proxy
reference stack under `deploy/proxy/`.

## The content tree is plain Markdown on disk

Plainbase's canonical content tree is **plain Markdown on disk** - the tree *is* the product. Because
of that, standard tools work directly on your content with **no Plainbase process running**:

```
rg "rolling deploy" content/        # ripgrep across the tree
grep -r "owner: platform" content/  # frontmatter scan
fzf < <(find content -name '*.md')  # fuzzy file navigation
```

This is a **documented product property, not a feature to build**. Plainbase's search is *additive* -
ranked, section-granular, and cited - never a gatekeeper between you and your files. If Plainbase
is down, your content is still fully readable, searchable, and editable with the tools you already
have.

## Search freshness: editing files outside Plainbase

Plainbase watches the main content root (`CONTENT_DIR`, or `roots.main.path` when a `roots {}`
block is configured) and re-indexes when files change, so an edit made outside Plainbase
(in your editor, via `git checkout`, etc.) becomes searchable automatically. The end-to-end latency
is **debounce (0.5 s) + a full index rebuild + the search sync**.

### Platform note - the 5-second promise binds Linux

- **Linux** (the deployment platform) uses `inotify`: file-change events arrive in milliseconds. The
  "searchable within 5 seconds" promise is an automated test on Linux.
- **macOS** dev boxes use the JDK's `PollingWatchService` (multi-second poll interval; the
  `com.sun.nio.file` sensitivity modifiers are not reliably effective on JDK 21+). So **the 5-second
  promise does not bind macOS.** The practical answer on a Mac is a manual **rescan**
  (`POST /api/v1/admin/rescan`): it re-scans the main content root, picks up the just-edited file, and
  diff-syncs search. **Reindex (below) will NOT surface a fresh disk edit** - it rebuilds the search
  engine from the *already-published* page snapshot, so a file the watcher hasn't picked up yet isn't
  in that snapshot. From Phase 3, Plainbase-initiated saves are immediate on every platform.

### Tree-size scaling caveat

The 5-second figure is **scoped to small/mid trees.** End-to-end latency is debounce + a *full*
index rebuild + the sync; at the 1,000-page corpus the rebuild budget alone is under 5 s, so the
end-to-end promise holds only where the rebuild is roughly ≲ 4 s. **Large trees converge at rebuild
speed, not in 5 seconds.** Manual reindex/rescan is always available, and an incremental indexer is
the named future path (not built today).

## Manual reindex: the two paths

Both rebuild the search index; neither changes a content file, and neither is the tool for
surfacing a *fresh* disk edit on a running server (that's `rescan` - above). The endpoint rebuilds
from the live published snapshot; the offline CLI re-scans the tree itself (no server is running to
hold a snapshot).

### `POST /api/v1/admin/reindex` - prefer this on a RUNNING instance

Reindexes in-process against the live published snapshot, with a single-flight guard: a concurrent
call returns `409 reindex_in_flight`. This forces a full generation-swap rebuild of the search
engine - distinct from `POST /api/v1/admin/rescan`, which re-runs the page-index pass and then
*diff-syncs* the engine. Use reindex when you want the search index rebuilt from scratch (e.g. after
restoring or replacing `search.db`).

```
# auth.mode=off (the local-dev tier) - no auth/CSRF. Behind a proxy or with builtin auth, this
# admin mutation needs an authenticated session + the X-CSRF-Token from GET /api/v1/session.
curl -X POST http://localhost:8080/api/v1/admin/reindex
# {"status":"ok","pages":42}
```

### `plainbase reindex` - the OFFLINE/ops path

For when the server is **down**, or for a scripted operational reindex:

```
CONTENT_DIR=./content DATA_DIR=./data plainbase reindex
# reindex: rebuilt the search index for 42 page(s) under /abs/path/to/content
```

Like `serve`, the offline CLIs (`reindex`, `adopt`) read `DATA_DIR/plainbase.conf` (env still wins), so a
file-configured `storage.backend=object` makes them operate on the bucket mirror - not the local
content root - matching the running server for the same `DATA_DIR`. A file-configured `roots {}`
block likewise makes them operate on `roots.main.path`, not an ignored `CONTENT_DIR`.

**Do not run `plainbase reindex` against a live server** - use the endpoint instead. The CLI and a
running server are separate processes with separate write monitors; while SQLite WAL +
`busy_timeout` prevent *corruption*, they do not prevent the CLI silently publishing an *older*
generation over the server's newer one (a freshness regression). To make this safe, both the server
and the CLI take an advisory lock on `DATA_DIR/plainbase.lock`: the server holds it for its lifetime,
and the CLI **refuses to run while a server holds it**, exiting with:

```
reindex: a Plainbase server is holding /abs/path/to/data - stop it, or use POST /api/v1/admin/reindex on the running server
```

Exit codes: `0` success, `1` runtime failure (including the lock refusal), `2` usage error.

## `search.db` is derived state

`DATA_DIR/search.db` is rebuildable from the content tree at any time and **deletable with zero data
loss** - there are no migrations, ever. Delete it and reindex (or just restart, or let the next
content change trigger a rebuild): the engine-truth diff self-heals from an empty index back to the
full corpus. Only the content tree and `DATA_DIR/plainbase.db` carry durable state.

One clarification on the delete-and-rebuild path: **stop the server before deleting `search.db`**
(or just restart afterwards - a fresh boot recreates and repopulates it). Deleting the file under a
running server unlinks it while the server's open connections keep reading and writing the unlinked
copy, so the on-disk file only reappears on restart. On a *running* server, use
`POST /api/v1/admin/reindex` for a rebuild-in-place instead - never a live delete.

## When to upgrade to Meilisearch

Plainbase's default search is embedded SQLite FTS5 - zero containers, ranked and section-granular
out of the box (see [the content tree is plain Markdown on disk](#the-content-tree-is-plain-markdown-on-disk)
above: search is additive, never a gatekeeper). Meilisearch is a **deliberate future upgrade
tier**, not a gap in the default - reach for it when you want typo tolerance, meaningfully better
relevance ranking, or CJK tokenization that FTS5 doesn't do well.

Meilisearch runs **out of process**. Compute-hungry search features never live in the native
binary - the same pattern that keeps embeddings/OCR out of it too (project `CLAUDE.md`). The
`docker-compose.yml` wiring for it is **reserved, not wired**: the `SEARCH_ENGINE`/`MEILI_URL`
lines there are commented out, and no `meilisearch` service exists in the compose file today.
Turning this tier on is future work - this section documents the intended shape, not something you
can flip on with an env var (there isn't one; see
[Configuration](configuration.md#meilisearch-is-not-a-config-key)).

Meilisearch's own query-latency budget is checked manually today, not gated in CI - unlike the
shipped FTS5 p95 < 200 ms budget (see [Performance & the startup gate](#performance--the-startup-gate)),
which a corpus perf test enforces on every build.

## Backups

**Back up whatever holds the authoritative content.** Which store that is depends on `storage.backend`:
in the default **local** mode it is the main content root - `CONTENT_DIR`, or `roots.main.path` when a
`roots {}` block is configured (see
[the content tree is plain Markdown on disk](#the-content-tree-is-plain-markdown-on-disk) above); in
**object** mode it is the S3-compatible **bucket**, and `CONTENT_DIR` is ignored entirely (the
Object-storage subsection below covers that case). Back up `DATA_DIR/plainbase.db` too in EITHER mode
if users, agent tokens, proposals, roles, or the audit log matter to you - it's the one piece of
`DATA_DIR` holding *real*, non-derived state. `DATA_DIR/search.db` needs no backup at all: it's fully
[derived state](#searchdb-is-derived-state), rebuildable from the authoritative content at any time
(and in object mode `DATA_DIR/mirror` / `DATA_DIR/mirror-state` are likewise derived and need none).

### Object-storage backend (`storage.backend=object`)

In object mode the S3-compatible **bucket** is the canonical content store - back IT up, the same way
you'd back up the content root locally. `DATA_DIR/mirror` and `DATA_DIR/mirror-state` are derived, deletable
cache (delete them and they self-heal from the bucket on the next boot), so they need no backup; `plainbase.db`
still holds real state and still wants one.

**Consistency requirement.** Object mode needs a bucket with **strong read-after-write AND strong LIST
consistency** - R2 and AWS S3 both provide this. On an eventually-consistent-LIST S3-compatible backend a
just-saved page can be transiently reaped from the mirror/index by a poll that LISTs a stale view (and, with
`git.enabled=true`, mint a spurious delete then restore commit) before the LIST catches up. Do not point
object mode at a backend that only offers eventual LIST consistency.

With `storage.backend=object` **and** `git.enabled=true`, commit-grained history over object mode is
available: every save commits over the `DATA_DIR/mirror` worktree exactly as local mode does, and the
`.git` history itself ships to the bucket as a bundle (`<prefix>/.plainbase/history.bundle`) on a
debounced cadence (20 commits or 300 seconds, whichever comes first, plus an immediate best-effort ship
triggered by a fresh process's first commit, plus a graceful-shutdown flush). That first-commit ship is
async, so a crash in the brief window before it completes can still lose that history: the bundle simply
hasn't reached the bucket yet. Losing `DATA_DIR` is then recoverable past
just content: the next boot fetches the bundle, restores `.git` from it, and reconciles any drift
between the bundle's tip and the bucket's current state into one commit
(`reconcile: bucket state at boot`). That reconcile's refreshed bundle is shipped SYNCHRONOUSLY on the boot
thread (an up-to-10-minute streaming PUT) BEFORE the server starts serving, so on a large-history restore
the boot can pause for that upload; the log line "shipping the refreshed DR bundle synchronously before
serving (a slow upload here is not a hang)" marks exactly that window - do not mistake it for a hang.

That reconcile carries one accepted, documented loss class: a proposal **apply** (an approval) that
lands inside the pre-crash cadence window - after the last shipped bundle, before the next one - has
its human proposer/approver **attribution** collapse into that single anonymous, server-identity
reconcile commit on the next boot. Content is never lost (the bucket stays authoritative throughout);
only the commit granularity and that window's human attribution are. The graceful-shutdown flush, the
20-commit/300-second cadence, and the first-commit-ships-immediately rule all bound how wide that
window can get.

The graceful-shutdown flush is best-effort within the shutdown budget: on stop, Plainbase drains any
in-flight cadence ship and then ships one final bundle before closing the object-store transport. A
full `bundle create` + streaming PUT can take up to the same ~10-minute bound as any other ship, so if
your orchestrator's termination grace period (e.g. Kubernetes `terminationGracePeriodSeconds`, a Docker
`stop` timeout) is shorter than the flush needs, the container is killed mid-flush and that final window's
commits fall into the same reconcile-on-next-boot class above (content is still safe in the bucket). Give
the process enough grace to flush if you want the tightest DR window on a large history; the next boot
reconciles cleanly either way.

**The bundle-growth plateau.** Every ship is a FULL `bundle create --all` (never incremental) followed by
a bundle PUT; every restore is a bundle GET followed by a whole-bundle `fetch`. All FOUR of these
size-dependent bundle legs now share a DR-sized ~10-minute bound: the two NETWORK transfers stream to/from a
file (never heap-buffered, so no OOM on a small replacement host) at `BUNDLE_TRANSFER_TIMEOUT_MILLIS`, and
the two GIT calls (`bundle create`, the restore `fetch`) run under a matching `BUNDLE_GIT_TIMEOUT_SECONDS`
per-invocation override rather than the default ~30s hot-path git timeout. That is fine at the roughly-1k-page
contract this design targets, but the bundle only grows (history is never pruned), so past *some*
corpus/history size one of those four legs eventually starts exceeding the ~10-minute bound and every ship or
restore fails there. A ship failure alone is silent in the sense that content keeps serving fine - the only
signal is the escalating WARN-then-ERROR log (`SHIP_FAILURE_ESCALATION_THRESHOLD` consecutive failures)
telling you the DR bundle has gone stale and stayed stale. There is no separate metric or alert for this:
watch that log line if your corpus/history is approaching a size where a full `bundle create`/`fetch` or its
network transfer could plausibly run past ten minutes.

A corrupt or partially-restored local `.git` (a process killed mid-restore, a manually-deleted `.git`
subpath) self-heals the same way ADR-0004 treats every other piece of `DATA_DIR`: git fails loud in a way
that is DEFINITIVELY "no real repo here" (absent, or an unborn/never-fetched HEAD), so
`DATA_DIR/mirror/.git` is renamed aside (`.git.pre-restore-<timestamp>-<uuid>`, dot-prefixed so it never
shows up as content) and the next boot re-restores cleanly from the bucket-shipped bundle - never a
silently-served partial repo. A `.git` that git merely **cannot read** in this environment (most commonly
a dubious-ownership refusal from a DATA_DIR ownership/UID change, or a permissions problem) is a different
case entirely and is deliberately NOT treated the same way: Plainbase cannot tell an unreadable-but-intact
repo apart from a genuinely broken one, so rather than guess and risk deleting a complete history it
aborts the boot with an actionable message naming the likely cause (ownership/permissions) and the fix.
Resolve the underlying ownership/permission issue and restart; nothing about the mirror is touched while
the boot is refusing to start. Those rename-aside husks are bounded: the newest 3 are kept and older
ones are reaped automatically on every bundle-restore boot (any boot that recovers an incomplete or
absent `.git`, so even legacy husks are bounded when the current boot mints none), so repeated
self-heals never accumulate unbounded debris.

**Strict-hydrate boot-loop escape hatch.** On the restore path specifically (a bundle-restore/reconcile is
owed), hydrate runs *strict*: any bucket-fetch or mirror-write failure for a SINGLE key aborts the boot
outright rather than silently leaving the mirror incomplete (FORK-1 design - a silently-incomplete mirror
would let the boot reconcile mis-delete that key from history). If that failure is persistent (a
permanently corrupted or inaccessible bucket object), this is a fail-loud boot loop by design: every
restart hits the same strict-hydrate failure and aborts again. The operator escape is
`rm DATA_DIR/restore-pending` - that clears the FORK-2 sentinel, so the next boot sees a complete `.git`
with no sentinel and takes the ordinary warm, non-strict path instead (skipping that boot's reconcile
commit; the reconcile obligation is simply dropped, not deferred). Content itself is never at risk during
any of this - only the commit-grained history reconcile is gated.

That `restore-pending` hatch only helps once `.git` is already complete. A CORRUPT bucket bundle is a
different failure: the GET succeeds, but the restore fetch itself fails (`fetch.fsckObjects` rejects the
bundle's contents), so `.git` never becomes complete and every restart re-fetches the same corrupt bundle
and re-aborts - the sentinel removal does nothing here. The remedy is to delete or replace the bucket's
`.plainbase/history.bundle` object directly (accepting the loss of the bundled commit-grained history).
Once that object is gone, the next boot's bundle GET comes back 404, which restore() reads as "no bundle" -
fresh-init a new local `.git` and proceed normally, same as any first-ever boot with git enabled. Content
itself was never at risk (the bucket's actual pages are untouched); only the pre-existing commit history
carried in that bundle is lost.

**Per-backend backup guidance.** Backups are operator-owned by decision (Plainbase ships the mechanisms
- the dirty-page journal, the git bundle, export - but never a backup schedule). Pick the recipe for
your store, sized to how much history you want:

- **Local (`storage.backend=local`):** the existing guidance above -
  [back up the main content root](#the-content-tree-is-plain-markdown-on-disk) (`CONTENT_DIR`, or
  `roots.main.path` when configured; plus `DATA_DIR/plainbase.db` if users/tokens/proposals matter).
  Unchanged otherwise.
- **AWS S3 (or any versioning-capable store):** enable bucket
  [versioning](https://docs.aws.amazon.com/AmazonS3/latest/userguide/Versioning.html) plus a
  **noncurrent-version lifecycle rule** for near-free point-in-time restore. **Same-bucket caveat:**
  in-bucket versioning survives an accidental object overwrite, but it survives neither a bucket
  deletion nor an account compromise - real disaster recovery is an **off-bucket** copy (below).
- **Cloudflare R2:** R2 has **no native object versioning** (lifecycle management is GA, but lifecycle
  is not versioning; versioning is on Cloudflare's public roadmap, not shipped). So the R2 recipe is a
  **scheduled external copy**: an `rclone sync` cron to a second bucket or host (or the equivalent with
  any S3 tool). This is the off-bucket copy, so it doubles as real DR.
- **Either provider, for real DR:** periodic `rclone` / `aws s3 sync` snapshots to a second
  bucket/host/cold storage - an off-bucket copy is the only thing that survives bucket deletion or
  account compromise.

**Git-on covers history everywhere.** With `git.enabled=true` you get commit-grained history regardless
of backend - a local `.git` worktree in local mode, the bucket-shipped `history.bundle` in object mode -
on top of whichever content backup you run.

**The three-choices exposure (R17).** Object mode + `git.enabled=false` + no external backup on a
no-versioning provider (i.e. R2 with no `rclone` cron) = **current-state durability only**. That is not
a bug; it is the sum of three explicit operator choices, the same posture a backup-less local deploy has
always had. The git-disabled startup WARN names it exactly: *"object mode with git disabled: no
commit-grained history; content point-in-time recovery is your backup schedule (see the backup
guidance)."* Change any one of the three choices to close the exposure.

What actually recovers if you lose `DATA_DIR` without a backup - and what doesn't - is the next
section.

## Losing `DATA_DIR`: what recovers and what doesn't

`DATA_DIR` can vanish entirely - a lost volume, a wiped container - and Plainbase boots clean against
the surviving authoritative content: in **local** mode that is the main content root (`CONTENT_DIR`,
or `roots.main.path` when configured), and in **object** mode it is the bucket (`CONTENT_DIR` is
ignored; `DATA_DIR/mirror` re-hydrates from the bucket on the next boot). The authoritative content is the source of truth, so most state re-derives; the app database
`DATA_DIR/plainbase.db` also holds *real* state that does not.

**Recovers on the next boot, automatically:**

- the directory itself (created on startup),
- a fresh `plainbase.db`, created and migrated to the current schema,
- a rebuilt, fully populated `search.db`,
- the id of every page that carries `id:` in its frontmatter - those `/p/{id}` permalinks and
  citations keep working,
- `redirect_from` aliases (re-derived from frontmatter),
- with `storage.backend=object` **and** `git.enabled=true`: commit-grained **history**, up to the last
  shipped bundle - `DATA_DIR/mirror/.git` restores from the bucket-shipped `history.bundle`, and the
  boot reconcile captures exactly the divergence between that bundle's tip and the bucket's current
  state as one commit (see [Object-storage backend](#object-storage-backend-storagebackendobject)
  above for the accepted loss class on commits/attribution since the last ship).

**Lost, by stated trade-off:**

- users and password hashes - re-run `plainbase admin setup-token` to bootstrap the first admin
  (the server logs the same hint at boot when no enabled admin exists),
- agent API tokens - re-mint them; token secrets are shown once and never stored,
- sessions, roles, and the audit log,
- pending proposals,
- move-history URL aliases: old URLs from past renames 404 unless re-declared as `redirect_from`,
- ids of pages that never carried a frontmatter id - fresh ids are minted, so old `/p/{id}`
  permalinks and citations to *those* pages break.

**Mitigation, before disaster:**

- run `plainbase adopt --write-ids` so every page's identity lives in the tree itself and survives
  any `DATA_DIR` loss,
- back up the main content root (`CONTENT_DIR`, or `roots.main.path` when configured) always; back up
  `DATA_DIR/plainbase.db` too if users, tokens, or proposals matter.

Two disaster-recovery drills run on every build: `IndexDestroyRebuildDrillTest` (stop → delete
`search.db` → `plainbase reindex`, with `SearchEquivalenceTest` covering the engine level) and
`LostDataDirRecoveryTest` (whole-`DATA_DIR` loss through the serve-boot recovery seams, asserting
which ids survive and that `search.db` repopulates at boot). What those two drills do *not* assert:
`redirect_from` re-derivation and move-alias loss are pinned by the broader `IndexBuilder` suite, not
by these drills; the whole-database row losses (users, tokens, sessions, roles, audit log, proposals)
follow directly from nuking `plainbase.db` and are not separately tested.

### Object-mode DR drills (operator recipes)

One truth governs every object-mode restore: **content enters the mirror ONLY via a boot `hydrate()` or
the background poll** - never via `rescan`. `POST /api/v1/admin/rescan` re-scans the LOCAL mirror only
(it never LISTs or GETs the bucket), so it does NOT surface content you just restored INTO the bucket.
There is no on-demand forced-hydrate admin action today. Restore recipes reflect this:

- **Content restore (required drill).** Copy a backed-up tree into the bucket with any S3 tool
  (`rclone copy backup/ <remote>:<bucket>/<prefix>` or `aws s3 sync backup/ s3://<bucket>/<prefix>/
  --endpoint-url <endpoint>`), then EITHER **restart the server** (boot `hydrate()` pulls the restored
  keys) OR **wait up to `PLAINBASE_S3_POLL_SECONDS`** for the background poll to fetch the changed keys.
  Do NOT expect `rescan` to surface the restored content. _Rehearsed for real: not yet rehearsed_ (an
  owner-run pre-release gate - see the [pre-release checklist](DEVELOPMENT.md#pre-release-checklist);
  record the date + provider here once run).
- **Bundle history restore (required drill, `git.enabled=true` only).** Wipe `DATA_DIR`, boot, and
  verify `git -C <DATA_DIR>/mirror log` shows history up to the last shipped bundle plus exactly one
  `reconcile: bucket state at boot` commit for the divergence (the mechanism documented under
  [Object-storage backend](#object-storage-backend-storagebackendobject) above). _Rehearsed for real:
  not yet rehearsed._
- **Versioned-S3 per-object restore (BONUS tier, documented, never drilled).** On a versioning-enabled
  S3 bucket, `aws s3api list-object-versions` then copy a prior `versionId` over the current key; then
  restart or wait for the poll (same surfacing rule - not `rescan`). Versioned-S3 deployments only; R2
  has no versioning (see [Per-backend backup guidance](#backups) above).

## Operator signals (object mode)

What the object-mode diagnostics mean and what you do about each:

- **`durable_but_unmirrored`.** The write IS durable at the bucket; only the local mirror apply failed.
  The mark is retained and self-heals on the next poll or boot. This is **not** data loss - no action
  needed beyond noting it if it recurs (a persistently failing mirror apply points at local disk).
- **`outcome_unknown`.** An ambiguous network outcome (the PUT may or may not have landed). The mark is
  retained; a boot hydrate-then-reconcile heals it, and a retry is safe (the conditional write makes a
  double-apply a no-op).
- **Retry-honesty signature: a 409 Conflict whose `current_content` equals what you sent.** That means
  your write already landed on a prior attempt - take the returned `current_hash` and move on. (The
  provider-level precondition code, R2's 412 for both create-conflict and stale-CAS, is absorbed by the
  adapter; operators only ever see the app-level 409.)
- **IAM signature: a green boot, then a persistent write 503 (403-mapped) on EVERY save.** The boot LIST
  proved READ, not WRITE - your credentials are missing PUT. Fix the IAM grant, not the app (see
  [Least-privilege IAM](deploy/object-storage.md#least-privilege-iam-stated-honestly)).
- **Q13 outage signature: writes 503 while reads keep serving 200.** The bucket (write authority) is out;
  the app is fine - reads are local by construction. A boot attempted DURING an outage fails fast by
  design (the boot LIST self-check).
- **R16 fail-closed signature: an object-mode boot refusing on a TLS or signature rejection.** That is
  the guard working, not a failure to route around. Never disable certificate validation to "fix" it -
  fix the endpoint or install the host CA trust (see
  [base-image requirements](deploy/object-storage.md#native-binary-base-image-requirements)).

## Adopting an existing repo

`plainbase adopt` gives an existing Markdown tree Plainbase-native page identity - a stable `id:`
in each page's frontmatter that survives moves, renames, and (per the section above) a lost
`DATA_DIR`.

Start read-only, always:

```
plainbase adopt --write-ids --dry-run
```

This is **PREVIEW mode** - it opens a read-only database driver and writes nothing at all,
database included; it prints what *would* be materialized and what *would* be refused (and why),
so you can review before committing to anything.

When the preview looks right, materialize it:

```
plainbase adopt --write-ids
```

This writes an `id:` line into every page's frontmatter (MATERIALIZE). The CLI logs each intended
write (`intent: write id <id> -> <path>`) *before* performing it, so an interrupted run is
reconcilable - re-running `adopt` is idempotent. On network filesystems (NFS/SMB) the CLI also
emits a durability caveat: atomic rename isn't available there, so writes fall back to
copy+delete, which is not crash-atomic.

Bare `plainbase adopt` (no flags) is **RECORD mode** - it writes `id_map` rows to `plainbase.db`
instead of touching your files (and does create/migrate that database). It's the lower-commitment
option, but the trade-off below still applies until a page is actually materialized.

The trade-off: an unmaterialized page is keyed by its **path**, not a durable id - move it outside
Plainbase before adopting, and it gets a fresh id on adoption (the same trade-off as
[Losing DATA_DIR](#losing-data_dir-what-recovers-and-what-doesnt) above, from the disaster-recovery
angle). `--write-ids` is the fix: once a page carries its own `id:`, its identity lives in the
tree, not in derived state.

Exact usage: `plainbase adopt [--write-ids [--dry-run]]` - no other flag combination is accepted.

## Performance & the startup gate

Three classes of number back Plainbase's performance story - keep them straight, because only some
are checked-in contracts.

**Cited budgets (asserted on every build, checked-in thresholds):**

- Page-render p95 < 300 ms, measured at index time over a 1,000-page + 20-large-page corpus
  (`RenderCorpusPerfTest`).
- Full reindex (page pass + engine rebuild) < 60 s over the 1,000-page corpus
  (`Fts5CorpusPerfTest`).
- FTS5 search p95 < 200 ms, warm, over the same corpus (`Fts5CorpusPerfTest`).
- Native-startup CI regression tripwire: 2000 ms (`ci.yml`, the `native-gate` job).

**Measured-during-C2 observations** (reproduce them yourself rather than trusting these numbers as
they age):

- Native cold-start (exec → first `200 /healthz`, against an *empty* content dir): ~467 ms local
  median, ~933 ms median on GitHub's shared CI runners (one run measured 1004 ms) - CI runners run
  roughly 2x slower than local/prod hardware, which is why the CI gate above is a 2000 ms tripwire,
  not the real target.
- Reproduce it yourself:

  ```
  scripts/ci/native-startup-bound.sh <path-to-plainbase-launcher> [port=8082] [budget-ms=1000]
  ```

  This boots the launcher 5 times against a fresh, empty `DATA_DIR` + `CONTENT_DIR` per run and
  gates on the median. The strict **< 1 s target stays local/prod** (the script's own default
  budget); CI merely tripwires regressions at roughly 2x its own median.

**Why the split:** booting against an empty content dir isolates process-exec + class-init + Koin +
SQLDelight open/migrate + the CIO bind + the first request - *not* corpus indexing. (Booting the
~41-page demo corpus instead measures ~1005 ms median - corpus-index-dominated, not startup; that
time is already budgeted by the render/reindex thresholds above, so it doesn't belong in the
startup gate.)

**Cloud startup budgets (object mode).** A separate drill covers object-mode boot: warm under 3 s, cold
under 10 s at the ~1k-page contract (a strict bound - a median exactly at 3 s / 10 s fails, matching the
local tripwire), checked by `scripts/ops/cloud-startup-budget.sh` against a real, prefix-scoped
~1k-corpus bucket. Two asymmetries with the local tripwire above matter:

- **It is NOT in CI** (CI has no bucket and no credentials), so unlike the local backend there is **no
  between-release regression guard** for cloud budgets. The obligation lives in the
  [pre-release checklist](DEVELOPMENT.md#pre-release-checklist) instead.
- **The warm number is a rebuild+serve budget, not a cold-JVM-start figure.** Every boot runs a full
  `IndexBuilder.rebuild()` over the hydrated mirror before `/healthz` serves 200, so the warm number is
  exec -> first 200 `/healthz` INCLUDING that rebuild. At ~1k pages the rebuild can dominate - which is
  why the drill is measured at the ~1k contract, not against a trivial corpus.

Measured medians (provider, platform, corpus size, date): _not yet rehearsed_ - an owner-run pre-release
gate; record them here once the drill runs.

### Git-write stall bound

Every git invocation Plainbase makes funnels through one executor with a bounded wait: **a 30 s
timeout, plus bounded per-stream (stdin/stdout/stderr) drain grace** after a force-kill. There is
no single exact total to quote, because one save issues roughly a dozen such invocations in
sequence (capturing HEAD, seeding a temp index, hashing the blob, updating it, writing the tree,
creating the commit, updating the ref, and a couple more) - so a wedged repo (a stuck filesystem, a
hung `git` hook shimmed in from outside Plainbase's pinned config, etc.) can stall a single save
for **a small multiple of the per-invocation bound**, not one fixed number of seconds. There's no
circuit breaker today - a trip-after-N-failures breaker is a v0.1.x candidate.

## Known limitations (v0.1)

Recorded cuts - conscious v0.1 trade-offs, not gaps discovered later and not TODOs:

- **Audit-log read asymmetry.** Denied *reads* are deliberately not audited (`checkRead`'s KDoc
  says so plainly: "Not audited") - only mutating denials (edit/create/manage/approve) record a
  decision row. A read-probing agent leaves no trail in the audit log. Don't treat the audit log as
  a complete access log; it's a complete *mutation* log.
- **Sub-1280px metadata rail cut.** The frontmatter edit rail is hidden below Tailwind's `xl`
  breakpoint (≈1280 px viewport width) - below that width the frontmatter form isn't editable in
  the UI, though the raw markdown buffer still is. A conscious v0.1 cut, not a bug.

**Maintainer watch-items** (correct today, worth re-checking if the surrounding code moves):

- The editor's `bufferRef`/`commitBuffer` invariant - *every* buffer write must go through
  `commitBuffer`, which updates the ref in the same tick it schedules the `setState`, so the ref
  never lags the rendered buffer. A future edit that writes buffer state directly would silently
  reintroduce a stale-read race.
- The frontend token-discipline gate's known false negative: the fixed `COLOR_PROPERTIES` list
  only catches a **named color** (`red`, `white`, …) in a **listed** property. A named color in an
  *unlisted* property - a `box-shadow`, a gradient stop - slips through undetected. Hex literals and
  color functions (`rgb()`, `hsl()`, `oklch()`, …) are caught position-independently regardless of
  property, so this gap is narrower than it sounds, but it's real.
