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

Plainbase watches `CONTENT_DIR` and re-indexes when files change, so an edit made outside Plainbase
(in your editor, via `git checkout`, etc.) becomes searchable automatically. The end-to-end latency
is **debounce (0.5 s) + a full index rebuild + the search sync**.

### Platform note - the 5-second promise binds Linux

- **Linux** (the deployment platform) uses `inotify`: file-change events arrive in milliseconds. The
  "searchable within 5 seconds" promise is an automated test on Linux.
- **macOS** dev boxes use the JDK's `PollingWatchService` (multi-second poll interval; the
  `com.sun.nio.file` sensitivity modifiers are not reliably effective on JDK 21+). So **the 5-second
  promise does not bind macOS.** The practical answer on a Mac is a manual **rescan**
  (`POST /api/v1/admin/rescan`): it re-scans `CONTENT_DIR`, picks up the just-edited file, and
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
in the default **local** mode it is `CONTENT_DIR` (see
[the content tree is plain Markdown on disk](#the-content-tree-is-plain-markdown-on-disk) above); in
**object** mode it is the S3-compatible **bucket**, and `CONTENT_DIR` is ignored entirely (the
Object-storage subsection below covers that case). Back up `DATA_DIR/plainbase.db` too in EITHER mode
if users, agent tokens, proposals, roles, or the audit log matter to you - it's the one piece of
`DATA_DIR` holding *real*, non-derived state. `DATA_DIR/search.db` needs no backup at all: it's fully
[derived state](#searchdb-is-derived-state), rebuildable from the authoritative content at any time
(and in object mode `DATA_DIR/mirror` / `DATA_DIR/mirror-state` are likewise derived and need none).

### Object-storage backend (`storage.backend=object`)

In object mode the S3-compatible **bucket** is the canonical content store - back IT up, the same way
you'd back up `CONTENT_DIR` locally. `DATA_DIR/mirror` and `DATA_DIR/mirror-state` are derived, deletable
cache (delete them and they self-heal from the bucket on the next boot), so they need no backup; `plainbase.db`
still holds real state and still wants one.

Because git history over the object backend is not available yet, an object-mode deployment has **no
commit-grained history** - your content's point-in-time recovery is entirely your bucket's backup
schedule (this is exactly what the `object mode with git disabled` startup WARN is pointing you at). Pick
one, sized to how much history you want:

- **R2:** enable [object versioning](https://developers.cloudflare.com/r2/buckets/object-versioning/)
  with a retention lifecycle rule, or run an `rclone sync` on a cron to a second bucket/host.
- **AWS S3:** enable bucket [versioning](https://docs.aws.amazon.com/AmazonS3/latest/userguide/Versioning.html)
  plus a lifecycle policy (and optionally cross-region replication) for point-in-time restore.
- **Either:** periodic `rclone`/`aws s3 sync` snapshots to cold storage.

When git-over-the-mirror lands in a later release the WARN goes away and commit history returns.

What actually recovers if you lose `DATA_DIR` without a backup - and what doesn't - is the next
section.

## Losing `DATA_DIR`: what recovers and what doesn't

`DATA_DIR` can vanish entirely - a lost volume, a wiped container - and Plainbase boots clean against
the surviving authoritative content: in **local** mode that is `CONTENT_DIR`, and in **object** mode it
is the bucket (`CONTENT_DIR` is ignored; `DATA_DIR/mirror` re-hydrates from the bucket on the next
boot). The authoritative content is the source of truth, so most state re-derives; the app database
`DATA_DIR/plainbase.db` also holds *real* state that does not.

**Recovers on the next boot, automatically:**

- the directory itself (created on startup),
- a fresh `plainbase.db`, created and migrated to the current schema,
- a rebuilt, fully populated `search.db`,
- the id of every page that carries `id:` in its frontmatter - those `/p/{id}` permalinks and
  citations keep working,
- `redirect_from` aliases (re-derived from frontmatter).

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
- back up `CONTENT_DIR` always; back up `DATA_DIR/plainbase.db` too if users, tokens, or proposals
  matter.

Two disaster-recovery drills run on every build: `IndexDestroyRebuildDrillTest` (stop → delete
`search.db` → `plainbase reindex`, with `SearchEquivalenceTest` covering the engine level) and
`LostDataDirRecoveryTest` (whole-`DATA_DIR` loss through the serve-boot recovery seams, asserting
which ids survive and that `search.db` repopulates at boot). What those two drills do *not* assert:
`redirect_from` re-derivation and move-alias loss are pinned by the broader `IndexBuilder` suite, not
by these drills; the whole-database row losses (users, tokens, sessions, roles, audit log, proposals)
follow directly from nuking `plainbase.db` and are not separately tested.

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
