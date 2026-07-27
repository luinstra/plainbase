# Configuration

Full reference for every environment variable Plainbase reads. The README keeps a five-row quick
table for the everyday knobs (`CONTENT_DIR`, `DATA_DIR`, `PLAINBASE_HOST`, `PLAINBASE_PORT`,
`PLAINBASE_LOG_LEVEL`); this is the complete surface. Every row below is read directly from
`PlainbaseConfig.build()` (`server/src/main/kotlin/com/plainbase/frameworks/config/PlainbaseConfig.kt`),
with one exception - `PLAINBASE_LOG_LEVEL`, a logback-level env var - noted below. The one
file-only key with no env twin is the `roots {}` block (its own section below).

## Logging and command channels

Logging is launcher/backend configuration, not a `PlainbaseConfig` field. Native binaries and local
JVM distributions use the readable `logback.xml` profile; the container image selects
`logback-container.xml` before the JVM starts and emits JSON Lines operational logs to stderr.
`PLAINBASE_LOG_LEVEL` controls either profile. A launcher may override the container default with, for
example, `PLAINBASE_OPTS=-Dlogback.configurationFile=logback.xml`.

Command results and reports use stdout. One-time token plaintext is written only to that result channel.
Usage errors and expected refusals use stderr. Operational logs and adoption command events never contain
token plaintext, credentials, authorization headers, cookies, or signed URLs. The `adopt --write-ids`
pre-write event is synchronously flushed before mutation; it is plain stdout locally and typed JSON stderr
in the container, independent of the ordinary log level.

## Env-wins-over-file, restart-only

Environment variables always win over `DATA_DIR/plainbase.conf` (HOCON, ADR-0009): the file only
supplies values env omits. Secrets (`PLAINBASE_PROXY_SECRET`) belong in env, not the file - the
file path exists for completeness, not as the recommended place for a secret. Config loads once at
boot; every key here is restart-only, there is no hot reload.

## Reference table

