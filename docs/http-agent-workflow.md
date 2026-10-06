# Use Plainbase from an HTTP agent

Run the examples in Bash (arrays require Bash). It needs `curl` and `jq`, not an MCP client. Use a
trusted HTTPS URL, or loopback HTTP for local development, and keep the bearer in `PLAINBASE_TOKEN`.
Tokens work in every Plainbase auth mode, but a `pb_` credential over insecure non-loopback
HTTP is refused with `421`. See the [deployment setup](deploy/reverse-proxy-sso.md) for TLS
termination and proxy mode.

## Choose a token and prepare the shell

An administrator mints and revokes tokens with the [MCP token instructions](connect-your-agent.md#1-mint-an-agent-token).
The modes are `read-only` (search and read), `propose` (read and queue changes for human review,
the usual choice), and `commit` (eligible REST writes may commit within configured globs). The
token is shown only once. For discussions, `propose` and `commit` both write directly, without page
proposal fallback or path-glob gating; `read-only` permits discussion reads and anchor preview.
Mint or revoke it against the target service's configured `DATA_DIR`:
stop the server for its lock, run the command, then restart it; a separate `DATA_DIR` would mint
tokens for a different service database.

```bash
set -eu
BASE_URL="${BASE_URL:-https://docs.example.com}"
BASE_URL="${BASE_URL%/}"
PLAINBASE_TOKEN="${PLAINBASE_TOKEN:?export PLAINBASE_TOKEN with the one-time token value}"
case "$PLAINBASE_TOKEN" in
  pb_*) ;;
  *) printf '%s\n' 'PLAINBASE_TOKEN must be a pb_ token' >&2; exit 2 ;;
esac
case "$PLAINBASE_TOKEN" in
  *[!A-Za-z0-9_-]*|'') printf '%s\n' 'PLAINBASE_TOKEN contains unsafe characters' >&2; exit 2 ;;
esac

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/plainbase-http.XXXXXX")"
AUTH_CONFIG="$WORK_DIR/curl-auth.conf"
printf 'header = "Authorization: Bearer %s"\n' "$PLAINBASE_TOKEN" > "$AUTH_CONFIG"
chmod 600 "$AUTH_CONFIG"
AUTH=(--config "$AUTH_CONFIG")
```

The token alphabet check makes the quoted curl-config line safe. Keep this private scratch directory while working.

## Search, read, propose, and track

Search uses only `q`, `limit`, and `offset`. Print the real hits, then copy one hit's matching
`page_id` and `root`; do not invent an id or assume that the first result is the page you want.

```bash
SEARCH_JSON="$WORK_DIR/search.json"
curl --fail-with-body --silent --show-error --get "$BASE_URL/api/v1/search" \
  "${AUTH[@]}" \
  --data-urlencode 'q=deployment' \
  --data-urlencode 'limit=20' \
  --data-urlencode 'offset=0' \
  --output "$SEARCH_JSON"
jq -e '.hits | length > 0' "$SEARCH_JSON" >/dev/null || {
  printf '%s\n' 'Search returned no hits.' >&2
  exit 1
}
jq -r '.hits[] | [.page_id, .root, .snippet] | @tsv' "$SEARCH_JSON"
```

Copy the selected hit into `PAGE_ID` and `ROOT` before running the next block.

```bash
PAGE_ID='paste-page-id-from-selected-hit'
ROOT='paste-root-from-selected-hit'
jq -e --arg id "$PAGE_ID" --arg root "$ROOT" \
  'any(.hits[]; .page_id == $id and .root == $root)' "$SEARCH_JSON" >/dev/null || {
  printf '%s\n' 'PAGE_ID and ROOT must match one printed search hit.' >&2
  exit 1
}
PAGE_JSON="$WORK_DIR/page.json"
curl --fail-with-body --silent --show-error --get "$BASE_URL/api/v1/pages/$PAGE_ID" \
  "${AUTH[@]}" \
  --data-urlencode "root=$ROOT" \
  --header 'Accept: application/json' \
  --output "$PAGE_JSON"

# Use fresh top-level content_hash and matching markdown; never citation.content_hash, a snippet, or a local rehash.
jq -e --arg id "$PAGE_ID" --arg root "$ROOT" \
  '(.id == $id) and (.root == $root) and (.content_hash | type == "string") and (.markdown | type == "string")' \
  "$PAGE_JSON" >/dev/null || {
  printf '%s\n' 'Page JSON did not match the selected id/root or lacks source/hash.' >&2
  exit 1
}
BASE_HASH="$(jq -er '.content_hash // error("missing top-level content_hash")' "$PAGE_JSON")" || {
  printf '%s\n' 'Page JSON lacks top-level content_hash.' >&2
  exit 1
}
ORIGINAL_MD="$WORK_DIR/original.md"
EDITED_MD="$WORK_DIR/edited.md"
jq -j '.markdown' "$PAGE_JSON" > "$ORIGINAL_MD"
cp "$ORIGINAL_MD" "$EDITED_MD"

# Edit this file in place. Keep all frontmatter, including the existing identity, and change only
# the source you intend to propose. jq -j above preserves the source's final-newline state.
"${EDITOR:-vi}" "$EDITED_MD"
RATIONALE='Clarify the deployment recovery steps.'
PROPOSAL_REQUEST="$WORK_DIR/proposal.json"
jq -n \
  --arg operation edit \
  --arg root "$ROOT" \
  --arg page_id "$PAGE_ID" \
  --arg base_hash "$BASE_HASH" \
  --rawfile proposed_content "$EDITED_MD" \
  --arg rationale "$RATIONALE" \
  '{operation: $operation, page_id: $page_id, root: $root, base_hash: $base_hash,
    proposed_content: $proposed_content, rationale: $rationale}' > "$PROPOSAL_REQUEST"

PROPOSAL_JSON="$WORK_DIR/proposal-response.json"
curl --fail-with-body --silent --show-error --request POST "$BASE_URL/api/v1/changes" \
  "${AUTH[@]}" \
  --header 'Content-Type: application/json' \
  --data-binary "@$PROPOSAL_REQUEST" \
  --output "$PROPOSAL_JSON"
jq -e '(.id | type == "string") and .status == "PENDING" and (.unified_diff | type == "string")' \
  "$PROPOSAL_JSON" >/dev/null || {
  printf '%s\n' 'Proposal response lacks id, PENDING status, or unified_diff.' >&2
  exit 1
}
CHANGE_ID="$(jq -er '.id' "$PROPOSAL_JSON")" || {
  printf '%s\n' 'Could not read proposal id.' >&2
  exit 1
}
DETAIL_JSON="$WORK_DIR/change-detail.json"
LIST_JSON="$WORK_DIR/change-list.json"
curl --fail-with-body --silent --show-error "$BASE_URL/api/v1/changes/$CHANGE_ID" \
  "${AUTH[@]}" --output "$DETAIL_JSON"
curl --fail-with-body --silent --show-error "$BASE_URL/api/v1/changes" \
  "${AUTH[@]}" --output "$LIST_JSON"
jq -e '.proposals | type == "array"' "$LIST_JSON" >/dev/null || {
  printf '%s\n' 'Change-list response lacks the proposals wrapper.' >&2
  exit 1
}
```

`POST /api/v1/changes` accepts the full replacement source, not a patch or base64. A successful
response is `201` with fields `id`, `status`, and `unified_diff`; `status: "PENDING"` queues review; it
does not apply bytes. `GET /api/v1/changes/$CHANGE_ID` returns detail fields such as `base_hash`,
`base_drifted`, `rationale`, `unified_diff`, and human decision fields. `GET /api/v1/changes`
returns the `proposals` wrapper and currently returns all proposals; do not assume pagination,
status filtering, notifications, or a fixed polling guarantee. Statuses are `PENDING`, `APPLYING`,
`APPLIED`, `REJECTED`, `CONFLICTED`, and `FAILED`.

To propose a new page, use an explicit root and a root-relative `.md` target. The server assigns
the page identity; omit `page_id` and `base_hash`. Include type frontmatter for new knowledge documents
as described in the [OKF author guide](okf-documents.md); the minimal type-only header below is sufficient.
Explicit proposals preserve author-supplied source, and headerless legacy proposals remain accepted.

```bash
CREATE_MD="$WORK_DIR/new-page.md"
printf '%s\n' '---' 'type: Reference' '---' '' '# New page' '' 'Created by an HTTP agent.' > "$CREATE_MD"
CREATE_REQUEST="$WORK_DIR/create.json"
jq -n --arg root "$ROOT" --arg target_path 'notes/new-page.md' \
  --rawfile proposed_content "$CREATE_MD" \
  '{operation: "create", root: $root, target_path: $target_path,
    proposed_content: $proposed_content, rationale: "Add the new runbook page."}' > "$CREATE_REQUEST"
curl --fail-with-body --silent --show-error --request POST "$BASE_URL/api/v1/changes" \
  "${AUTH[@]}" --header 'Content-Type: application/json' \
  --data-binary "@$CREATE_REQUEST" --output "$WORK_DIR/create-response.json"
```

## Read the source as Markdown

For a read-only source download, request Markdown explicitly from the pinned API URL. It returns
the full indexed source, has no ETag, and leaves errors as structured JSON. For document URL
redirects, permalinks, and invalid-UTF-8 replacement behavior, use the [HTTP API reference](http-api.md).
For editing, read JSON first so the source and `content_hash`/ETag come from the same response.

```bash
MARKDOWN_SOURCE="$WORK_DIR/page-markdown.md"
curl --fail-with-body --silent --show-error --get "$BASE_URL/api/v1/pages/$PAGE_ID" \
  "${AUTH[@]}" --data-urlencode "root=$ROOT" \
  --header 'Accept: text/markdown' \
  --output "$MARKDOWN_SOURCE"
```

## Discussions: read, preview, start and reply

Discussions are available on editable local roots. Lists and detail pinned to a read-only/object-mode
root return disabled metadata; preview and mutations refuse there. Unpinned discussion-ID lookup scans
only editable local roots, so an ID present only in an unsupported root can return `404 discussion_not_found`.
Pin that root for disabled detail or an ID mutation's topology refusal.
Follow the [principal policy](configuration.md#discussions-support-and-authorship).
Agents can edit/retract their own comments and resolve/reopen any discussion; they cannot purge or
reattach. The server derives author kind/label snapshots and capture metadata. Author kind records agent
authorship; anchor `narrowed`/`snapped` records source matching. Matching mode depends on supplied
block offsets, not author kind.

Using the selected `PAGE_ID`, `ROOT`, private `WORK_DIR` and `AUTH` array above, read existing
discussions and fetch fresh JSON source/hash together. Select one unique verbatim passage from the
raw Markdown **body**, excluding frontmatter; rendered text and snippets are not substitutes.
Save exactly those characters in `quote.txt` (an added final newline also changes the quote).

```bash
ROOT_QUERY="$(jq -rn --arg root "$ROOT" '$root | @uri')"
curl --fail-with-body --silent --show-error --get "$BASE_URL/api/v1/pages/$PAGE_ID/discussions" \
  "${AUTH[@]}" --data-urlencode "root=$ROOT" --output "$WORK_DIR/discussions.json"
curl --fail-with-body --silent --show-error --get "$BASE_URL/api/v1/pages/$PAGE_ID" \
  "${AUTH[@]}" --data-urlencode "root=$ROOT" --header 'Accept: application/json' \
  --output "$WORK_DIR/discussion-source.json"
jq -e --arg id "$PAGE_ID" --arg root "$ROOT" \
  '(.id == $id) and (.root == $root) and (.content_hash | type == "string") and (.markdown | type == "string")' \
  "$WORK_DIR/discussion-source.json" >/dev/null
DISCUSSION_HASH="$(jq -er '.content_hash' "$WORK_DIR/discussion-source.json")"
jq -j '.markdown' "$WORK_DIR/discussion-source.json" > "$WORK_DIR/discussion-source.md"

# Create quote.txt from that source, and starter.md with the comment you intend to post.
"${EDITOR:-vi}" "$WORK_DIR/quote.txt" "$WORK_DIR/starter.md"
jq -n --arg hash "$DISCUSSION_HASH" --rawfile quote "$WORK_DIR/quote.txt" \
  '{kind: "quote", content_hash: $hash, selected_text: $quote}' > "$WORK_DIR/anchor.json"

# Preview takes the quote object DIRECTLY, with no anchor wrapper.
curl --fail-with-body --silent --show-error --request POST \
  "$BASE_URL/api/v1/pages/$PAGE_ID/discussions/anchor-preview?root=$ROOT_QUERY" \
  "${AUTH[@]}" --header 'Content-Type: application/json' \
  --data-binary "@$WORK_DIR/anchor.json" --output "$WORK_DIR/preview.json"
jq '{content_hash, byte_start, byte_end, selection, quote_text}' "$WORK_DIR/preview.json"
```

Inspect the preview before continuing. Start wraps that same anchor with the decoded Markdown body;
preview does not reserve the source, so a later `page_changed` requires a fresh read, reselection and preview.

```bash
jq -n --slurpfile anchor "$WORK_DIR/anchor.json" --rawfile body "$WORK_DIR/starter.md" \
  '{anchor: $anchor[0], body: $body}' > "$WORK_DIR/start.json"
curl --fail-with-body --silent --show-error --request POST \
  "$BASE_URL/api/v1/pages/$PAGE_ID/discussions?root=$ROOT_QUERY" \
  "${AUTH[@]}" --header 'Content-Type: application/json' \
  --data-binary "@$WORK_DIR/start.json" --output "$WORK_DIR/started.json"
DISCUSSION_ID="$(jq -er '.id' "$WORK_DIR/started.json")"

# Read the discussion before composing a reply; follow next for later comments.
curl --fail-with-body --silent --show-error --get "$BASE_URL/api/v1/discussions/$DISCUSSION_ID" \
  "${AUTH[@]}" --data-urlencode "root=$ROOT" --output "$WORK_DIR/thread.json"
"${EDITOR:-vi}" "$WORK_DIR/reply.md"
jq -n --rawfile body "$WORK_DIR/reply.md" '{body: $body}' > "$WORK_DIR/reply.json"
curl --fail-with-body --silent --show-error --request POST \
  "$BASE_URL/api/v1/discussions/$DISCUSSION_ID/comments?root=$ROOT_QUERY" \
  "${AUTH[@]}" --header 'Content-Type: application/json' \
  --data-binary "@$WORK_DIR/reply.json" --output "$WORK_DIR/replied.json"
```

For a whole-page discussion use `{ "kind": "page", "content_hash": "sha256:<64 lowercase hex digits>" }`
as the start anchor. Preview accepts only quote anchors. In **no-offset mode**, omit both
`block_start` and `block_end`: the nonempty selected UTF-8 sequence must occur exactly once in the
current body. Zero matches is `422 anchor_not_found`; repeated matches is `422 anchor_not_unique`.

In **SPA block-offset mode**, supply both JSON integer offsets from server-rendered block boundaries:
`{ "kind": "quote", "content_hash": "sha256:<64 lowercase hex digits>", "selected_text": "verbatim Markdown",
"block_start": 123, "block_end": 456 }`. Offsets are absolute half-open UTF-8 byte ranges in the
full file, including frontmatter/BOM, but the span must lie in its Markdown body. Only selected text
occurring exactly once within that span narrows the capture. Empty selected text, text absent from
the span, or repeated text snaps to the whole block span. Oversize captures are refused,
never truncated. Clients cannot submit author, captured context, commit, line or heading fields;
the server derives them. Unknown JSON fields and invalid Unicode are rejected.

### Discussion endpoints and responses

`{id}` is a page ID under `/pages` and a discussion ID under `/discussions`; `{commentId}` is a
comment ID. Pin `root` to avoid cross-root ambiguity and unrelated-root lookup uncertainty. It is
optional on page/ID routes and required for the root inbox. Every POST requires
`Content-Type: application/json`, including preview and empty lifecycle requests.

| Method and path | Request | Success |
|---|---|---|
| GET `/api/v1/pages/{id}/discussions` | `root?`, `cursor?`, `limit?`; no `state` | List, `200` |
| POST `/api/v1/pages/{id}/discussions/anchor-preview` | Quote object directly | Preview, `200` |
| POST `/api/v1/pages/{id}/discussions` | `{anchor, body}` | Mutation, `201` |
| GET `/api/v1/discussions` | Required `root`; `state?`, `cursor?`, `limit?` | List, `200` |
| GET `/api/v1/discussions/{id}` | `root?`, `cursor?`, `limit?` | Detail, `200` |
| POST `/api/v1/discussions/{id}/comments` | `{body}` | Mutation, `201` |
| POST `/api/v1/discussions/{id}/comments/{commentId}/edit` | `{body}` | Mutation, `200` |
| POST `/api/v1/discussions/{id}/comments/{commentId}/retract` | Empty body or `{}` | Mutation, `200` |
| POST `/api/v1/discussions/{id}/comments/{commentId}/purge` | Empty body or `{}` | Mutation, `200` |
| POST `/api/v1/discussions/{id}/resolve` | Empty body or `{}` | Mutation, `200` |
| POST `/api/v1/discussions/{id}/reopen` | Empty body or `{}` | Mutation, `200` |
| POST `/api/v1/discussions/{id}/reattach` | `{anchor: <quote>}`; human policy only | Mutation, `200` |

List is `{discussions, next, discussions_available, reason}`. Each row contains
`{id, page, status, state, reason, range, candidates, placement, quote, comment_count, starter, created, updated}`;
`page` is `{id, path, resolution}`. Lifecycle `status` (`open`/`resolved`) differs from anchor `state`.
Root `state` filters accept `page_level`, `exact`, `moved`, `ambiguous`, `changed`, `orphaned`,
`unavailable`, `unreadable`, `incomplete`. State and clipped quote previews reflect the effective
anchor: latest reattachment when present, otherwise the original.

Detail is `{discussion, comments, next, discussions_available, reason}`. Its `discussion` adds
full immutable `anchor` and separate latest `reattachment` (including `by`, `at`, `anchor`); its starter
label is full rather than clipped. Comment is `{id, author, created, edited_at, retracted, html, markdown}`;
author/starter is `{key, kind, label}`. The key is a digest identity, not a bearer secret. Nullable
keys remain present with `null`, including unavailable detail's `discussion`; malformed/incomplete
records do not masquerade as healthy threads. For example, detail pinned to a read-only root is:

```json
{ "discussion": null, "comments": [], "next": null, "discussions_available": false, "reason": "read_only_root" }
```

Preview is `{content_hash, byte_start, byte_end, selection, quote_text}`. Mutation is always
`{id, comment_id, commit}`: start/reply return the new comment ID; every other mutation (comment
edit/retract/purge, resolve/reopen and reattach) returns `comment_id: null`. `commit: null` is possible
with history off. An illustrative lifecycle response is:

```json
{ "id": "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a", "comment_id": null, "commit": null }
```

Cursors are exclusive canonical lowercase UUIDv7 IDs: discussion IDs for lists, comment IDs for
detail. Pass `next` back with the same root/mode/filter until it is null, even for short or empty
windows. Root inboxes project at most four times the requested limit before filtering, so they can
return fewer matches with `next`. This budget bounds summary projections, not total traversal:
unsynced reads can enumerate the collection or scan files before selecting a window. There is no
constant-work or response-time guarantee.

### Discussion limits

These bounds apply to REST and shared MCP arguments/results. Fixed limits are distinct from the
existing configurable `maxWriteBodyBytes`, which can further reduce the JSON envelope cap.

| Surface | Limit and behavior |
|---|---|
| JSON body/argument envelope | 524,288 UTF-8 bytes (512 KiB) or `maxWriteBodyBytes`, whichever is smaller; oversize is `413 body_too_large` over HTTP |
| Decoded Markdown comment body | 65,536 UTF-8 bytes (64 KiB); blank is `422 comment_empty`, oversize is `422 comment_too_large` |
| Serialized comment file, including frontmatter | 73,728 bytes (72 KiB); oversize encoding is `422 discussion_too_large` even if decoded body fits |
| Serialized marker file, including frontmatter | 524,288 bytes (512 KiB); oversize encoding is `422 discussion_too_large` |
| Captured quote | 16,384 UTF-8 bytes (16 KiB); oversize is `422 anchor_too_large`, never truncation |
| Stored prefix and suffix | Each at most 64 UTF-8 bytes, clipped at codepoint boundaries |
| Ambiguous match candidates | At most 20 projected locations |
| List starter label and effective quote | Each clipped to 256 UTF-8 bytes at codepoint boundaries; detail retains full evidence separately |
| Page discussion list | Default/maximum `limit=200` |
| Root inbox | Default/maximum `limit=50` |
| Detail comments | Default/maximum `limit=50`; follow `next` for later comments |
| Existing discussions per page (start admission) | 200, including resolved discussions; full admission is `409 page_discussion_limit`; unsynced count refuses creation |
| Present canonical comments per discussion | 1,000, including retracted comments; adding at capacity is `409 discussion_full`; purge removes a present entry |

### Discussion errors

After transport parsing, policy, root resolution and page-read success, start admission checks the
current-source hash first (`409 page_changed`), then synced count (`503 content_unreadable` if
unsynced), then the page cap (`409 page_discussion_limit`). The first applicable admission failure
wins before start writes; earlier page-read failures still take precedence. Common refusals include:

| Response | Meaning and action |
|---|---|
| `415 unsupported_media_type` | All discussion POSTs need `application/json` |
| `401 unauthorized` | Enforced auth requires credentials; an unresolved Plainbase bearer is also refused in off mode. Supply valid credentials |
| `403 forbidden` | The authenticated principal lacks token, role or ownership permission; follow the principal policy |
| `403 root_not_editable` / `discussions_unsupported` | Preview/mutations refuse read-only/object-mode roots; read-only takes precedence. Pin the root for an ID mutation's topology refusal |
| `400 invalid_page_id` | Malformed page path ID; supply a canonical page UUID |
| `400 invalid_utf8` / `invalid_request_body` | Invalid raw UTF-8/Unicode, malformed JSON, unsupported shape/fields or invalid anchor grammar. Malformed discussion/comment path IDs are `invalid_request_body`; correct the request |
| `400 invalid_root` / `invalid_query` | Malformed/repeated `root` is checked before discussion query validation; an unknown root is also `invalid_root`. Repeated query values, invalid cursors/limits or disallowed combinations are `invalid_query`. REST limits are unsigned decimals in `1..maximum`; mutations/preview disallow `state`, `cursor`, `limit` |
| `409 ambiguous_page_id` / `ambiguous_discussion_id` | Name a known `root`. Discussion REST errors have plain `{error: {code, message}}` with no candidates; MCP includes `{root,id}` candidate hints for both codes. An unpinned discussion lookup with an unknown eligible-root claim can refuse `503 content_unreadable` before ambiguity/not-found |
| `404 page_not_found` | Page unavailable in the requested scope or confirmed absent at source admission; retain citations/provenance rather than inferring physical deletion |
| `409 page_changed` | Start/preview/reattach source is stale: fetch fresh source/hash, reselect and obtain a new preview |
| `422 anchor_not_found` / `anchor_not_unique` / `anchor_too_large` / `invalid_anchor` | Correct the selection using the matching modes above; oversize quotes are not clipped |
| `413 body_too_large` / `422 comment_too_large` / `discussion_too_large` | JSON envelope, decoded body and serialized files are separate caps; use the limits table. `422 comment_empty` refuses blank text |
| `409 stale_discussion` / `discussion_changed` | Store version mismatch, or changed author/starter facts relied on for authorization; refresh and inspect before a new action |
| `409 discussion_resolved` / `discussion_full` | Resolved discussions reject reply/reattach; capacity rejects another comment |
| `409 comment_retracted` / `already_resolved` / `already_open` | The target is already in that state; inspect it instead of replaying |
| `422 reattach_page_level` | Only an original quote anchor can be reattached, under human policy |
| `409 discussion_unreadable` / `404 discussion_not_found` / `comment_not_found` | Ordinary mutations refuse unreadable facts; incomplete/missing discussion facts and missing targets refuse. See the purge exception below |
| `422 discussion_path_refused` | The store refused the target path; inspect safe files/permissions |
| `503 content_unreadable` | File/index recovery needed; a store failure can leave residual files. Inspect failed writes; `Retry-After: 30` is a hint, not a deadline or write-replay permission |
| `503 absence_unverified` | Page absence is not proven. Retry reads as observations converge, honoring `Retry-After: 30` when supplied; discussion source admission can omit it. Do not infer deletion or replay writes |
| `503 root_unavailable` | `Retry-After: 300`; root availability stays unavailable until operator restoration/restart. A late failure can follow persistence |
| `503 discussion_commit_failed` / `discussion_commit_uncertain` | No `Retry-After`. Known failure attempts undo, which can fail; unknown outcome leaves files in place. Inspect files/thread/Git before another action |
| `503 server_shutting_down` | Pre-work shutdown rejection; no `Retry-After` promise |

Authorized comment purge bypasses the ordinary unreadable/incomplete-facts refusal. It can remove
a target with syntax-malformed but present/readable marker bytes, subject to policy, root eligibility
and safe marker/target reads, without decoding marker syntax. Missing marker is still
`404 discussion_not_found`, missing target `404 comment_not_found`; read failures can be
`503 content_unreadable`, and symlink/too-many-entry reads refuse `409 discussion_unreadable`.
Purge changes only the target comment, never repairs the marker or erases Git/backups.

A root refusal before entry leaves no write, but network loss, late root failure or any `5xx` on a
discussion write requires inspection, including `503 content_unreadable` after a store failure that
can leave residual files. Do not automatically replay start/comment/lifecycle requests;
inspect the current discussion, later comment windows, files and Git first. A successful write followed
by a failed refresh is still successful. Source reload refreshes quote evidence, not the prior write's
outcome. See [operator recovery](operating-plainbase.md#uncertain-writes-and-residual-recovery).

## Errors and safe actions

Use HTTP status and `error.code` for recovery decisions; free-text messages are explanatory and are not proof of current filesystem contents.

| Response | Meaning and action |
|---|---|
| `400 stale_base` | The proposal base is old. Re-read JSON, reconcile the edit, and submit a new proposal. |
| `409 conflict` on PUT | The conflict envelope is `error.code: conflict` plus `error.reason: content_changed` or `page_deleted`; stop and reconcile. `page_moved` is reserved, not an emitted PUT conflict. |
| `400 invalid_root` / `409 ambiguous_page_id` / proposal `400 stale_base` | Malformed or repeated GET/PUT `?root` pins are `400 invalid_root`; a legal unknown GET/PUT root is `404 page_not_found`; duplicate bare ids are `409` and need a root pin; an edit proposal with no resolvable page/root is `400 stale_base`; an unknown create root is `400 invalid_root`. |
| `401` / `403` / `421` | Resolve credentials, permissions, read-only root, or insecure credential transport. Do not retry blindly. |
| `415 unsupported_media_type` | A direct PUT must use `Content-Type: text/markdown`. |
| `422 id_change_unsupported` | On direct PUT, restore the original frontmatter `id` and retry the PUT. |
| `404 page_not_found` | A miss in the requested visible scope; a root pin narrows that scope, and hidden or excluded content can also be 404. Do not infer physical deletion or erase historical citations/provenance. |
| `503 root_unavailable` / `absence_unverified` | Availability is unresolved. Keep citations and provenance, honor `Retry-After` when supplied (`300` or `30` seconds) for reads after recovery/convergence; inspect uncertain writes before resubmitting. Discussion source admission can omit the absence header. `server_shutting_down` is a pre-work rejection with no Retry-After promise. |

For a bare or rooted permalink (`/p/{id}` or `/p/{root}/{id}`), `410 page_retired` means retirement was recorded for that root.
Retirement is reversible, and live alternative roots may be listed in `Link` headers. Keep its historical citation/provenance
and do not promise `410` through MCP or `/api/v1/pages`.

Respect a returned `Retry-After`; the network itself supplies no equivalent
guarantee. If a POST or PUT times out, inspect the current page/proposal queue, or the affected
discussion for a discussion mutation, before resubmitting.
There is no universal idempotency promise: proposal POSTs can create independent proposals. Direct
PUT has a narrow same-content retry shim (a stale base with identical on-disk bytes can return 200),
but a degraded PUT can create a proposal, so do not treat every mutation retry as safe.

## Optional direct writes

An unreviewed direct page PUT requires a `COMMIT` token and a target path inside that root's allowed glob. A
`READ_ONLY` agent is refused with `403`; a `PROPOSE` agent, or a COMMIT write outside its glob,
degrades to `202` with fields `degraded`, `proposal_id`, `status`, and `unified_diff`. Root `editable = false` still
refuses writes; degradation is not a permission bypass. See the [per-root direct-commit globs](configuration.md#per-root-agent-direct-commit-globs).

This is an alternative to submitting a proposal, not a second write for the same edit. If you already
posted a proposal, stop there. For a direct edit, perform a fresh JSON read and edit a new local copy
so its source and quoted ETag/hash are current.

```bash
DIRECT_PAGE_JSON="$WORK_DIR/direct-page.json"
DIRECT_PAGE_HEADERS="$WORK_DIR/direct-page.headers"
curl --fail-with-body --silent --show-error --get "$BASE_URL/api/v1/pages/$PAGE_ID" \
  "${AUTH[@]}" --data-urlencode "root=$ROOT" \
  --header 'Accept: application/json' \
  --dump-header "$DIRECT_PAGE_HEADERS" --output "$DIRECT_PAGE_JSON"
jq -e --arg id "$PAGE_ID" --arg root "$ROOT" \
  '(.id == $id) and (.root == $root) and (.content_hash | type == "string") and (.markdown | type == "string")' \
  "$DIRECT_PAGE_JSON" >/dev/null || {
  printf '%s\n' 'Fresh direct-write JSON did not match the selected id/root or lacks source/hash.' >&2
  exit 1
}
DIRECT_EDITED_MD="$WORK_DIR/direct-edited.md"
jq -j '.markdown' "$DIRECT_PAGE_JSON" > "$DIRECT_EDITED_MD"
"${EDITOR:-vi}" "$DIRECT_EDITED_MD"

ETAG="$(awk 'tolower($0) ~ /^[[:space:]]*etag[[:space:]]*:/ { sub(/\r$/, ""); sub(/^[^:]*:[[:space:]]*/, ""); print; exit }' "$DIRECT_PAGE_HEADERS")"
if [ -z "$ETAG" ]; then
  printf '%s\n' 'Fresh JSON read did not return a quoted ETag.' >&2
  exit 1
fi
PUT_URL="$BASE_URL/api/v1/pages/$PAGE_ID?root=$(jq -rn --arg root "$ROOT" '$root | @uri')"
curl --fail-with-body --silent --show-error --request PUT "$PUT_URL" \
  "${AUTH[@]}" \
  --header 'Content-Type: text/markdown' \
  --header "If-Match: $ETAG" \
  --data-binary "@$DIRECT_EDITED_MD"
```

`200` means the raw Markdown bytes were saved; the response has `content_hash` and `commit`, and
may also have a `warning` when indexing is deferred. `202` means the write was accepted as a pending
proposal and includes `proposal_id`; `proposal_id` and the `id` returned by proposal POST are different
JSON field names for the same kind of proposal identifier. Direct page creation also exists, but this
guide keeps its full write API out of the proposal workflow.

## When finished

```bash
rm -rf -- "$WORK_DIR"
```
