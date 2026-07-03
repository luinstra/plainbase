# Operating Plainbase

Operator notes for running Plainbase in production and on a dev box. This document covers search
freshness, the manual-reindex paths, and the filesystem-native virtue. (Backups and the Git layer
are later phases.)

For **single-sign-on behind a reverse proxy** (`auth.mode=proxy`), see
[`deploy/reverse-proxy-sso.md`](deploy/reverse-proxy-sso.md) and the standalone Caddy + oauth2-proxy
reference stack under `deploy/proxy/`.

## The content tree is plain Markdown on disk

Plainbase's canonical content tree is **plain Markdown on disk** — the tree *is* the product. Because
of that, standard tools work directly on your content with **no Plainbase process running**:

```
rg "rolling deploy" content/        # ripgrep across the tree
grep -r "owner: platform" content/  # frontmatter scan
fzf < <(find content -name '*.md')  # fuzzy file navigation
```

This is a **documented product property, not a feature to build**. Plainbase's search is *additive*
— ranked, section-granular, and cited — never a gatekeeper between you and your files. If Plainbase
is down, your content is still fully readable, searchable, and editable with the tools you already
have.

## Search freshness: editing files outside Plainbase

Plainbase watches `CONTENT_DIR` and re-indexes when files change, so an edit made outside Plainbase
(in your editor, via `git checkout`, etc.) becomes searchable automatically. The end-to-end latency
is **debounce (0.5 s) + a full index rebuild + the search sync**.

### Platform note — the 5-second promise binds Linux

- **Linux** (the deployment platform) uses `inotify`: file-change events arrive in milliseconds. The
  "searchable within 5 seconds" promise is an automated test on Linux.
- **macOS** dev boxes use the JDK's `PollingWatchService` (multi-second poll interval; the
  `com.sun.nio.file` sensitivity modifiers are not reliably effective on JDK 21+). So **the 5-second
  promise does not bind macOS.** The practical answer on a Mac is a manual **rescan**
  (`POST /api/v1/admin/rescan`): it re-scans `CONTENT_DIR`, picks up the just-edited file, and
  diff-syncs search. **Reindex (below) will NOT surface a fresh disk edit** — it rebuilds the search
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
surfacing a *fresh* disk edit on a running server (that's `rescan` — above). The endpoint rebuilds
from the live published snapshot; the offline CLI re-scans the tree itself (no server is running to
hold a snapshot).

### `POST /api/v1/admin/reindex` — prefer this on a RUNNING instance

Reindexes in-process against the live published snapshot, with a single-flight guard: a concurrent
call returns `409 reindex_in_flight`. This forces a full generation-swap rebuild of the search
engine — distinct from `POST /api/v1/admin/rescan`, which re-runs the page-index pass and then
*diff-syncs* the engine. Use reindex when you want the search index rebuilt from scratch (e.g. after
restoring or replacing `search.db`).

```
# auth.mode=off (the local-dev tier) — no auth/CSRF. Behind a proxy or with builtin auth, this
# admin mutation needs an authenticated session + the X-CSRF-Token from GET /api/v1/session.
curl -X POST http://localhost:8080/api/v1/admin/reindex
# {"status":"ok","pages":42}
```

### `plainbase reindex` — the OFFLINE/ops path

For when the server is **down**, or for a scripted operational reindex:

```
CONTENT_DIR=./content DATA_DIR=./data plainbase reindex
# reindex: rebuilt the search index for 42 page(s) under /abs/path/to/content
```

**Do not run `plainbase reindex` against a live server** — use the endpoint instead. The CLI and a
running server are separate processes with separate write monitors; while SQLite WAL +
`busy_timeout` prevent *corruption*, they do not prevent the CLI silently publishing an *older*
generation over the server's newer one (a freshness regression). To make this safe, both the server
and the CLI take an advisory lock on `DATA_DIR/plainbase.lock`: the server holds it for its lifetime,
and the CLI **refuses to run while a server holds it**, exiting with:

```
reindex: a Plainbase server is holding /abs/path/to/data — stop it, or use POST /api/v1/admin/reindex on the running server
```

Exit codes: `0` success, `1` runtime failure (including the lock refusal), `2` usage error.

## `search.db` is derived state

`DATA_DIR/search.db` is rebuildable from the content tree at any time and **deletable with zero data
loss** — there are no migrations, ever. Delete it and reindex (or just restart, or let the next
content change trigger a rebuild): the engine-truth diff self-heals from an empty index back to the
full corpus. Only the content tree and `DATA_DIR/plainbase.db` carry durable state.

One clarification on the delete-and-rebuild path: **stop the server before deleting `search.db`**
(or just restart afterwards — a fresh boot recreates and repopulates it). Deleting the file under a
running server unlinks it while the server's open connections keep reading and writing the unlinked
copy, so the on-disk file only reappears on restart. On a *running* server, use
`POST /api/v1/admin/reindex` for a rebuild-in-place instead — never a live delete.

## Losing `DATA_DIR`: what recovers and what doesn't

`DATA_DIR` can vanish entirely — a lost volume, a wiped container — and Plainbase boots clean
against the surviving `CONTENT_DIR`. The content tree is the source of truth, so most state
re-derives; the app database `DATA_DIR/plainbase.db` also holds *real* state that does not.

**Recovers on the next boot, automatically:**

- the directory itself (created on startup),
- a fresh `plainbase.db`, created and migrated to the current schema,
- a rebuilt, fully populated `search.db`,
- the id of every page that carries `id:` in its frontmatter — those `/p/{id}` permalinks and
  citations keep working,
- `redirect_from` aliases (re-derived from frontmatter).

**Lost, by stated trade-off:**

- users and password hashes — re-run `plainbase admin setup-token` to bootstrap the first admin
  (the server logs the same hint at boot when no enabled admin exists),
- agent API tokens — re-mint them; token secrets are shown once and never stored,
- sessions, roles, and the audit log,
- pending proposals,
- move-history URL aliases: old URLs from past renames 404 unless re-declared as `redirect_from`,
- ids of pages that never carried a frontmatter id — fresh ids are minted, so old `/p/{id}`
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
