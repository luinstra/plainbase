<picture>
  <source media="(prefers-color-scheme: dark)" srcset="assets/brand/plainbase-logo-dark.svg" />
  <img src="assets/brand/plainbase-logo.svg" alt="Plainbase" width="450" />
</picture>

_Internal docs humans enjoy and agents can actually work with._

[![Coverage Status](https://coveralls.io/repos/github/luinstra/plainbase/badge.svg)](https://coveralls.io/github/luinstra/plainbase)

Plainbase is a self-hosted docs workspace built AI-native from the first
commit: the same single binary that serves the web UI is an
[MCP server](docs/connect-your-agent.md) your agents connect to directly.
Agents search, read, and **propose** changes; humans review and approve them
in a built-in queue. Your content never stops being a plain tree of Markdown
files.

## Why Plainbase

- **Agent-native, not agent-bolted-on.** In-binary MCP over SSE with scoped,
  revocable tokens - seven tools, byte-identical to the REST API. Connect
  Claude Code (or any MCP client) to your team's docs in minutes.
- **Humans stay in charge.** Agents open change proposals with diffs and
  rationale; nothing lands without a human approving it in the review UI.
- **No lock-in, structurally.** Your docs are a plain tree you can always walk
  away with, and Plainbase never keeps a second copy of them. Local deploy: the
  `CONTENT_DIR` directory IS the authority - or, with a `roots {}` block, *every*
  configured root's directory is (each is a plain tree in its own right, and each
  one is content you back up) - manage them with `plainbase root add/remove/list`
  (see [Configuration](docs/configuration.md#the-cli-and-the-two-files)). Cloud
  deploy: an S3-compatible bucket IS the authority (a plain tree of objects any
  S3 tool can read). Git is an optional layer, every index is derived and
  rebuildable. Leaving Plainbase is copying those directories or syncing the
  bucket.
- **One binary, no fleet.** A single native executable (no JRE, no database
  server, no Node) with embedded SQLite + FTS5 search and sub-second cold
  start. `docker compose up` if you'd rather run a container.

## Quickstart (single binary)

Grab a binary from the [latest release](https://github.com/luinstra/plainbase/releases/latest)
(`linux-x64`, `linux-arm64`, `macos-arm64`, or the universal JAR for supported
Linux/macOS environments with Java 21+):

```sh
curl -L -o plainbase https://github.com/luinstra/plainbase/releases/latest/download/plainbase-macos-arm64
chmod +x plainbase
./plainbase serve   # CONTENT_DIR=./content DATA_DIR=./data by default
```

Open http://localhost:8080. Point `CONTENT_DIR` at an existing Markdown tree
to adopt it as-is.

### Windows via WSL2

Native Windows and direct Windows JVM operation are intentionally deferred. On a Windows host, run
the `linux-x64` binary under WSL2 and keep both `CONTENT_DIR` and `DATA_DIR` in the distribution's
Linux filesystem (for example, under `/home/<user>/plainbase`), not under `/mnt/c`, `/mnt/d`, or
another Windows-mounted drive. Windows-backed mounts retain cross-filesystem differences in
permissions, symlinks, file identity, watchers, and performance.

The same boundary applies to Docker Desktop: run Compose from a checkout in the WSL distribution and
bind-mount content from that Linux filesystem rather than from a Windows drive. See
[Operating Plainbase](docs/operating-plainbase.md#platform-note---the-5-second-promise-binds-linux)
for the supported layout.

## Quickstart (Docker Compose)

```sh
git clone https://github.com/luinstra/plainbase && cd plainbase
docker compose up --build
```

Serves the bundled demo docs (`fixtures/demo-docs`); point the bind mount in
`docker-compose.yml` at your own tree. Search is embedded SQLite FTS5 - no
extra containers needed.

## Connect your agent

```console
$ plainbase admin mint-token my-agent propose
pb_a1b2c3d4e5f6a7b8_3hVZ…
```

Point any MCP client at `https://<host>/api/v1/mcp` (SSE) with that bearer
and it gets `search`, `read_page`, `get_page_metadata`, `validate_links`,
`propose_change`, `list_changes`, `get_change`. Reads return the raw
Markdown, proposals come back as unified diffs in the human review queue,
and revocation takes effect mid-session. The full worked session, token
modes (`read-only` / `propose` / `commit`), and reverse-proxy notes:
[Connect your agent (MCP)](docs/connect-your-agent.md).

## Configuration

| Env var | Default | Meaning |
|---|---|---|
| `CONTENT_DIR` | `./content` | Canonical, user-owned Markdown tree (local mode). Plainbase only writes here on explicit save/approve; ignored in object mode, where the bucket is canonical. |
| `DATA_DIR` | `./data` | App-owned state: SQLite DB, config, caches, search index. |
| `PLAINBASE_HOST` | `127.0.0.1` | Bind address. |
| `PLAINBASE_PORT` | `8080` | HTTP port. |
| `PLAINBASE_LOG_LEVEL` | `INFO` | Root log level (`ERROR`/`WARN`/`INFO`/`DEBUG`). |

Full reference: [Configuration](docs/configuration.md) (every key, the three
`auth.mode`s, the proxy/MCP CIDR nuance).

Native binaries and local JVM distributions emit readable operational logs on stderr. The container
image emits one JSON object per operational log line for Docker/Kubernetes collection. Command results
(including one-time tokens) remain exact stdout payloads; usage and refusal text remains stderr and is
not routed through the logger.

## Your data (hard rule)

- `CONTENT_DIR` - canonical, portable, user-owned (local mode). Reinstall
  Plainbase anywhere against the same tree and nothing is lost.
- Object mode (`storage.backend=object`) - the S3-compatible **bucket** is
  canonical instead; `CONTENT_DIR` is ignored and `DATA_DIR/mirror` is a
  derived, deletable cache. One authority per deployment, never two.
- `DATA_DIR` - app-owned workflow/security state. Never canonical content.
- Search indexes - fully derived; delete them any time and rebuild.

## Docs

- [Configuration](docs/configuration.md) - the full environment-variable reference.
- [Operating Plainbase](docs/operating-plainbase.md) - search freshness, manual reindex, adopting
  an existing repo, backups, performance + the startup gate, known limitations.
- [Object-storage backend](docs/deploy/object-storage.md) - serve an S3-compatible bucket (R2-first)
  as the authority: setup, IAM, migration, platform support.
- [Connect your agent (MCP)](docs/connect-your-agent.md) - mint a token, point an MCP client at the
  server, a worked search → read → propose session.
- [Design summary](docs/DESIGN_SUMMARY.md) - architecture & product framing.
- [Development](docs/DEVELOPMENT.md) - building, the CI gates, the native dependency spike,
  architecture rules.

## Contributing & development

`./gradlew build` is the verification floor; the interesting constraints
(GraalVM native gate, dependency policy) live in
[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md). Contribution mechanics, including
the required DCO sign-off: [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[Apache-2.0](LICENSE). The Plainbase name and logo are covered by the
[trademark policy](TRADEMARK.md).
