# Operating Plainbase

Operator notes for running Plainbase in production and on a dev box. This document covers search
freshness, the manual-reindex paths, the filesystem-native virtue, adopting an existing repo,
backups and disaster recovery, performance budgets and the startup gate, and the recorded v0.1
limitations.

For **single-sign-on behind a reverse proxy** (`auth.mode=proxy`), see
[`deploy/reverse-proxy-sso.md`](deploy/reverse-proxy-sso.md) and the standalone Caddy + oauth2-proxy
reference stack under `deploy/proxy/`.

## Bind and proxy address safety

The bind guard and proxy trust checks accept numeric address literals only. IPv4 must use four ASCII
decimal octets without abbreviation or leading-zero ambiguity; IPv4 zones are rejected, while IPv6 may carry
a zone, which is removed before classification. Host ports are validated as ASCII decimal `0`–`65535`, with bracketed IPv6
syntax for a port-bearing value. Case-insensitive, unbracketed `localhost` and `ip6-localhost` remain supported aliases for
loopback bind/remote checks, but bracketed aliases and arbitrary hostnames are not loopback evidence.

`PLAINBASE_TRUSTED_PROXY` must contain valid numeric CIDRs. Plainbase refuses startup with an error
naming that variable when an entry is malformed. The guard performs no hostname lookup, so a DNS name
cannot turn a bind or socket peer into a trusted address; put a proxy's numeric CIDR in configuration.

## Upgrading the binary: stop the old one FIRST (schema v17)

Per-root page identity ([ADR-0012](decisions/0012-per-root-page-identity.md)) makes a page's identity the
pair `(root, id)` rather than the id alone, which rewrites `id_map` to `UNIQUE(id, root)` at schema v17.
A pre-v17 binary does not understand the new constraint and runs its
global cross-root `unbindStale`/`unretire` against it, silently wiping another root's rows. Shut the
running server down BEFORE upgrading the binary. A v17-or-later binary REFUSES to open a still-NEWER
DB, but it cannot stop an OLDER binary that is already running, and there is no downgrade.

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

### Embedded Mermaid diagrams

Markdown pages may include a lowercase `mermaid` fence. Plainbase renders supported diagrams in the
browser while keeping the Markdown source authoritative and readable when a diagram is malformed or
unsupported:

~~~markdown
```mermaid
flowchart LR
  author[Markdown file] --> page[Plainbase page]
```
~~~

Each diagram is limited to 50,000 source characters. Mermaid also enforces a 500-edge limit for
flowcharts and agent-flow diagrams; other diagram types do not share that edge limit. Rejected
diagrams remain visible as source. Rendering runs in the browser, so complex diagrams can briefly
delay interaction even within these limits.

### Standalone Mermaid files

Lowercase `.mmd` files are indexed as read-only diagram leaves. The tree and folder listings link to
`/browse/{root}/{path}`; that route serves the SPA shell, which fetches the guarded raw source from
`/assets/{root}/{path}` and renders it with the same Mermaid limits as embedded fences. The source
download remains the file on disk, including its original bytes. Missing, unavailable, or unauthorized
source reads are shown as the normal error state; a valid browser address can still load the shell.

Standalone Mermaid document creation and proposals are intentionally unsupported. Edit or add the `.mmd`
file with filesystem tools, then let the normal watcher or a manual rescan update navigation.

### Callouts and task lists

Use one of the five fixed callout markers as the first line of a quoted block. The remaining lines are
ordinary Markdown, so links, lists, headings, code, and nested callouts keep their normal behavior:

~~~markdown
> [!NOTE]
> This is a visible note.
>
> - It can contain a list.

- [ ] Open task
  - [x] Nested completed task
~~~

Keep a quoted blank line (`>`) after the marker when a body line is `===` or `---`, so it remains body text instead of becoming a heading.

`NOTE`, `TIP`, `IMPORTANT`, `WARNING`, and `CAUTION` are recognized in uppercase only. Task checkboxes
are disabled display controls; edit `[ ]`, `[x]`, or `[X]` in the Markdown file to change a task. The
same rendering is used in the editor preview. A `[!NOTE]` marker that is also a defined link-reference label,
or a marker with same-line body text, stays an ordinary quote.

To add a callout, open the page editor and use the `Callout type` chooser beside `Insert callout`. The
chooser starts at `NOTE`; choosing a type does not insert anything until the action is clicked. With a
selection, the command wraps every touched source line (not only the selected characters); with no selection,
it inserts after the current nonblank line or at the current blank line. The generated `Callout text`
placeholder is selected for replacement. The block remains ordinary Markdown: preview it, then choose
`Save` explicitly to write it. Conflicts and other save failures keep the editor buffer for recovery.

## Search freshness: editing files outside Plainbase

Plainbase watches **every root that is available at startup** (`CONTENT_DIR`, or each `roots.*.path`
when a `roots {}` block is configured) and re-indexes when files change, so an edit made outside
Plainbase (in your editor, via `git checkout`, etc.) becomes searchable automatically. The end-to-end
latency is **debounce (0.5 s) + a full index rebuild + the search sync**.

