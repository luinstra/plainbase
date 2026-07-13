# Connect your agent (MCP)

Plainbase ships an **in-binary MCP server** - the same single native binary that serves the web UI also
speaks the [Model Context Protocol](https://modelcontextprotocol.io) over SSE. An agent (Claude Code, the MCP
inspector, or any MCP client) connects with an app-issued `pb_` token and gets exactly **seven tools** that are
byte-for-byte the same contract as the REST API: same auth, same outcomes. Agents *propose*; humans *approve*.

## 1. Mint an agent token

Tokens are minted with the admin CLI (stop the server first - the command needs the `DATA_DIR` lock, or run it
against a separate `DATA_DIR`). Choose a mode:

- `read-only` - search + read only (no proposals).
- `propose` - read **and** open change proposals for human review (the usual agent mode).
- `commit` - direct-commits a REST write (`PUT /api/v1/pages/{id}` edit or `POST /api/v1/pages` create) whose
  page falls INSIDE `auth.agentDirectCommit.globs`, and otherwise DEGRADES to a proposal (HTTP `202`,
  `{degraded, proposal_id, status, unified_diff}`) - so a commit agent is a propose agent everywhere outside its
  allowed globs. MCP has no write tool, so MCP is propose-only
  regardless of mode. The globs are set with `auth.agentDirectCommit.globs` (or
  `PLAINBASE_AGENT_DIRECT_COMMIT_GLOBS`); the default `[]` means EVERY agent write is a proposal.

```console
$ plainbase admin mint-token my-agent propose
token id: a1b2c3d4e5f6a7b8 (label: my-agent, mode: propose)
pb_a1b2c3d4e5f6a7b8_3hVZ…<43 base64url chars>…
store this now - it is not recoverable; the server keeps only its hash
```

The second line is the **plaintext token** - copy it now; only its hash is stored. Revoke it any time with
`plainbase admin revoke-token a1b2c3d4e5f6a7b8` (revocation takes effect immediately, even on a live MCP
session - the next tool call is denied).

## 2. Point your MCP client at the SSE endpoint

| | |
|---|---|
| **Endpoint** | `https://<host>/api/v1/mcp` |
| **Transport** | SSE (the SSE GET opens the stream; the client POSTs messages back on the sessionId it's handed) |
| **Auth** | `Authorization: Bearer pb_…` on the SSE GET |

The bearer is refused over a **non-secure transport**: a `pb_` token presented over plaintext on a non-loopback
bind is `421 Misdirected Request` (it is never read over a leaky connection). Serve MCP over loopback (dev) or
behind a TLS-terminating reverse proxy.

**Reverse-proxy deployments:** the server enforces DNS-rebinding protection - by default it only accepts a
`Host`/`Origin` matching the configured bind host plus loopback. Behind a proxy, add your external host/origin:

```hocon
# DATA_DIR/plainbase.conf
auth.mcpAllowedHosts   = ["docs.example.com"]
auth.mcpAllowedOrigins = ["https://docs.example.com"]
```

(or the env equivalents `PLAINBASE_MCP_ALLOWED_HOSTS` / `PLAINBASE_MCP_ALLOWED_ORIGINS`, comma-separated.)

## 3. A worked session

Once connected, `listTools` returns exactly these seven:

`search`, `read_page`, `get_page_metadata`, `validate_links`, `propose_change`, `list_changes`, `get_change`.

A typical search → read → propose flow:

```jsonc
// search the docs - hits carry the page's root (the /docs/{root}/... URL segment)
→ search            { "q": "kubernetes deploy" }
← { "query": "...", "hits": [ { "page_id": "0197…", "root": "main", "snippet": "…", "citation": {…} }, … ] }

// read the whole verbatim page (frontmatter header + body) - content_hash is your edit base
→ read_page         { "id": "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a" }
← { "id": "0197…", "root": "main", "markdown": "---\ntitle: …\n---\n\n# …", "content_hash": "sha256:…", … }

// propose an edit - proposed_content is the FULL UTF-8 markdown of the page after your change
// (frontmatter header included), NOT a diff and NOT base64
→ propose_change    {
    "operation": "edit",
    "page_id": "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a",
    "base_hash": "sha256:…",            // the content_hash you read above
    "proposed_content": "---\ntitle: …\n---\n\n# …\n\n…your edited body…\n",
    "rationale": "fix the broken deploy command"
  }
← { "id": "0198…", "status": "PENDING", "unified_diff": "--- …\n+++ …\n@@ …" }
```

The response is a **proposal id** in `PENDING` - a human reviews and approves it in the web UI. Agents cannot
approve their own (or any) proposals. To create a NEW page instead of editing, use
`{ "operation": "create", "target_path": "notes/new.md", "proposed_content": "…", "rationale": "…" }`
(no `page_id`/`base_hash`).

Track the review queue with `list_changes` (all proposals, newest-first) and `get_change` (one proposal's full
detail + diff + decision state).

## 4. Roots: what a page lives under, and what its errors mean

Every page lives under a named **root** - a document directory the server is configured to serve
([ADR-0011](decisions/0011-multi-root-document-directories.md)). A single-root install has exactly
one, named `main`; a multi-root install can have more (`memoria`, `handbook`, whatever the operator
named them). `search` and `read_page` already carry the root in their responses (see the worked
session above); the URL grammar is always `/docs/{root}/{path}`, and a `create` (or a REST direct
commit) **names its root explicitly** - `propose_change`'s `root` field on a `create` operation (default
`main` if you omit it), or `CreatePageRequest.root` over REST.