| Env var | Config path | Default | Source |
|---|---|---|---|
| `CONTENT_DIR` | `contentDir` | `./content` | PlainbaseConfig.kt |
| `DATA_DIR` | (env/default only, never file) | `./data` | PlainbaseConfig.kt |
| `PLAINBASE_HOST` | `host` | `127.0.0.1` (`DEFAULT_HOST`) | PlainbaseConfig.kt |
| `PLAINBASE_PORT` | `port` | `8080` (`DEFAULT_PORT`) | PlainbaseConfig.kt |
| `PLAINBASE_LOG_LEVEL` | - | `INFO` | `logback.xml:8-9` (`${PLAINBASE_LOG_LEVEL:-INFO}`; **not** a `PlainbaseConfig` field) |
| `PLAINBASE_MAX_WRITE_BODY_BYTES` | `maxWriteBodyBytes` | 1 MiB | PlainbaseConfig.kt |
| `PLAINBASE_MAX_ASSET_BYTES` | `maxAssetBytes` | 10 MiB | PlainbaseConfig.kt |
| `PLAINBASE_AUTH_MODE` | `auth.mode` | `off` (blank parses to `OFF`) | PlainbaseConfig.kt |
| `PLAINBASE_TRUSTED_PROXY` | `auth.trustedProxy` | `[]` | PlainbaseConfig.kt (comma-list, CIDR-validated at load) |
| `PLAINBASE_PROXY_SECRET` | `auth.proxySecret` | none (required in `proxy` mode) | PlainbaseConfig.kt |
| `PLAINBASE_PROXY_IDENTITY_HEADER` | `auth.proxyIdentityHeader` | `X-Forwarded-User` | PlainbaseConfig.kt |
| `PLAINBASE_INSECURE_HTTP` | `auth.insecureHttp` | `false` | PlainbaseConfig.kt |
| `PLAINBASE_AGENT_DIRECT_COMMIT_GLOBS` | `auth.agentDirectCommit.globs` | `[]` | PlainbaseConfig.kt |
| `PLAINBASE_MCP_ALLOWED_HOSTS` | `auth.mcpAllowedHosts` | fail-closed bind-host default | PlainbaseConfig.kt |
| `PLAINBASE_MCP_ALLOWED_ORIGINS` | `auth.mcpAllowedOrigins` | fail-closed bind-host default | PlainbaseConfig.kt |
| `PLAINBASE_GIT_ENABLED` | `git.enabled` | auto-detect (`null`) | PlainbaseConfig.kt |
| `PLAINBASE_GIT_AUTHOR_NAME` | `git.authorName` | `Plainbase` | PlainbaseConfig.kt |
| `PLAINBASE_GIT_AUTHOR_EMAIL` | `git.authorEmail` | `plainbase@localhost` | PlainbaseConfig.kt |
| `PLAINBASE_STORAGE_BACKEND` | `storage.backend` | `local` | PlainbaseConfig.kt (`local` \| `object`; `object` serves an S3-compatible bucket as the authority) |
| `PLAINBASE_S3_ENDPOINT` | `storage.object.endpoint` | none (**required** in `object` mode) | PlainbaseConfig.kt (absolute https URL; `http` refused unless `PLAINBASE_INSECURE_HTTP`) |
| `PLAINBASE_S3_BUCKET` | `storage.object.bucket` | none (**required** in `object` mode) | PlainbaseConfig.kt |
| `PLAINBASE_S3_ACCESS_KEY_ID` | (env only, never file) | none (**required** in `object` mode) | PlainbaseConfig.kt (secret: env only, never `plainbase.conf`) |
| `PLAINBASE_S3_SECRET_ACCESS_KEY` | (env only, never file) | none (**required** in `object` mode) | PlainbaseConfig.kt (secret: env only, never `plainbase.conf`) |
| `PLAINBASE_S3_REGION` | `storage.object.region` | `auto` (R2) | PlainbaseConfig.kt |
| `PLAINBASE_S3_PREFIX` | `storage.object.prefix` | `""` | PlainbaseConfig.kt (validated through the `TreePath` funnel when non-empty) |
| `PLAINBASE_S3_PATH_STYLE` | `storage.object.pathStyle` | `true` (R2 account-endpoint) | PlainbaseConfig.kt |
| `PLAINBASE_S3_POLL_SECONDS` | `storage.object.pollSeconds` | `60` | PlainbaseConfig.kt |

Any `storage.object.*` key set while `storage.backend=local` is ignored with a single startup warning
that names the keys (a shared `plainbase.conf` across a local and an object deploy stays legal). In
`object` mode `CONTENT_DIR` is ignored (the bucket is the authority); an explicitly-set `CONTENT_DIR`
warns.