A root whose path is missing at startup gets no watcher (there is nothing to watch) and serves 503
until you restore it and restart - see
[Multiple roots: what happens when one is not there](#multiple-roots-what-happens-when-one-is-not-there).

### Platform note - the 5-second promise binds Linux

Native Windows and direct Windows JVM operation are intentionally deferred. On a Windows host, the
supported path is the `linux-x64` binary under WSL2, with both `CONTENT_DIR` and `DATA_DIR` inside the
distribution's Linux filesystem (for example, `/home/<user>/plainbase/content` and
`/home/<user>/plainbase/data`). Do not place either directory under `/mnt/c`, `/mnt/d`, or another
Windows-mounted drive: the paths look Linux-like, but the underlying Windows filesystem retains
different permission, symlink, file-identity, watcher, and performance semantics. For `DATA_DIR` the
stakes are data integrity, not just semantics: Plainbase relies on cross-process advisory file locks
(`DataDirLock`), SQLite's locking, and atomic rename plus directory fsync, none of which is reliable
over the `/mnt/*` 9P translation layer. On the distro filesystem, `inotify` works normally and the
5-second promise below holds.

For Docker Desktop, use the same boundary: run Compose from a checkout inside the WSL distribution
and bind-mount content from that Linux filesystem. A Docker-managed named volume is appropriate for
`DATA_DIR`; bind-mounting `C:\...` into a Linux container does not turn the underlying filesystem into
a native Linux filesystem.

- **Linux** (the deployment platform) uses `inotify`: file-change events arrive in milliseconds. The
  "searchable within 5 seconds" promise is an automated test on Linux.
- **macOS** dev boxes use the JDK's `PollingWatchService` (multi-second poll interval; the
  `com.sun.nio.file` sensitivity modifiers are not reliably effective on JDK 21+). So **the 5-second
  promise does not bind macOS.** The practical answer on a Mac is a manual **rescan**
  (`POST /api/v1/admin/rescan`): it re-scans every available root (the index pass is one whole-corpus
  pass, never a single tree), picks up the just-edited file, and diff-syncs search. **Reindex (below) will NOT surface a fresh disk edit** - it rebuilds the search
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

`pages` counts published pages accepted as input to this rebuild. Pages currently retired and unbound are
excluded, even if an older published snapshot still contains them. The count can therefore be smaller than the
snapshot, and it does not include additional unretired rows carried forward from the search engine. Reindex does
not change the published page snapshot or its checkpoints.

### `plainbase reindex` - the OFFLINE/ops path

For when the server is **down**, or for a scripted operational reindex:

```
CONTENT_DIR=./content DATA_DIR=./data plainbase reindex
# reindex: rebuilt the search index for 42 page(s) under /abs/path/to/content
```

The CLI's fresh complete page pass binds the scanned pages before its explicit swap. On a fresh `DATA_DIR`, this
matches the accepted input and resulting engine count. The running API rebuild instead filters the existing holder
after current retirement authority is read; its accepted count can therefore differ from the CLI's fresh scan when
the holder includes a newly retired page. Both paths may carry additional unretired engine-only rows outside the
reported input count.

The CLI requires every configured root to be available and the page pass to be complete; otherwise it refuses the
search generation swap.

Like `serve`, the offline CLIs (`reindex`, `adopt`) read `DATA_DIR/plainbase.conf` (env still wins), so a
file-configured `storage.backend=object` makes them operate on the bucket mirror - not the local
content root - matching the running server for the same `DATA_DIR`. A file-configured `roots {}`
block likewise makes them operate on `roots.docs.path`, not an ignored `CONTENT_DIR`.

**Do not run `plainbase reindex` against a live server** - use the endpoint instead. The CLI and a
running server are separate processes with separate write monitors; while SQLite's own locking
prevents *corruption*, it does not prevent the CLI silently publishing an *older* generation over the
server's newer one (a freshness regression). To make this safe, both the server
and the CLI take an advisory lock on `DATA_DIR/plainbase.lock`: the server holds it for its lifetime,
and the CLI **refuses to run while a server holds it**, exiting with:

```
reindex: a Plainbase server is holding /abs/path/to/data - stop it, or use POST /api/v1/admin/reindex on the running server
```

Exit codes: `0` success, `1` runtime failure (including the lock refusal), `2` usage error.

**If an operation fails with `database is locked`:** app DB transactions use `BEGIN IMMEDIATE`, including read-only
transaction bodies, so they take SQLite's reserved write lock when they open. Ordinary bare `SELECT`s are not
wrapped in those transactions and can coexist with a reserved lock, while overlapping read/write lifetimes can still
delay a writer's commit. If a reindex or large publication is in flight, wait for it to finish and retry after
contention clears.

The app DB is not WAL; the derived `search.db` uses WAL and has a separate busy timeout.

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

## Multiple roots: what happens when one is not there

The primary root is `docs`, but it follows the same unavailable-root path as every extra. If its directory
is missing or unreadable when `serve` starts, the server logs this warning and starts with that root marked
unavailable:

```
root 'docs' is not available at /srv/docs: it will serve 503 until the path is restored and the server restarted (its pages, aliases and checkpoints are left untouched)
```

Root-dependent API, agent, asset, and write surfaces answer 503 until the path is restored and the server
is restarted. Browser navigation is the deliberate exception: it serves the SPA shell with 200 so the
client can render its own outage UI. A missing `docs` path is not the same failure as a config declaring the reserved root name
`main`: the latter refuses during config loading, before a path is checked. Offline commands have their
own fail-closed path checks; `serve` uses the unavailable-root behavior above.

Everything else in this section applies to **all** configured roots.

A configured root that is missing at boot, or whose directory vanishes while the server runs, is marked
**unavailable**. Two things matter operationally.

**1. Root-dependent API, agent, asset, and write surfaces answer 503, never 404 - and that distinction is for your agents.**

