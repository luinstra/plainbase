# Connect your agent (MCP)

Plainbase ships an **in-binary MCP server** - the same single native binary that serves the web UI also
speaks the [Model Context Protocol](https://modelcontextprotocol.io) over SSE. An agent (Claude Code, the MCP
inspector, or any MCP client) connects with an app-issued `pb_` token and gets exactly **eleven tools** over the same
guarded services as REST. Successful discussion response shapes are shared; authentication/session
and error handling retain intentional transport differences. Agents *propose* page changes for human approval;
authorized agents can write discussions directly.

## 1. Mint an agent token

Tokens are minted with the admin CLI against the target service's configured `DATA_DIR`. Stop the server first because
the command needs that `DATA_DIR` lock, then restart the same service afterward; do not use a separate `DATA_DIR`, which
would mint tokens for a different service database. Choose a mode:

- `read-only` - search and read pages, proposals, and discussions, plus REST anchor preview; no writes.
- `propose` - read, open page change proposals for human review, and directly start discussions or add comments;
  over REST, edit/retract own comments and resolve/reopen any discussion.
- `commit` - With default configuration, eligible page writes become proposals. Per-root direct-commit globs can permit
  eligible REST writes (`PUT /api/v1/pages/{id}` edit or `POST /api/v1/pages` create) to direct-commit only when the
  target root and path fall INSIDE the configured [per-root direct-commit globs](configuration.md#per-root-agent-direct-commit-globs),
  and otherwise DEGRADES to a proposal (HTTP `202`,
  `{degraded, proposal_id, status, unified_diff}`) - so a commit agent is a propose agent everywhere outside its
  allowed globs. MCP page mutations use proposals, while its discussion writes are direct for both `propose` and
  `commit` tokens, with no proposal or approval fallback. Over REST, `commit` tokens can also edit/retract
  own comments and resolve/reopen any discussion. The `globs` key and
  `PLAINBASE_AGENT_DIRECT_COMMIT_GLOBS` are **docs-root only**; grant an extra root only with
  `auth.agentDirectCommit.roots.<name>`. An empty docs list denies docs-root direct commits but does not deny
  independently granted extra-root globs.

All agent token modes are barred from discussion reattachment and purge.

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

Once connected, `listTools` returns exactly these eleven:

`search`, `read_page`, `get_page_metadata`, `validate_links`, `propose_change`, `list_changes`, `get_change`,
`list_discussions`, `get_discussion`, `start_discussion`, `add_comment`.

A typical search → read → propose flow:

```jsonc
// search the docs - hits carry the page's root (the /{root}/... URL segment)
→ search            { "q": "kubernetes deploy" }
← { "query": "...", "hits": [ { "page_id": "0197…", "root": "docs", "snippet": "…", "citation": {…} }, … ] }

// read the whole verbatim page (frontmatter header + body) - content_hash is your edit base
→ read_page         { "id": "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a" }
← { "id": "0197…", "root": "docs", "markdown": "---\ntitle: …\n---\n\n# …", "content_hash": "sha256:…", … }

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
`{ "operation": "create", "root": "docs", "target_path": "notes/new.md", "proposed_content": "…", "rationale": "…" }`
(no `page_id`/`base_hash`). A create must name its `root` - there is no default, and an omitted one is
`invalid_root`: which root a page lands in decides whose tree it joins and whose policy accepts it. An edit does
not need one - the root comes from the page - but it MAY name `root` as an optional disambiguation pin, which is
what you retry with if an edit comes back `ambiguous_page_id` (below).

Track the review queue with `list_changes` (all proposals, newest-first) and `get_change` (one proposal's full
detail + diff + decision state).

The four discussion tools use these arguments (`?` marks optional fields):

| Tool | Arguments |
|---|---|
| `list_discussions` | `{page_id?, root?, state?, cursor?, limit?}`; `page_id` selects page mode and forbids `state`; otherwise `root` is required |
| `get_discussion` | `{id, root?, cursor?, limit?}` |
| `start_discussion` | `{page_id, root?, anchor, body}` |
| `add_comment` | `{id, root?, body}` |

Discussion/comment IDs are canonical lowercase UUIDv7; `anchor` is a page or quote anchor with the
fresh page `content_hash`. Example argument forms (shortened IDs/hashes must be replaced before use):

```jsonc
→ list_discussions { "page_id": "0197…", "root": "docs", "cursor": "0198…", "limit": 20 }
→ list_discussions { "root": "docs", "state": "page_level", "cursor": "0198…", "limit": 20 }
→ get_discussion   { "id": "0198…", "root": "docs", "cursor": "0199…", "limit": 20 }
→ start_discussion { "page_id": "0197…", "root": "docs", "anchor": { "kind": "page", "content_hash": "sha256:…" }, "body": "Question" }
→ add_comment      { "id": "0198…", "root": "docs", "body": "Reply" }
```

`root` is optional for page and discussion IDs when unambiguous; select a returned candidate root
after either ambiguity code. Pinning also avoids unrelated-root uncertainty: an unpinned discussion
lookup with an unknown eligible-root claim refuses `content_unreadable` before ambiguity/not-found.
Root listing requires `root`, and `state` applies only there. Cursors are exclusive IDs from `next`; pass them back
with the same mode, root, and filter. Discussion writes take effect immediately for `propose` and `commit` tokens,
with one write audit for each request reaching the permission check; malformed arguments are rejected before auditing.
They never become proposals. A `read-only` token can list/get discussions and use REST anchor preview.
`propose`/`commit` tokens can also edit/retract their own comments and resolve/reopen any discussion
over REST; starter ownership does not constrain those agents. All agent tokens are barred from
reattach and purge, including in off mode. See [principal policy](configuration.md#discussions-support-and-authorship).

Discussion arguments are strict: reject unknown fields and invalid Unicode, supply `limit` as a JSON
number rather than a numeric string, and supply both integer block offsets or neither. Omit unused
optional fields rather than sending `null`. For an agent quote, omit offsets and submit nonempty
`selected_text` occurring exactly once in the current raw
Markdown body, excluding frontmatter. Rendered text is not a substitute; zero/repeated matches are
`anchor_not_found`/`anchor_not_unique`. Clients cannot supply author, commit or captured metadata.
Author kind/label are server-derived snapshots, and the server also derives capture metadata.
Author kind records agent authorship; anchor `narrowed`/`snapped` records source matching.
Matching mode depends on supplied block offsets. Actor keys are digest identities, not bearer secrets.
[Limits and matching modes](http-agent-workflow.md#discussion-limits) apply to
parsed MCP arguments too.

Successful list/detail/mutation contracts are [documented with REST](http-agent-workflow.md#discussion-endpoints-and-responses).
Nullable response keys are present with `null`, not omitted: for example, a history-off start/reply
still includes all three keys:

```json
{ "id": "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a", "comment_id": "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5b", "commit": null }
```

List rows carry a clipped **effective**
quote (latest reattachment or original), while detail retains full original and latest anchors
separately. Follow comment `next` windows rather than assuming the first detail contains all comments.
Failures are MCP tool results with `isError=true` and a machine code in the JSON text content, not HTTP
response statuses. Both `ambiguous_page_id` and `ambiguous_discussion_id` supply `{root,id}` candidate hints.

Start checks current source/hash before synced-count admission and the page cap, after parsing,
policy, root resolution and page-read success. On `page_changed`, read fresh source/hash, reselect
and use a new REST preview; unsynced admission is `content_unreadable`, full admission
`page_discussion_limit`. Treat network failures, any REST `5xx` on a discussion write (including
`503 content_unreadable`, which can leave residual files), equivalent MCP server failures, late `root_unavailable` and
`discussion_commit_failed`/`discussion_commit_uncertain` as requiring inspection of the current
discussion/files/Git before another action. Do not automatically replay writes. A successful post
with failed refresh remains successful; source reload alone does not establish its outcome.
See [discussion errors](http-agent-workflow.md#discussion-errors).

## 4. Roots: what a page lives under, and what its errors mean

Every page lives under a named **root** - a document directory the server is configured to serve
([ADR-0011](decisions/0011-multi-root-document-directories.md)). A single-root install has exactly
one, named `docs`; a multi-root install can have more (`memoria`, `handbook`, whatever the operator
named them). `search` and `read_page` already carry the root in their responses (see the worked
session above); the URL grammar is always `/{root}/{path}`, and a `create` (or a REST direct
commit) **names its root explicitly, with no default** - `propose_change`'s `root` field on a `create`
operation, or `CreatePageRequest.root` over REST. Omitting it is a 400 `invalid_root` (above), never
permission to write into `docs`.

A root can be unavailable or read-only, and a page id can be held by more than one root - the server tells you
which with a code, not a guess. Seven wire shapes to recognize:

| code | status | what it means | what you must do |
|---|---|---|---|
| `root_unavailable` | **503** + `Retry-After: 300` (REST) | The root's availability is unresolved: its disk may be unmounted, missing at boot, or its watcher may have died. Pre-entry refusal leaves no write; a late discussion failure can follow persistence. | **Keep your citations and provenance.** Honor the read recovery hint after an operator restores/restarts the root; inspect uncertain discussion writes before resubmitting. Do not infer physical existence or deletion. |
| `absence_unverified` | **503**; `Retry-After: 30` on some REST paths | The page is still bound, but its content absence has not been proven. Discussion source admission refuses before persistence and can omit the header. | **Keep your citations and provenance.** Retry reads after observations or an absence proof converge, honoring the header when supplied; inspect uncertain writes before another action. |
| `server_shutting_down` | **503** | The server is draining and this request was rejected before business work began. | Keep your citations and retry once an available server returns. There is no `Retry-After` promise; an admitted write follows the shutdown drain instead. |
| `root_not_editable` | **403** | The root is declared `editable = false`. Page writes are refused there in **every** auth mode - this is topology, not a permission you might be granted. | Do not retry. Do not propose a write into this root; read-only means read-only for every agent, always. |
| `invalid_root` | **400** (REST); MCP tool error | Malformed/repeated REST root pin, or an unknown root for MCP/discussions. Ordinary REST page reads use `404 page_not_found` for a legal unknown pin. | Fix the name - check the `root` a `search`/`read_page` hit actually carries, or what `GET /healthz` lists. |
| `ambiguous_page_id` | **409** (REST); MCP tool error | The page id you sent is held by more than one root and you named none, so the server will not pick one for you. | Name a known `root`. Ordinary REST page reads include candidate retry URLs; proposal candidates omit URLs because their pin is in the body. Discussion REST errors omit candidates for both ambiguity codes. MCP supplies `{root,id}` hints, with no URL. |
| `ambiguous_discussion_id` | **409** (REST); MCP tool error | The discussion id you sent is held by more than one root and you named none. | Retry with a known root. Discussion REST refuses with its existing plain `{ "error": { "code", "message" } }` envelope and no candidate list; MCP supplies `{root,id}` candidate hints for a retry with the `root` argument. |

The id-addressed **read** tools - `read_page`, `get_page_metadata` and `validate_links` - accept the same optional
`root` pin, which is what makes the `ambiguous_page_id` remedy above actually available on a read. Omit it and the
server resolves the owning root from the id; name it and you get that root's page or nothing.

A `404 page_not_found` means only that the page is unavailable in the requested visible scope. A
root pin narrows that scope, and hidden or excluded content can also be 404 even when a physical file
remains. Do not infer physical deletion or erase historical citations/provenance from this response;
mark the source unverified and reconcile against a known root as appropriate. A `503 root_unavailable`
or `503 absence_unverified` means availability is unresolved, not proven existence or deletion:
**KEEP citations and provenance**, honor REST `Retry-After` when supplied for reads after recovery/convergence,
and inspect uncertain discussion writes before resubmitting.
For a pinned `404 page_not_found`, retry without the root pin or with another known root before treating the page as absent.

Root rejection before entry leaves no write; `root_not_editable` remains a policy refusal. A late
discussion `root_unavailable` can follow persistence, irrespective of generic error wording.
Recovery headers do not authorize blind write replay, and MCP tool failures carry no HTTP retry header.

## Parity with the REST API

Every MCP tool is a thin transport adapter over the same guarded facades the REST routes use, with policy rechecked per
call. The six original read/list/get tools and two discussion reads have shared successful JSON contracts with their REST
endpoints; authentication, session and error envelopes remain transport-specific. `propose_change` is
`POST /api/v1/changes`; `start_discussion` and `add_comment` use the discussion REST facades. Separately executed
proposal creates mint independent IDs even though their response structure is shared. Every MCP tool has a REST equivalent you
can drive with the same `pb_` bearer. REST also offers direct page edit (`PUT /api/v1/pages/{id}` for an in-glob
COMMIT token) and creation (`POST /api/v1/pages`); over MCP, you propose page changes instead. The following
discussion endpoints are REST-only:

| Operation | Endpoint |
|---|---|
| Edit comment | `POST /api/v1/discussions/{id}/comments/{commentId}/edit` |
| Retract comment | `POST /api/v1/discussions/{id}/comments/{commentId}/retract` |
| Resolve discussion | `POST /api/v1/discussions/{id}/resolve` |
| Reopen discussion | `POST /api/v1/discussions/{id}/reopen` |
| Reattach discussion | `POST /api/v1/discussions/{id}/reattach` |
| Purge comment | `POST /api/v1/discussions/{id}/comments/{commentId}/purge` |
| Preview anchor | `POST /api/v1/pages/{id}/discussions/anchor-preview` |

Anchor preview does not mutate anything, despite using POST, and is allowed for `read-only` agents
on supported roots. Preview and all lifecycle operations remain REST-only; there are no extra MCP
tools for them. Use [the endpoint/request reference](http-agent-workflow.md#discussion-endpoints-and-responses).

For new knowledge documents, follow the [OKF author guide](okf-documents.md). REST creation accepts
optional `type` (for example, `"Reference"`); see the [creation contract](http-api.md#page-creation).
Explicit MCP/REST create proposals keep author-supplied Markdown: include type frontmatter going
forward. Headerless proposals remain accepted; the server adds identity without adding a type.

For the REST document URL matrix, opt-in Markdown representation, source-byte semantics, and the JSON `ETag` write
base-hash rule, see the [HTTP API reference](http-api.md). MCP's `read_page` remains the structured JSON read contract.

For a direct curl + jq REST workflow without an MCP client, see [Use Plainbase from an HTTP agent](http-agent-workflow.md).

See the [transport differences table](backend-architecture.md#rest-and-mcp-ownership) for the intentional error distinctions.