A root can be unavailable or read-only, and the server tells you which with a code, not a guess. Three
wire shapes to recognize:

| code | status | what it means | what you must do |
|---|---|---|---|
| `root_unavailable` | **503** + `Retry-After: 300` | The root's disk is unmounted, missing at boot, or its watcher died. **The page is NOT gone.** Nothing was written. | **Keep your citations.** Retry after an operator restores the root and restarts the server - the `Retry-After` (seconds) is how long to wait before trying again. |
| `root_not_editable` | **403** | The root is declared `editable = false`. Page writes are refused there in **every** auth mode - this is topology, not a permission you might be granted. | Do not retry. Do not propose a write into this root; read-only means read-only for every agent, always. |
| `invalid_root` | **400** | The named root is not a legal slug, or names no root the server has configured. | Fix the name - check the `root` a `search`/`read_page` hit actually carries, or what `GET /healthz` lists. |

**The distinction that matters most is `root_unavailable` (503) versus a plain `404 page_not_found` -
and it is deliberate, not incidental:**

- **`404` still means exactly what it always meant: the page is gone.** Drop your citations to it.
- **`503 root_unavailable` means the opposite: the page still exists.** A disk is unmounted, not a page
  deleted, and the content is coming back once an operator restores it. **An agent that treats a 503
  as a 404 destroys citations for a page that is still there** - that is the one failure this section
  exists to prevent, because from the wire alone the two look like "I couldn't get the page" unless you
  know to look at the status code and the error code together.

Nothing is ever written on a `root_unavailable` or `root_not_editable` response, so retrying a read is
always safe; retrying a write into either is not going to change the outcome until an operator acts.

## Parity with the REST API

Every MCP tool is a thin transport adapter over the same guarded service the REST routes use - there is no
second authorization path and no divergent behavior. The six read/list/get tools return byte-identical JSON to
their REST endpoints (`GET /api/v1/search`, `/pages/{id}`, `/pages/{id}/metadata`, `/pages/{id}/validate-links`,
`/changes`, `/changes/{id}`); `propose_change` is `POST /api/v1/changes`. Every MCP tool has a REST equivalent you
can drive with the same `pb_` bearer. The reverse is not total: a few write paths are REST-only (the
`PUT /api/v1/pages/{id}` direct commit for an in-glob COMMIT token, and direct page creation), with no MCP tool -
over MCP you propose instead.
