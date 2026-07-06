# Configuration

Full reference for every environment variable Plainbase reads. The README keeps a five-row quick
table for the everyday knobs (`CONTENT_DIR`, `DATA_DIR`, `PLAINBASE_HOST`, `PLAINBASE_PORT`,
`PLAINBASE_LOG_LEVEL`); this is the complete surface. Every row below is read directly from
`PlainbaseConfig.build()` (`server/src/main/kotlin/com/plainbase/frameworks/config/PlainbaseConfig.kt:216-252`),
with one exception - `PLAINBASE_LOG_LEVEL`, a logback-level env var - noted below.

## Env-wins-over-file, restart-only

Environment variables always win over `DATA_DIR/plainbase.conf` (HOCON, ADR-0009): the file only
supplies values env omits. Secrets (`PLAINBASE_PROXY_SECRET`) belong in env, not the file - the
file path exists for completeness, not as the recommended place for a secret. Config loads once at
boot; every key here is restart-only, there is no hot reload.

## Reference table

| Env var | Config path | Default | Source |
|---|---|---|---|
| `CONTENT_DIR` | `contentDir` | `./content` | PlainbaseConfig.kt:217 |
| `DATA_DIR` | (env/default only, never file) | `./data` | PlainbaseConfig.kt:196, :218 |
| `PLAINBASE_HOST` | `host` | `127.0.0.1` (`DEFAULT_HOST`, :164) | PlainbaseConfig.kt:219 |
| `PLAINBASE_PORT` | `port` | `8080` (`DEFAULT_PORT`, :156) | PlainbaseConfig.kt:220 |
| `PLAINBASE_LOG_LEVEL` | - | `INFO` | `logback.xml:8-9` (`${PLAINBASE_LOG_LEVEL:-INFO}`; **not** a `PlainbaseConfig` field) |
| `PLAINBASE_MAX_WRITE_BODY_BYTES` | `maxWriteBodyBytes` | 1 MiB (:167) | PlainbaseConfig.kt:221-222 |
| `PLAINBASE_MAX_ASSET_BYTES` | `maxAssetBytes` | 10 MiB (:170) | PlainbaseConfig.kt:223-224 |
| `PLAINBASE_AUTH_MODE` | `auth.mode` | `off` (blank parses to `OFF`, :380) | PlainbaseConfig.kt:231 |
| `PLAINBASE_TRUSTED_PROXY` | `auth.trustedProxy` | `[]` | PlainbaseConfig.kt:232-234 (comma-list, CIDR-validated at :261) |
| `PLAINBASE_PROXY_SECRET` | `auth.proxySecret` | none (required in `proxy` mode) | PlainbaseConfig.kt:242 |
| `PLAINBASE_PROXY_IDENTITY_HEADER` | `auth.proxyIdentityHeader` | `X-Forwarded-User` (:177) | PlainbaseConfig.kt:243-244 |
| `PLAINBASE_INSECURE_HTTP` | `auth.insecureHttp` | `false` | PlainbaseConfig.kt:235 |
| `PLAINBASE_AGENT_DIRECT_COMMIT_GLOBS` | `auth.agentDirectCommit.globs` | `[]` | PlainbaseConfig.kt:236-239 |
| `PLAINBASE_MCP_ALLOWED_HOSTS` | `auth.mcpAllowedHosts` | fail-closed bind-host default | PlainbaseConfig.kt:247-248 |
| `PLAINBASE_MCP_ALLOWED_ORIGINS` | `auth.mcpAllowedOrigins` | fail-closed bind-host default | PlainbaseConfig.kt:249-250 |
| `PLAINBASE_GIT_ENABLED` | `git.enabled` | auto-detect (`null`) | PlainbaseConfig.kt:226 |
| `PLAINBASE_GIT_AUTHOR_NAME` | `git.authorName` | `Plainbase` (:173) | PlainbaseConfig.kt:227 |
| `PLAINBASE_GIT_AUTHOR_EMAIL` | `git.authorEmail` | `plainbase@localhost` (:174) | PlainbaseConfig.kt:228 |
| `PLAINBASE_STORAGE_BACKEND` | `storage.backend` | `local` | PlainbaseConfig.kt (`local` \| `object`; `object` is not built yet and is refused at startup) |
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
warns. **`object` mode is not available in this build yet** - configuring it makes `serve`, `adopt`, and
`reindex` refuse at startup with an actionable message until the object adapter ships.

## `auth.mode` - the three modes

- **`off`** - no login, no auth. Loopback-dev only, and despite being the "no auth" mode it is
  still subject to the fail-closed bind guard (`PlainbaseConfig.kt:85-87,362`): a non-loopback
  `off` bind is refused unless a trusted proxy or `PLAINBASE_INSECURE_HTTP` override is present,
  because `off` is the **most dangerous** mode if it ever reached a public interface.
- **`builtin`** - password login; Plainbase manages its own users and sessions.
- **`proxy`** - a trusted reverse proxy asserts identity. This mode **requires both** a
  trusted-proxy CIDR (`PLAINBASE_TRUSTED_PROXY`) and `PLAINBASE_PROXY_SECRET`, or the bind guard
  refuses to start at all (`PlainbaseConfig.kt:94-97,406-410`). See
  [`deploy/reverse-proxy-sso.md`](deploy/reverse-proxy-sso.md) for a worked deployment (Caddy +
  oauth2-proxy).

## Meilisearch is not a config key

`SEARCH_ENGINE` / `MEILI_URL` do **not** exist as Plainbase configuration - grepping
`server/src/main/kotlin` for either returns zero hits. Meilisearch is an out-of-process **upgrade
tier**, never a config value; see
[When to upgrade to Meilisearch](operating-plainbase.md#when-to-upgrade-to-meilisearch) in the
operating guide. The commented `SEARCH_ENGINE`/`MEILI_URL` lines in `docker-compose.yml` are a
reserved stub for a future phase, not a live setting you can turn on today.