**`object` mode** makes an S3-compatible bucket the authoritative content store; `DATA_DIR/mirror` is a
local, derived, deletable cache of the bucket (rebuildable at any time - delete it and it self-heals on
the next boot). The bucket MUST offer **strong read-after-write and strong LIST consistency** (R2 and AWS
S3 do); an eventually-consistent-LIST backend can transiently reap a just-saved page from the mirror. See
[operating-plainbase.md](operating-plainbase.md#object-storage-backend-storagebackendobject). At startup `serve` (and offline `adopt --write-ids` / `reindex`) HYDRATE the mirror from
the bucket before serving, so a fresh install pulls the whole corpus down first. The first bucket LIST
doubles as a fail-closed TLS/signature self-check: an unreachable endpoint, a rejected certificate, or a
bad signature makes startup **refuse with an operator-actionable message and exit** rather than serve a
stale or empty tree (never disable certificate validation to work around a TLS failure - fix the endpoint
or this host's CA trust). Git history over the object backend IS available: set
`PLAINBASE_GIT_ENABLED=true` to commit every save into `DATA_DIR/mirror` and ship a bucket-backed
`history.bundle` (C5's git-over-the-mirror + bundle DR), recovering commit-grained history after a
`DATA_DIR` loss, not just content. Leave git unset/false to run object mode without history. See the
[object-storage deploy guide](deploy/object-storage.md) for a zero-to-serving R2 walkthrough (IAM,
migration, platform support), [operating-plainbase.md](operating-plainbase.md#backups) for the
object-mode backup guidance, and [ADR-0010](decisions/0010-object-storage-backend.md) for the design
record.

## Root names are top-level URL segments

Page URLs are `/docs/{root}/{path}`, so a root's name IS a top-level URL segment on this server. Two
rules follow from that - one on the SHAPE of a name, one on the segments the product already owns or
expects to grow into - and each is a refusal naming the offending root, at boot and at `plainbase root
add` alike.

**The shape.** A root name is a lowercase slug - `[a-z][a-z0-9]*(-[a-z0-9]+)*`, 2 to 32 characters:
letter-leading, digits and hyphens after it, no doubled or trailing hyphen, no dots or underscores. It
also may not parse as a page id (a 32-hex or UUID string), which is what keeps `/p/{root}/{id}` from
being ambiguous with the bare `/p/{id}` permalink.

**The reserved segments.** No root may be named:

- one of Plainbase's own top-level URLs, live or foreseeable - `api`, `assets`, `browse`, `healthz`,
  `p`, `fonts`, the SPA's `admin`, `new` and `review`, and the auth/ops/discovery vocabulary
  (`login`, `search`, `session`, `settings`, `static`, `metrics`, `well-known` and the rest);
  `ReservedSegments.kt` holds the list in full and it is deliberately generous, because growing it later
  would boot-refuse an install that had already taken the new word;
- anything beginning `pb-` or `plainbase-`, or the bare stems `pb` and `plainbase`;
- a `v` followed by digits and nothing else (`v1`, `v2`, `v10`), which keeps the API-version namespace
  free without enumerating it. `v2beta` and `v1alpha1` are legal names and are NOT reserved.

Corpus vocabulary is not reserved: `guides`, `notes`, `handbook`, `changelog`, `team` are all fine, and
so is `docs`. The rule fires wherever a root is registered - `plainbase.conf`, `DATA_DIR/roots.conf`, and
`plainbase root add`, which refuses before it writes anything (see
[the CLI](#the-cli-and-the-two-files) below). A reserved name already sitting in a config file makes that
config unloadable, so `plainbase root remove` cannot dig you out either: edit the file that declares it.

**Nothing in your CONTENT is reserved.** The rule above governs the names you give ROOTS, and nothing
else. A top-level `main/` directory in the primary root, a `main.md`, a `slug: main` are all ordinary
content: they serve at `/docs/main/main/...`, under the root segment like everything else.

## Multiple document roots - the `roots {}` block

A top-level `roots {}` block in `plainbase.conf` declares the server's document directories
([ADR-0011](decisions/0011-multi-root-document-directories.md)). It is **file-only** - there is no
env-var grammar for it (a `plainbase root add/remove/list` CLI manages a second, machine-owned file
instead - see [The CLI and the two files](#the-cli-and-the-two-files) below) - and like every key it
is restart-only.

```hocon
roots {
  main    { path = "/home/me/docs" }                    # editable=true, history=auto by default
  memoria { path = "/home/me/dev/memoria/.crew" }       # editable=false, history=off by default
  notes   { path = "/home/me/notes", editable = true }
}
```

Per-root keys:

| Key | Meaning | Default (`main`) | Default (extras) |
|---|---|---|---|
| `path` | the directory the root serves (**required**, non-blank) | - | - |
| `editable` | whether pages in this root can be edited/created. **Topology, not authorization**: it is enforced in EVERY auth mode, `off` included, and a write to a read-only root answers 403 `root_not_editable` | `true` | `false` |
| `history` | `off` \| `auto` \| `native` git history mode | `auto` (today's repo auto-detection) | `off` (Plainbase never commits into a repo it does not own) |

### The ORDER of the block decides SOURCE PRECEDENCE, not permalinks

**A root's rank is its DECLARATION LINE in this block, and the lowest rank wins.** Two roots may hold two
different pages carrying the same frontmatter `id:` - a copied file, a forked runbook, a page moved
by hand between trees. Under per-root identity (C5) BOTH pages KEEP that id: each lives in its own root
and each answers its OWN rooted permalink `/p/{root}/{id}`. Rank no longer decides who "wins" the id - it
decides only SOURCE PRECEDENCE, the order a bare, root-less lookup ranks the candidate roots in.

The bare `/p/{id}` permalink no longer picks a winner. A bare id held by more than one root answers
**300 Multiple Choices**, one `Link: rel="alternate"` per candidate root in rank order, so the caller
disambiguates by naming a root. **Reordering the block therefore reassigns NO permalink:**

```hocon
# `runbooks` is rank 0, so in a bare /p/{a-shared-id} listing ITS root is offered first.
roots {
  runbooks { path = "/srv/runbooks" }
  main     { path = "/srv/docs" }
  archive  { path = "/srv/archive" }
}

# The same three roots, alphabetized with main first. No value changed and NO permalink moved:
# /p/runbooks/{a-shared-id} and /p/main/{a-shared-id} both answer exactly as before. `main` is
# rank 0 now, so it is merely offered FIRST in the bare 300 disambiguation list.
roots {
  main     { path = "/srv/docs" }
  archive  { path = "/srv/archive" }
  runbooks { path = "/srv/runbooks" }
}
```

**Nothing refuses and nothing warns**, and nothing needs to: a shared id is no longer a contest. Both
orders are valid topologies; a re-rank changes only the order a bare `/p/{id}` lists its candidate roots,
never which page any rooted permalink opens. Still, the order is worth keeping deliberate - it is the
tie-break every bare lookup reads.

Three consequences worth stating outright:

- **`main` is not automatically rank 0.** It ranks where you declared it, so this really does let
  `zeta` outrank `main` - that is the point of honoring the order you wrote:

  ```hocon
  roots {
    zeta { path = "/srv/zeta" }
    main { path = "/srv/docs" }
  }
  ```

- **Roots declared on ONE line rank ALPHABETICALLY, not left-to-right.** Rank reads the declaration
  LINE, so roots sharing a line are tied and the tie breaks on the name. Written on one line
  (`roots { zeta { path = "/srv/zeta" }, main { path = "/srv/docs" } }` - and the comma is not
  optional there; without a newline between them HOCON refuses to parse the block at all), the
  example above means the OPPOSITE of what it reads like: both roots are on line 1, so `main`
  outranks `zeta`. **Give each root its own line and the question never arises** - which is why every
  example here does.
- **`roots.conf` (the CLI's file) always ranks after `plainbase.conf`'s block**, and `plainbase root
  add` **appends**, so a newly added root ranks last. There is NO rename operation: an extra root's name
  is IMMUTABLE, because it is part of every permalink into it, so Plainbase ships no `root rename`/`mv`.

  > **Root rename/mv rots rooted citations.** A root's name is part of every permalink into it
  > (`/p/{root}/{id}`), so removing and re-adding a root under a different name, or `mv`-ing files
  > between root directories outside Plainbase, breaks the citations that named the old root. After the
  > old name is removed it is UNREGISTERED, so `/p/{old}/{id}` answers **404** (an unregistered root is
  > not found; the server never invents a page for a name it no longer knows). A rooted permalink answers
  > **410** with a `Link: rel="alternate"` hint only while the old root remains REGISTERED and has a
  > tombstone for that id.

Rank is also why `DATA_DIR/plainbase.conf` and `DATA_DIR/roots.conf` are worth backing up: they are
the only record of the order (see
[operating-plainbase.md](operating-plainbase.md#losing-data_dir-what-recovers-and-what-doesnt)).

### `history` on an extra root: `native` or `off`, never `auto`

`auto` on an EXTRA root is a **boot error**. Auto detects a repository and may `git init` one - which
is exactly what Plainbase must not do in a tree it does not own. An extra root either CLAIMS an
existing repository explicitly (`history = native`) or records no history (`off`).

A `native` root is strictly guarded at boot, and the guard **refuses to start** rather than degrade:
Plainbase will not commit into a **linked worktree**, a **submodule**, or a repository rooted at a
SURROUNDING checkout. In all three cases `git -C <root>` succeeds quietly against a repository that
is not this root's, and you would find out when an unrelated branch had Plainbase commits on it. It
also never `git init`s a claimed root: a missing `.git` there is reported, not created.

For `main`, `history` and the `git.enabled` tri-state are two knobs on one thing. `auto` (the
default, and what every config without a `roots {}` block produces) is compatible with either
`git.enabled` value. Setting them to CONTRADICT (`history = native` with `git.enabled = false`, or
`history = off` with `git.enabled = true`) is a boot error naming both keys - never a silent winner.

Validation at boot (each failure is an actionable `serve:` refusal naming the offending root):

- a root named `main` is **required** (it is the reserved primary);
- names are lowercase slugs (`[a-z][a-z0-9]*(-[a-z0-9]+)*`, 2-32 chars), may **not** be page-id-shaped
  (a 32-hex string a page id could parse), and may not take a reserved segment (`api`, `assets`, `new`,
  the `pb-`/`plainbase-` prefixes, the `v[0-9]+` version shape, and the rest of the list). Each is a boot
  **refusal**; the full rule is in
  [Root names are top-level URL segments](#root-names-are-top-level-url-segments) above;
- `main`'s path must exist and be a **readable and searchable** (`r-x`) directory - a missing or
  unreadable `main` is a boot **refusal**, never a degraded 503 root (see
  [When a root is not there](#when-a-root-is-not-there) below). The identical rule applies to
  `CONTENT_DIR` when there is no `roots {}` block at all: it is the same root, under the same name;
- a missing/unreadable EXTRA path is a startup **warning**, never a boot error - the root serves 503
  until it is restored AND the server is restarted;
- `history = auto` on an extra root is refused (see above);
- no two roots may resolve to the same directory (symlinks are resolved for this check), no root may
  nest inside another, and no root may equal or live inside `DATA_DIR`;
- `roots {}` cannot be combined with `storage.backend=object` in this release - object deployments
  keep the plain `CONTENT_DIR`-less config shape.

When a `roots {}` block is present, an explicitly set `CONTENT_DIR`/`contentDir` is **ignored** with
a startup warning: `roots.main.path` is main's directory.

### The CLI and the two files

```
plainbase root add <name> <path> [--editable] [--history off|native]
plainbase root remove <name>
plainbase root list
```

Exit codes: `0` success, `1` runtime failure, `2` usage error (the same convention as
`reindex`/`adopt`, [operating-plainbase.md](operating-plainbase.md#manual-reindex-the-two-paths)).

**Two files, one merge, one owner each:**

- `plainbase.conf`'s `roots {}` block is yours, by hand. `plainbase root` never opens it for
  writing - not a best-effort round-trip, an absence of code. Comments, formatting, key order and
  every hand-written value survive by construction.
- `DATA_DIR/roots.conf` is the CLI's. Every `add` or `remove` **rewrites it in full**. Do not
  hand-edit it - a hand edit is lost on the next `add`/`remove`.
- At boot the two files **merge**. A root name declared in both is a **boot error** naming the
  file - never a silent winner, never a merge of the two declarations.
- **`main` is never CLI-managed.** `plainbase root add main` and `plainbase root remove main` are
  usage errors (exit 2), and `roots.conf` declaring `main` is a boot error. Main's path keeps coming
  from a hand-written `roots {}` block in `plainbase.conf`, or from `CONTENT_DIR` when there is none.
- **Restart to apply.** `root add`/`remove` edit a file; they do not talk to a running server, and the
  server does not hot-reload topology. Nothing changes until the next restart.

`root add` refuses outright (an error message, not a silent skip) on:

- **a reserved segment** - Plainbase owns a set of top-level URLs for its own surfaces (`api`,
  `assets`, `browse`, `healthz`, `p`, `admin`, `new`, `review` and the like), plus the `pb-` and
  `plainbase-` prefixes and any `v` followed only by digits. No root may take one. The refusal names
  the word, and it is exit 2 - a bad argument, refused before anything is locked or read.
- **nesting** - the new path may not sit inside another configured root or inside `DATA_DIR`, and may
  not equal one already configured.
- **a duplicate declaration** - the name is already in `roots.conf` or in `plainbase.conf`'s block.
- **object mode** - `roots {}` cannot be combined with `storage.backend=object` in this release, so
  there is no local tree for a root to add.
- **`--history auto`** - an extra root accepts only `off` or `native`; `auto` is always a boot error
  on an extra (it can `git init` a repository Plainbase does not own), so the CLI refuses it up front
  too.
- **an empty path** - `plainbase root add docs "$DOCS_DIR"` with `DOCS_DIR` unset is a usage error
  (exit 2), never a root pointing at the process's working directory.

It does **not** refuse a path that is not there: an extra root may legitimately be a volume that is
not mounted yet (the server marks it unavailable and serves 503 for it until it is restored and the
server restarted). The CLI prints the same warning `serve` prints, and exits 0 - so a typo'd path is
visible, not silent.

`--editable` defaults to `false` for an extra root; `--history` defaults to `off`.

`root remove` of the **last** managed root deletes `roots.conf` outright, so the topology is then
whatever `plainbase.conf` alone says: main from its `roots {}` block or from `CONTENT_DIR`, plus any
**other** roots you declared there by hand. `plainbase root` never writes that file, so it cannot
remove those - deleting `roots.conf` returns the install to single-root behavior only if
`plainbase.conf` declares no extra roots of its own.

`root list` prints, per root: name, path, `editable`, `history`, **provenance**
(`plainbase.conf` / `roots.conf` / `CONTENT_DIR`), and whether the path is a readable directory
**right now**. It does not report live serving state - a separate process cannot know what a running
server has marked unavailable in memory - so it points at `GET /healthz` for that instead (see
[When a root is not there](#when-a-root-is-not-there) above).

## Per-root agent direct-commit globs

`auth.agentDirectCommit` is the agent **privilege gate**: a COMMIT-mode agent writing inside a glob
lands its change UNREVIEWED; outside one it degrades to a proposal for a human.

```hocon
auth {
  agentDirectCommit {
    globs = ["notes/**", "archive:2024/**"]   # MAIN's list - unchanged meaning, colons and all
    roots {
      archive  = ["2024/**"]                  # the ONLY way to grant an EXTRA root
      handbook = ["**"]
    }
  }
}
```

**The existing `globs` key is main-scoped and always will be**, and neither it nor its env var
(`PLAINBASE_AGENT_DIRECT_COMMIT_GLOBS`) changes meaning when you add roots. That is why per-root
globs are a structured block rather than a `<root>:<pattern>` prefix: a colon is a legal directory
name character AND a legal glob character, so an in-string grammar would silently RETARGET an
operator's existing `archive:2024/**` pattern the day they added a root named `archive` - revoking it
in main and granting it, unasked, inside the new root. **No config that authorizes something today
authorizes anything different after an upgrade.**

Rules:

- every key in the block must name a **configured** root (an unknown or malformed name refuses at boot);
- main's list has exactly ONE key. Declaring `roots.main` alongside `globs` or the env var is a boot
  error naming both - Plainbase will not guess, because a union would silently widen what an agent may
  commit unreviewed and a winner would silently drop an authorization you wrote;
- there is no env form for the block (extra roots are file-only by construction);
- a glob on a root with `editable = false` can authorize nothing - the editable gate denies first - so
  it emits a startup warning naming the root.

## When a root is not there

**`main` is the exception, and it is not a small one: a `main` that is not there at boot REFUSES to
start.** There is no degraded mode for it - main is the root the whole URL grammar and the SPA shell are
anchored on, so `serve` fails closed instead of coming up with an empty corpus:

```
serve: roots.main.path does not exist or is not a directory: /srv/docs
serve: CONTENT_DIR does not exist or is not a directory: /srv/docs     # the same fault, no roots {} block
```

The same refusal covers a `main` that exists but the server cannot **read and traverse** (`r-x`):
`... is not readable/searchable: /srv/docs`. Restore the path (remount the volume, fix the
permissions) and start again. If main vanishes while the server is already *running*, it behaves like
any other root below - 503, sticky until restart - but the *next* boot will refuse until it is back.

Everything below is about the **extra** roots.

An extra root that is missing at boot, or whose directory vanishes while the server runs, is marked
**unavailable** and:

- every read and write of it answers **503 `root_unavailable`** with a `Retry-After`, **never a 404**.
  A 404 tells an agent the page is gone and it should drop its citations; the truth is that a disk is
  unmounted and the content is coming back. Nothing is written on a 503;
- **nothing is deleted for it.** Its pages stay in the index (carried forward), and its `id_map`,
  `url_alias`, `page_checkpoint` and `dirty_page` rows are left exactly as they are;
- it still appears in `GET /api/v1/tree` with `"available": false` and an EMPTY subtree (never a stale
  listing), and in `GET /healthz` with a cause (`missing_at_boot` | `vanished` | `watcher_failed`);
- search results from it are dropped;
- a mid-run disappearance is detected **within 5 seconds even if the root is completely idle** - each
  available root's watcher probes its own directory on that interval, so a silent unmount or a renamed
  directory (neither of which raises a file event) is caught without any traffic to trip it.

**Unavailability is sticky until restart.** Restoring the directory does not bring the root back on
its own: a vanished root's scan and identity state cannot be trusted afterwards. Restore the path,
then restart the server.

## `auth.mode` - the three modes

- **`off`** - no login, no auth. Loopback-dev only, and despite being the "no auth" mode it is
  still subject to the fail-closed bind guard (`bindGuardRefusal()` in `PlainbaseConfig.kt`): a
  non-loopback `off` bind is refused unless a trusted proxy or `PLAINBASE_INSECURE_HTTP` override
  is present, because `off` is the **most dangerous** mode if it ever reached a public interface.
- **`builtin`** - password login; Plainbase manages its own users and sessions.
- **`proxy`** - a trusted reverse proxy asserts identity. This mode **requires both** a
  trusted-proxy CIDR (`PLAINBASE_TRUSTED_PROXY`) and `PLAINBASE_PROXY_SECRET`, or the bind guard
  refuses to start at all. See
  [`deploy/reverse-proxy-sso.md`](deploy/reverse-proxy-sso.md) for a worked deployment (Caddy +
  oauth2-proxy).

## Heap size (native binary) - not an env var

The native binary ships with a compiled-in max heap of **256 MiB** (`-R:MaxHeapSize=256m`, baked at
build time in `server/build.gradle.kts`). This bounds resident memory: GraalVM's Serial GC would
otherwise let the heap ratchet toward a large physical-memory-derived default and squat there. 256 MiB
boots the intended corpus range with wide margin; steady RSS lands ~90-160 MiB depending on corpus
size (a ~50-90 MiB share is off-heap - mmap'd SQLite, JNI, resident image code - and this knob does
not touch it).

Override it per process with a standard JVM flag, which always wins over the compiled-in default:

    plainbase -Xmx512m serve         # or -XX:MaxHeapSize=512m

Raise it for a very large corpus. The startup index build holds the whole content tree in memory, so
a large enough corpus (many thousands of pages) can eventually exceed 256 MiB and fail to boot with
`OutOfMemoryError: Garbage-collected heap size exceeded`. If you hit that on startup, raise `-Xmx`.

## Meilisearch is not a config key

`SEARCH_ENGINE` / `MEILI_URL` do **not** exist as Plainbase configuration - grepping
`server/src/main/kotlin` for either returns zero hits. Meilisearch is an out-of-process **upgrade
tier**, never a config value; see
[When to upgrade to Meilisearch](operating-plainbase.md#when-to-upgrade-to-meilisearch) in the
operating guide. The commented `SEARCH_ENGINE`/`MEILI_URL` lines in `docker-compose.yml` are a
reserved stub for a future phase, not a live setting you can turn on today.
