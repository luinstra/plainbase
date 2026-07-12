# Configuration

Full reference for every environment variable Plainbase reads. The README keeps a five-row quick
table for the everyday knobs (`CONTENT_DIR`, `DATA_DIR`, `PLAINBASE_HOST`, `PLAINBASE_PORT`,
`PLAINBASE_LOG_LEVEL`); this is the complete surface. Every row below is read directly from
`PlainbaseConfig.build()` (`server/src/main/kotlin/com/plainbase/frameworks/config/PlainbaseConfig.kt`),
with one exception - `PLAINBASE_LOG_LEVEL`, a logback-level env var - noted below. The one
file-only key with no env twin is the `roots {}` block (its own section below).

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

## Multiple document roots - the `roots {}` block

A top-level `roots {}` block in `plainbase.conf` declares the server's document directories
([ADR-0011](decisions/0011-multi-root-document-directories.md)). It is **file-only** - there is no
env-var grammar for it (a `root add/remove/list` CLI arrives in a later release) - and like every
key it is restart-only.

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
- names are lowercase slugs (`[a-z0-9][a-z0-9-]*`, max 32 chars);
- `main`'s path must exist and be readable; a missing/unreadable EXTRA path is a startup **warning**,
  never a boot error - the root serves 503 until it is restored AND the server is restarted;
- `history = auto` on an extra root is refused (see above);
- no two roots may resolve to the same directory (symlinks are resolved for this check), no root may
  nest inside another, and no root may equal or live inside `DATA_DIR`;
- `roots {}` cannot be combined with `storage.backend=object` in this release - object deployments
  keep the plain `CONTENT_DIR`-less config shape.

When a `roots {}` block is present, an explicitly set `CONTENT_DIR`/`contentDir` is **ignored** with
a startup warning: `roots.main.path` is main's directory.

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

A root that is missing at boot, or whose directory vanishes while the server runs, is marked
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
