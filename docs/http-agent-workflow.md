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
token is shown only once. Mint or revoke it against the target service's configured `DATA_DIR`:
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
the page identity; omit `page_id` and `base_hash`.

```bash
CREATE_MD="$WORK_DIR/new-page.md"
printf '%s\n' '# New page' '' 'Created by an HTTP agent.' > "$CREATE_MD"
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
| `503 root_unavailable` / `absence_unverified` | Availability is unresolved. Keep citations and provenance, honor `Retry-After` (`300` or `30` seconds), and retry after recovery or convergence. `server_shutting_down` is a pre-work rejection with no Retry-After promise. |

For a bare or rooted permalink (`/p/{id}` or `/p/{root}/{id}`), `410 page_retired` means retirement was recorded for that root.
Retirement is reversible, and live alternative roots may be listed in `Link` headers. Keep its historical citation/provenance
and do not promise `410` through MCP or `/api/v1/pages`.

Respect a returned `Retry-After`; the network itself supplies no equivalent
guarantee. If a POST or PUT times out, inspect the current page and proposal queue before resubmitting.
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