| answer | what it means to an agent |
|---|---|
| `404 page_not_found` | the page is unavailable in the requested visible scope. A root pin narrows that scope; this does not prove physical deletion. Keep citations/provenance until the source state is verified. |
| `503 root_unavailable` (+ `Retry-After: 300`) | the root's availability is unresolved. **KEEP citations and provenance**, honor the delay, and retry after the operator restores the root and restarts the server. |
| `503 absence_unverified` (+ `Retry-After: 30`) | the root is healthy but the page's absence is not yet proven. **KEEP citations and provenance**, honor the delay, and retry as observations converge; do not restart the root. |

Nothing is ever written when root rejection happens before the operation is entered, so retrying that response is safe.
Shutdown admission is a separate 503, `server_shutting_down`; see the [agent error table](connect-your-agent.md#4-roots-what-a-page-lives-under-and-what-its-errors-mean).
It carries no `Retry-After` promise. A root that is not serving also never reports
its pages as deleted, never reports a conflict against them, and never quietly succeeds a write into
them.

**2. Unavailability grants no new absence authority.** Its pages are carried forward in the index, and its
`id_map`, `url_alias`, `page_checkpoint` and `dirty_page` rows are left untouched. An outage cannot create a
new retirement, although a retirement already durably committed before the outage may still be cleaned from
derived search state. Its proposals stay decidable too: an APPLYING row stays APPLYING, a CONFLICTED row is
never terminally failed, and an approve or rebase against it answers 503 rather than rewriting the row.

### Checking, and recovering

`GET /healthz` reports every configured root:

```json
{
  "status": "ok",
  "version": "0.1.0",
  "roots": [
    { "root": "docs",    "available": true,  "reason": null },
    { "root": "archive", "available": false, "reason": "vanished" }
  ]
}
```

The top-level `status` stays `"ok"`: this is a LIVENESS probe, and a vanished extra root must not flip
a k8s probe into a restart loop - a restart cannot remount a disk, and killing a server that is still
serving every other root only makes the outage worse. Alert on the `roots` array instead.

`reason` is one of `missing_at_boot`, `vanished`, or `watcher_failed`. `GET /api/v1/tree` carries the
same bit as `"available": false` on the root's entry (with an empty subtree - never a stale listing).

**Recovery is: restore the directory, then RESTART the server.** Unavailability is sticky on purpose -
a vanished root's scan and identity state cannot be trusted afterwards, so the server will not silently
re-adopt a directory that reappeared.

## URL surfaces and the top-level root grammar

The server route table treats the first segment as a root name on every root-scoped surface. Do not add a
`/docs` prefix before an extra root: `docs` is the primary root's name, while `extra` owns `/extra/...`.
The following table is the operator-facing status contract for the route families that are easiest to
misconfigure in a proxy or health check:

| surface | request shape | answer |
|---|---|---|
| Browser content (HTML/default) | `/{unknown-root}` or `/{unknown-root}/{path}` | 404 with the SPA shell body. An unknown first segment is not a path in `docs`; opted-in Markdown answers JSON 404 without a content lookup (see [HTTP API reference](http-api.md)). |
| Browser content (HTML/default) | `/{root}` or `/{root}/{path}` for a registered root | 200 with the SPA shell, except a live alias redirects 301 within that root. Opted-in Markdown reads guarded source directly, aliases answer 200, and auth/lookup/outage failures answer structured JSON, including 503 (see [HTTP API reference](http-api.md)). |
| Asset files | `/assets` | 400 `invalid_path`, because an asset path is required. `/assets/{unknown-root}/...` and `/assets/{registered-root}` answer 404; a registered root plus a path is required, such as `/assets/docs/infra/assets/diagram.svg`. |
| Embedded bundle | `/assets/index-<hash>.js` or the corresponding CSS path | 200 from the embedded bundle before the root split and before the content-asset read gate. The bundle check is root-blind. |
| Markdown file-path lookup | `/browse/{root}/{path}.md` | 400 `invalid_path` for a missing or bare root, 404 for an unknown root or missing page, and 302 to the current Markdown page URL for a readable registered-root path. The redirect is read-gated. |
| Standalone Mermaid shell | `/browse/{root}/{path}.mmd` | 200 with the SPA shell for a valid rooted diagram address, including a missing source; 404 shell for malformed or unknown diagram addresses. The source read remains guarded at `/assets/{root}/{path}`. |
| Agent page lookup | `/api/v1/pages/by-path` | 400 `invalid_path`, because a page path is required. A path whose first segment is not a registered root, or a bare registered root, is 404 `page_not_found`, never a lookup under `docs`; `/api/v1/pages/by-path/docs/guides/deploy-guide` is the rooted form. |
| Permalink | `/p/<id>` or `/p/<root>/<id>` | 302 when the page is found, 200 for a live path-collision loser, 300 for an ambiguous bare id, 404 for an unknown id, 400 for a malformed shape, 410 for a retired id, and 503 when a live page's root is unavailable. |

Once shutdown admission closes, the global `503 server_shutting_down` response can supersede the route-specific answers in this table.

The app route table owns these paths and their query strings. A reverse proxy must forward them unchanged,
must not rewrite `/assets/<bundle>` to the SPA shell, and must not add a second `/docs` prefix. The reference
Caddyfile forwards the application without a path rewrite.

### How fast is it detected?

**Within 5 seconds, whether or not anyone is using the root.** Each available root's watcher probes its own
directory on a 5-second interval (and treats the death of the root's own watch key as the same signal), so a
silent unmount, a dropped NFS/SMB share, or a `mv` of the directory is caught without anything having to
touch it. That matters because a rename or an unmount raises no file event of its own: the pages inside were
never modified, so there is nothing for an event-driven detector to see.

The other detectors remain, and they are faster when the root is in use: any write, any rebuild, and an
explicit `POST /api/v1/admin/rescan` all probe the root and mark it on the spot. There is no need to cron a
rescan for this.

Once marked, nothing serves that root's content - that is the invariant. The exposure is therefore bounded to
reads issued in the seconds *before* detection. Those reads do not themselves retire anything: the probes prevent a
missing root from becoming deletion evidence, while search reconciliation may still finish previously committed
retirements using durable authority.

### Reindexing while a root is unavailable

`POST /api/v1/admin/reindex` retains existing unretired search rows even when their root is unavailable, detached,
or absent from the published snapshot. It removes identities currently retired and unbound, including retirements
whose earlier search update failed. Retired pages still present in an older or carried snapshot are excluded from
search input, so that snapshot cannot reintroduce them. Reindex leaves the published snapshot and `page_checkpoint`
rows unchanged. Retaining engine rows does not make an unavailable root's content available.

Full search reconciliation reads the current retired-and-unbound identities accumulated over the app database's
lifetime. A generation rebuild also stages those identities as exclusions. This work can grow even when the live
tree is small; bounded insertion batches do not bound total work. Targeted page updates use an indexed lookup for
one rooted identity.

These operations share the serialized index builder. App DB contention can delay retirement reads; a retirement read
can hold a shared lock while consuming result rows, delaying a concurrent binding commit. If overlap exceeds the
configured three-second busy timeout, either read or commit may fail `SQLITE_BUSY`. This is conditional, not every
long read. A failed retirement read makes no search changes for that operation.

A targeted save whose bytes landed but whose search update failed follows the existing dirty-page recovery path. If
the identity remains retired and unbound, reconciliation can continue failing and logging across restarts; a retained
dirty row has no automatic expiry. A legitimate same-path reclaim permits indexing again. A dirty row already
removed by an accepted retirement proof is not recreated by the search failure.

### Adding or removing a root: a config edit plus a restart

`plainbase root add`/`remove`
(see [Configuration: the CLI and the two files](configuration.md#the-cli-and-the-two-files)) writes
`DATA_DIR/roots.conf`; nothing changes for a running server until you restart it - the CLI has no
runtime API to talk to a live process, and the server does not hot-reload topology.

`root remove <name>` does not touch the root's content or its database rows. Its pages keep their
`id_map`, `url_alias` and `page_checkpoint` rows exactly as they were; the rows just become
**detached**, because the name no longer names a configured root. `root add` of the **same** name
later revives them, and under per-root identity (C5) that revival is permalink-SAFE: each page keeps
its own `id:` and answers the same rooted `/p/{name}/{id}` it did before. Two things are still worth
naming:

- **Re-adding under a DIFFERENT name is not a free rename.** Permalinks and agent citations are rooted
  (`/p/{root}/{id}`), so the root's name is baked into every one of them. Bring the same directory back
  under a new name and the pages answer at `/p/{newname}/{id}` while the old `/p/{oldname}/{id}` URLs
  404 - the `id:` values are untouched; it is the ROOT segment that moved.
- **Rank only reorders source precedence.** `root add` APPENDS to `roots.conf`, so a re-added root
  ranks last - but rank decides SOURCE PRECEDENCE and the order a bare `/p/{id}` lists its candidate
  roots (see
  [Configuration: the order of the block](configuration.md#the-order-of-the-block-decides-source-precedence-not-permalinks)),
  NOT who "keeps" a shared id. Two roots holding the same frontmatter `id:` BOTH keep it, each at its
  own rooted permalink. `root remove` prints these consequences when you run it.

A same-name remove/re-add leaves every page with the same id and the same rooted permalink. **Renaming
a root, though, is a permalink-affecting operation** - prefer fixing the topology in one edit over a
remove now and an add-under-a-new-name later.

**If the removed root held every page binding in `DATA_DIR`, the next boot refuses to serve.** This is
the 100%-detached guard (ADR-0011 D15): a nonempty `id_map` whose roots are entirely disjoint from the
configured names looks like the wrong `DATA_DIR` or a wholesale-rewritten `roots.conf`, so `serve` fails
closed rather than silently minting fresh identities for a corpus it can no longer place. Add the root
back (or fix `roots.conf`), then restart.

The rewrite of `roots.conf` is atomic (a temp file, then an atomic rename), so a `root add`/`remove`
interrupted halfway leaves the previous file untouched. On a `DATA_DIR` whose filesystem has no atomic
rename - an NFS/SMB mount - the CLI falls back to a copy, warns that it did, and copies the previous
file to `roots.conf.bak` first. If you ever find that `.bak` sitting there, a write was interrupted
mid-copy: it is the last config that booted, and `mv roots.conf.bak roots.conf` restores it.

A last-root `remove` also refuses to unlink `roots.conf` when any `roots.conf.bak` entry is present, including a
directory or symlink, and leaves both entries untouched. The command checks that sibling without following symlinks;
the startup warning and damaged-file recovery path still recognize only a regular backup file, so a directory or
dangling symlink may be silent at startup. Inspect such an entry deliberately: restore a known-good regular backup
with `mv roots.conf.bak roots.conf`, or resolve/remove the entry intentionally before retrying the command. The
refusal ends the command with exit `1` and no success or detached-row consequence output.

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

**Back up every store that holds authoritative content - and on a multi-root install that is more than one
directory.** Which stores those are depends on `storage.backend`:

- **local** (the default): **every configured root's directory is authoritative content, and every one of
  them needs backing up.** With no `roots {}` block that is the single `CONTENT_DIR` tree (see
  [the content tree is plain Markdown on disk](#the-content-tree-is-plain-markdown-on-disk) above). With a
  `roots {}` block it is `roots.docs.path` **plus each extra root's `path`** - `roots.handbook.path`,
  `roots.runbooks.path`, and so on. Roots are disjoint directories with no shared storage and nothing else
  in the deployment holds a copy of them, so backing up `docs` alone silently leaves the rest of your corpus
  unprotected.
- **object**: the S3-compatible **bucket** is the authority - back *it* up, and `CONTENT_DIR` is ignored
  entirely (the Object-storage subsection below covers that case). Object mode is single-root today: a
  `roots {}` block cannot be combined with `storage.backend=object` (ADR-0011 D10).

Back up `DATA_DIR/plainbase.db` too, in EITHER mode: it holds durable identity bindings, retirement history and
aliases as well as users, agent tokens, proposals, roles, sessions and the audit log - the one piece of `DATA_DIR`
holding *real*, non-derived state. `DATA_DIR/search.db`
needs no backup at all: it's fully [derived state](#searchdb-is-derived-state), rebuildable from the
authoritative content at any time with `plainbase reindex` (and in object mode `DATA_DIR/mirror` /
`DATA_DIR/mirror-state` are likewise derived and need none).

### Object-storage backend (`storage.backend=object`)

In object mode the S3-compatible **bucket** is the canonical content store - back IT up, the same way
you'd back up the content root locally. `DATA_DIR/mirror` and `DATA_DIR/mirror-state` are derived, deletable
cache (delete them and they self-heal from the bucket on the next boot), so they need no backup; `plainbase.db`
still holds durable identity, retirement, security and workflow state and still wants one.

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
thread (bundle creation followed by a streaming PUT with a 10-minute request timeout) BEFORE the server starts serving,
so on a large-history restore
the boot can pause for that upload; the log line "shipping the refreshed DR bundle synchronously before
serving (a slow upload here is not a hang)" marks exactly that window - do not mistake it for a hang.

That reconcile carries one accepted, documented loss class: a proposal **apply** (an approval) that
lands inside the pre-crash cadence window - after the last shipped bundle, before the next one - has
its human proposer/approver **attribution** collapse into that single anonymous, server-identity
reconcile commit on the next boot. Content is never lost (the bucket stays authoritative throughout);
only the commit granularity and that window's human attribution are. The graceful-shutdown flush, the
20-commit/300-second cadence, and the first-commit-ships-immediately rule all bound how wide that
window can get.

The graceful-shutdown flush is attempted in the ordered shutdown path: Plainbase drains any in-flight cadence
ship and then attempts one final bundle before closing the object-store transport. Final shipping runs `bundle create`
and the streaming PUT sequentially, with a 600-second Git command deadline and a 600-second HTTP request timeout.
Their configured allowances total 20 minutes; the DR close forecast also includes two 30-second drain periods, for
21 minutes. This forecast is not a completion bound: admitted work and Git completion may outlive it, and lock waits,
file hashing and the other shutdown terms still need workload-specific allowance. If your orchestrator's termination
grace period (for example Kubernetes `terminationGracePeriodSeconds` or a Docker `stop` timeout) expires first, the
process can be killed mid-flush and that final window's commits fall into the same reconcile-on-next-boot class above
(content is still safe in the bucket). The process does not promise to return before that supervisor boundary; size
the supervisor from measured workload terms and retain forced termination as the final boundary.

**The bundle-growth plateau.** Every ship is a FULL `bundle create --all` (never incremental) followed by
a bundle PUT; every restore is a bundle GET followed by a whole-bundle `fetch`. Each of these FOUR
size-dependent bundle legs has its own deadline/request timeout: the two NETWORK transfers stream to/from a
file (never heap-buffered, so no OOM on a small replacement host) at `BUNDLE_TRANSFER_TIMEOUT_MILLIS`, and
the two GIT calls (`bundle create`, the restore `fetch`) run under their own `BUNDLE_GIT_TIMEOUT_SECONDS`
per-invocation override rather than the default ~30s hot-path git timeout. That is fine at the roughly-1k-page
contract this design targets, but the bundle only grows (history is never pruned), so past *some*
corpus/history size one of those four legs eventually starts exceeding its own 10-minute deadline/request timeout
and every ship or restore fails there. Deadline expiry does not promise prompt Git completion. A ship failure alone
is silent in the sense that content keeps serving fine - the only signal is the escalating WARN-then-ERROR log
(`SHIP_FAILURE_ESCALATION_THRESHOLD` consecutive failures) telling you the DR bundle has gone stale and stayed stale.
There is no separate metric or alert for this: watch that log line if your corpus/history is approaching a size where
a full `bundle create`/`fetch` or its network transfer could plausibly run past ten minutes.

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
  [back up EVERY configured root's directory](#backups) (`CONTENT_DIR` with no `roots {}` block; otherwise
  `roots.docs.path` **and every extra root's `path`** - each root is an independent content authority and
  nothing else in the deployment holds a copy of it; plus `DATA_DIR/plainbase.db` for durable identity,
  retirement, security and workflow state). Unchanged otherwise.
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
the surviving authoritative content: in **local** mode that is **every configured root's directory**
(`CONTENT_DIR` with no `roots {}` block; otherwise `roots.docs.path` and each extra root's `path`), and
in **object** mode it is the bucket (`CONTENT_DIR` is ignored; `DATA_DIR/mirror` re-hydrates from the
bucket on the next boot). The authoritative content is the source of truth, so most state re-derives; the app database
`DATA_DIR/plainbase.db` also holds *real* state that does not.

**Recovers on the next boot, automatically:**

- the directory itself (created on startup),
- a fresh `plainbase.db`, created and migrated to the current schema,
- a rebuilt, fully populated `search.db`,
- the id of every page that carries `id:` in its frontmatter - those `/p/{root}/{id}` permalinks and
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
- ids of pages that never carried a frontmatter id - fresh ids are minted, so old `/p/{root}/{id}`
  permalinks and citations to *those* pages break.

**Mitigation, before disaster:**

- run `plainbase adopt --write-ids` so every page's identity lives in the tree itself and survives
  any `DATA_DIR` loss. It covers **every configured root**, and it **refuses to run** (exit 1, nothing
  written) if it cannot see one of them - a root it skipped would be a root whose ids stayed in
  `DATA_DIR` alone, which is the exact loss this command exists to prevent. If it refuses, restore the
  missing path and re-run: adopt is idempotent.
- back up **every configured root's directory** (`CONTENT_DIR` with no `roots {}` block; otherwise
  `roots.docs.path` and each extra root's `path`) always; back up `DATA_DIR/plainbase.db` too for durable
  identity, retirement, security and workflow state.
- on a multi-root install, back up `DATA_DIR/plainbase.conf` and `DATA_DIR/roots.conf` too. Neither is
  reconstructable from the content trees: they're the only record of *which* directories are roots,
  under what names, with what `editable`/`history` settings, and **in what ORDER** - and the order is
  not cosmetic. The root NAMES are baked into every rooted `/p/{root}/{id}` permalink and citation, so
  a restore that brings a root back under a different name rots every citation into it; and a root's
  rank is its line in the `roots {}` block, which decides SOURCE PRECEDENCE - the order a bare `/p/{id}`
  lists its candidate roots - not who keeps a shared id (two roots holding one `id:` both keep it, each
  at its own rooted permalink; see
  [Configuration: the order of the block](configuration.md#the-order-of-the-block-decides-source-precedence-not-permalinks)).
  Lose these files without a backup and a restore has the pages back but not the topology that made
  them a multi-root install.

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
  Do NOT expect `rescan` to surface the restored content. _Rehearsed for real: 2026-09-19 against
  Cloudflare R2 in an isolated two-page test prefix. The restored page 404'd before the copy,
  `rescan` answered 200 while the page stayed 404 (proving rescan never reads the bucket), and a
  restart served it with byte-identical content._
- **Bundle history restore (required drill, `git.enabled=true` only).** Wipe `DATA_DIR`, boot, and
  verify `git -C <DATA_DIR>/mirror log` shows history up to the last shipped bundle plus exactly one
  `reconcile: bucket state at boot` commit for the divergence (the mechanism documented under
  [Object-storage backend](#object-storage-backend-storagebackendobject) above). _Rehearsed for real:
  2026-09-19 against Cloudflare R2 in an isolated test prefix. An API edit shipped a valid bundle;
  after an out-of-band bucket edit, a fresh `DATA_DIR` recovered the prior history plus exactly one
  reconcile commit. The bucket content survived byte-for-byte, and the re-shipped bundle contained
  the recovered HEAD before shutdown._
- **Versioned-S3 per-object restore (BONUS tier, documented, never drilled).** On a versioning-enabled
  S3 bucket, `aws s3api list-object-versions` then copy a prior `versionId` over the current key; then
  restart or wait for the poll (same surfacing rule - not `rescan`). Versioned-S3 deployments only; R2
  has no versioning (see [Per-backend backup guidance](#backups) above).

## Stopping Plainbase: SIGTERM and supervisor grace

`docker stop`, systemd and Kubernetes normally deliver **SIGTERM**. Plainbase also routes SIGINT through the
same shutdown path. The path is ordered: close HTTP admission and stop the CIO engine, drain the final admitted
calls, close content watchers, drain rebuild/maintenance work, make the final object-mode DR attempt when
enabled, close transports and databases, then release the `DATA_DIR` lock. Dependent closes must not release
that lock while an admitted write is still completing.

Before the runtime hook has been installed, startup may still be doing boot preparation, hydrate/restore/reconcile
work, synchronous DR-bundle shipping, or the initial rebuild; see [Object-storage backend](#object-storage-backend-storagebackendobject)
for the slow-upload log cue. A signal in that window has no Plainbase shutdown acknowledgement; the supervisor
must rely on its process policy and may need to retry or restart. After the hook is active, the shutdown-rejection
response (`503`) is additive: it prevents new work from entering the closing system while existing admitted work
follows the ordered drain. The `shutting down: ...` and `shutdown complete in ...` stderr lines are the start and
completion cues; completion is the positive acknowledgement, and missing logs alone do not prove SIGKILL.

The CIO engine is configured with a **3-second graceful-stop interval and a 5-second engine timeout**. CIO's five-second
allowance includes its three-second grace: if work remains, CIO cancels it and waits up to the remaining two seconds.
Application disposal has a separate configured five-second wait, giving a ten-second configured-wait forecast, not a
completion bound. Actual completion can outlast it. The later final-call drain, watcher and scheduler joins, Git
maintenance, DR bundle creation/upload, transport close, ordinary I/O and lock acquisition can each take longer.
Completion waits can also remain pending indefinitely when a collaborator or a shared writer never completes. The
8-second `WARN` is a shutdown diagnostic; it is not a supervisor deadline and does not force Plainbase to return.

A `shutdown wait: phase '…' exceeded its …ms forecast` warning identifies an incomplete phase. Current diagnostic inputs
are 10s per watcher, 60s for the rebuild scheduler, 60s per unfinished Git-maintenance job, and 5s for each
transport/database/context/lock phase; HTTP uses 10s and DR uses 21min as described above. Pending construction adds a
5s estimate. The initial aggregate is frozen at the first owner drain; each phase uses its own entry snapshot, so later
maintenance work can change that phase's forecast without changing the initial aggregate. These are warning inputs,
not worst-case durations or supervisor settings.

The completion duration runs from the first owner drain through completed resource cleanup; it excludes signal
delivery and subsequent process exit.

### Derive the supervisor grace from the workload

Use the measured, representative completed operation for each admitted term and keep diagnostic forecasts
separate from capacity assumptions. The planning form is:

`G = H + W + R + M + C + S + Q + B + U + D + margin`

Here `H` is the final HTTP-call drain, `W` watcher close, `R` rebuild drain, `M` Git maintenance, `C` cadence
drain, `S` ship drain, `Q` the final Git-state probe, `B` bundle creation, `U` pre-request hashing plus the final
streaming PUT and `D` dependency/context/lock close. Account for a shared-lock wait once inside the operation
that incurs it; do not add overlapping lifetimes or add a measured whole-owner drain a second time. Record zero
explicitly only for an absent term under the stated workload. Count roots, pages, bytes, admitted jobs and the
actual runtime/storage endpoint in the measurement record. A deliberately held writer is a correctness test, not
a production-latency estimate. Any term whose completion is inherently unbounded cannot support a graceful
guarantee; the supervisor timeout remains a forced-termination boundary.

One measured LOCAL example used one root, 32 deterministic valid-ID Markdown pages of 4,096 bytes each (131,072
initial content bytes), one same-size authenticated PUT completed before SIGTERM, and Git disabled with no OBJECT
backend. Its initial rebuild was 139 ms, the PUT was 18 ms, and the owner's completed shutdown log was 1,026 ms;
that log is the measured `H + W + R + D` owner aggregate. The parent's 1,046 ms signal-to-exit interval is the
outer signal-through-post-exit-checks observation used for supervisor sizing. The 20 ms residual is derived from
those two observations, not a separately measured phase. Watcher, rebuild, and final-call activity were not
separately sampled, so they remain within the owner aggregate and watcher/scheduler close is not falsely zero.
For this Git-disabled LOCAL/OBJECT-absent workload
`M = C = S = Q = B = U = 0`; `Q` is the final Git-state probe and `U` is object prehash/upload. Lock waits belong
once inside the operation that incurs them. With one chosen 500 ms margin, `G_example = 1,046 + 500 = 1,546 ms`,
rounded once to a 2-second example supervisor setting. This is one ordinary local observation, not a product
default, capacity guarantee, or maximum; do not add the 500 ms margin again outside `G`. Remote OBJECT capacity
inputs remain provisional because endpoint, bundle-size, contention, cadence, ship, final-Git, and upload capacity
were not measured here. See the [historical shutdown and Git measurements](reports/server-shutdown-and-git-measurements.md)
for the source, runtime, method, values, and local raw-evidence custody.

The supervisor setting should use the derived value with the stated margin and rounding. Docker's Linux default is **10 seconds**;
Kubernetes' default `terminationGracePeriodSeconds` is **30 seconds** and includes `preStop`; both are often
too short. Docker Compose's `stop_grace_period` and `stop_signal` apply to the standalone Compose file actually
started. The repository's root `docker-compose.yml` (local development) and
`deploy/proxy/docker-compose.proxy.yml` (deploy/proxy) are separate stacks, not merged overlays; configure and
measure the one you run. Compose `init: true` and Docker's `--init` option can forward signals and reap child
processes, but they are process-hygiene aids, not Plainbase correctness requirements. A custom image or Kubernetes
deployment should use a PID 1 that forwards signals and reaps children; Kubernetes has no universal Docker-style
init flag, and this guidance is neither a grace-period guarantee nor a new correctness prerequisite.

For Kubernetes, set `terminationGracePeriodSeconds` from the measured workload and account for any `preStop`
time inside that same window. For systemd, set `TimeoutStopSec` from the same calculation: it covers the
stop operation and service wait, after which systemd forcibly terminates the service tree. `KillMode=mixed`
keeps the main process graceful while ensuring the remaining cgroup is not left behind; there is no checked-in
systemd unit, so these are deployment settings rather than Plainbase defaults.

If a supervisor expires its grace, SIGKILL can interrupt a page write or leave a backup bundle truncated. A
write that already returned successfully has its own durability result, but an in-flight write or final backup
does not. On the shared write path, a stalled Git maintenance or save writer can block subsequent saves; an
independent read path can continue and observe different state. Inspect the next boot/reconciliation and the
shutdown log rather than treating forced termination as a clean completion.

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
- **A throttled GET (503/SlowDown, 429) takes longer instead of failing.** The in-memory mirror GETs
  (hydrate and poll) alone retry, up to 3
  times with short backoff, before the failure paths above see them - a throttled LIST does not (nor
  does the streaming bundle-restore GET), so a
  throttled boot still refuses on its LIST self-check (the bullet above). The worst case per GET is round-trip
  inclusive: up to 4 request timeouts plus about 2 seconds of backoff, so about 2 minutes at the default
  30 s request timeout. A boot against a throttling provider is slow, never unbounded.
- **R16 fail-closed signature: an object-mode boot refusing on a TLS or signature rejection.** That is
  the guard working, not a failure to route around. Never disable certificate validation to "fix" it -
  fix the endpoint or install the host CA trust (see
  [base-image requirements](deploy/object-storage.md#native-binary-base-image-requirements)).

## Adopting an existing repo

`plainbase adopt` gives an existing Markdown tree Plainbase-native page identity - a stable `id:`
in each page's frontmatter that survives moves, renames, and (per the section above) a lost
`DATA_DIR`.

**It adopts every configured root, or none.** With a `roots {}` block it reads them all first, works out
every page's identity in one pass over the whole corpus, and only then writes (one section of output per
root, named). If any configured root's path is not available it refuses outright - exit 1, nothing
written - rather than half-adopt a corpus, and a root that disappears *while it is running* aborts the
run for the same reason. The reason is the whole point of the command: the ids of a root it skipped would
live in `DATA_DIR` alone, so that root would lose every permalink and citation in exactly the disaster
`--write-ids` is meant to survive. Restore the path and re-run (adopt is idempotent), or drop the root
from the block if it is gone for good.

**If the tree moves under it, it aborts - it never improvises.** Adopt only ever *replaces* a page it has
already read: it cannot create a file, and it cannot create a directory. So a root that is unmounted or
deleted mid-run is never quietly re-created on disk holding just the pages that run had got to (a partial
skeleton of your tree, possibly at a path that now points somewhere else entirely), and a page that is
edited or deleted after the plan was made is never overwritten with content derived from the stale read.
Any of these stops the run and names the page. **The remedy is always the same, and it is always safe:
restore the root (or let the tree settle) and re-run.** What an abort can leave behind is a page whose
`id_map` row exists while its file does not carry the `id:` line yet - which is exactly the state adopt
exists to repair, so a re-run converges on it. Adoption deletes nothing and is idempotent.

Reading everything before writing anything is also what lets it settle a page id that two files in ONE
root both claim (a copy-paste within a tree, say): the same id cannot live twice in one root, so one page
keeps it and the other is given a fresh one, reported as a `duplicate_id`. The SAME id in two DIFFERENT
roots is not a contest at all under per-root identity (C5) - a file copied between trees keeps its id in
BOTH, each root answering its own rooted `/p/{root}/{id}` - so adopt reassigns nothing there and reports
nothing. `adopt --write-ids --dry-run` previews exactly these decisions - the same bytes - without
touching a file.

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

Measured medians (provider, platform, corpus size, date): **cold 9562 ms / warm 2106 ms** - Cloudflare
R2, macOS (Apple Silicon, native binary) over a residential link, 1000-page corpus, 2026-07-29. Both
under budget (cold < 10 s, warm < 3 s). Read the cold number with its environment in mind: the budget
models the deployed topology (server adjacent to the bucket, ~1-3 ms RTT), so a residential-link pass
is the conservative case. The same drill measured 16-way lockstep fetching at ~25 s cold, which is what
sized the hydrate pipeline (64 concurrent fetches; chunks close at a 64 MiB declared-byte budget or
256 keys, whichever comes first, and the fetch loop closes a chunk early once ACTUALLY received bytes
reach the same budget).

### Git command deadlines and completion

Ordinary Git commands that Plainbase makes use of, including foreground maintenance and GC, use the executor's **30 s
command deadline** by default. Only DR bundle creation and restore fetch explicitly use a **600 s** per-invocation
override because they are size-dependent operations.
Deadline expiry records failure and initiates termination; byte caps and bounded observation slices do not impose
a helper-completion deadline. The process and its helpers can remain pending indefinitely, retaining the writer
and its locks and blocking subsequent saves. A supervisor owns the external whole-tree termination boundary.
There is no single exact total to quote because one save issues roughly a dozen invocations in sequence (capturing
HEAD, seeding a temp index, hashing the blob, updating it, writing the tree, creating the commit, updating the
ref, and a couple more). A wedged repository can therefore keep a save pending until its collaborators finish or
the supervisor terminates the process. The historical Git invocation rows and their source/runtime limits are
summarized in the [shutdown and Git measurements report](reports/server-shutdown-and-git-measurements.md).

## Operational logs and one-shot command output

Local JVM and native launches write readable operational records to stderr. The container image selects
the JSON profile before JVM initialization, so `docker logs` and Kubernetes collectors receive one JSON
object per operational line:

```sh
./plainbase serve 2>plainbase.log
docker logs -f plainbase
kubectl logs -f deployment/plainbase
```

`PLAINBASE_LOG_LEVEL` filters operational telemetry in both profiles. To override an image's default,
replace its launcher options, for example
`PLAINBASE_OPTS=-Dlogback.configurationFile=logback.xml -Dplainbase.commandEvents=plain`.

One-shot CLI commands retain a separate wire contract: results and reports are stdout; usage and expected
refusals are stderr; exit codes remain 0/1/2 for success/runtime/usage. Token commands print plaintext only
on stdout, so capture that stream separately. `adopt --write-ids` publishes its pre-write event as plain
stdout locally and typed JSON stderr in the container, and refuses the write if that event cannot be flushed.

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
