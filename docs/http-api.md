# HTTP API reference

This is the concise reference for Plainbase's document HTTP surface.

For a runnable search → JSON read → proposal workflow without an MCP client, see [Use Plainbase from an HTTP agent](http-agent-workflow.md).

## Document reads

The full indexed document can be read through either representation:

- `GET /api/v1/pages/{id}`
- `GET /api/v1/pages/{id}?root={root}` when an ID is held by more than one root
- `GET /api/v1/pages/by-path/{root}/{path...}`
- `GET /{root}/{path...}` for a canonical or alias browser address
- `GET /p/{id}` or `GET /p/{root}/{id}` for the durable permalink surface

Bare root landings such as `/docs` and `/docs/` remain SPA shells.

REST reads default to JSON. Browser document addresses default to the HTML shell; normal permalink
addresses default to redirects to the document URL.
Send `Accept: text/markdown` to opt into Markdown when that explicit range has a positive quality
strictly greater than the historical default representation. For example:

```http
GET /api/v1/pages/0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a HTTP/1.1
Accept: text/markdown
```

The response is `200 OK`, `Content-Type: text/markdown; charset=UTF-8`, and the UTF-8 encoding of
the indexed page's `markdown` field: the complete source including BOM characters, frontmatter,
fences, Unicode, original line endings, and final-newline presence. Invalid UTF-8 retains the
existing replacement-character decode; the original invalid bytes cannot be recovered from this
representation. Markdown responses carry `Cache-Control: no-store`, no `ETag`, and
`X-Content-Type-Options: nosniff`.

Quality comparison uses the most-specific matching range, accepts repeated `Accept` fields, and
uses the first identical range. Missing `q` means `1`; valid qvalues are `0..1` with at most
three decimal places. Wildcards alone never select Markdown, and equal quality keeps JSON or HTML.
Unsupported media parameters and charsets do not match. Invalid or duplicate q parameters make that
media-range member ineligible; another valid Markdown member can still win. If Markdown is not
selected, the route keeps its default JSON or HTML representation rather than producing a new `406`.
All affected responses include `Vary: Accept`.

Canonical document URLs in Markdown mode return indexed source on success and structured JSON for
authentication, authorization, lookup, and root-availability failures, including `503` outage responses.
A directory URL without an indexed page is JSON `404` in Markdown mode while HTML keeps its landing shell.
Bare root landings remain shells in either mode.

Normal permalink resolutions retain their existing `302` redirect, including the query string;
follow it while preserving the `Accept` header. Collision losers without a canonical URL can be
served directly as Markdown. Retired IDs remain `410`, ambiguous IDs remain `300` on permalink
URLs (with their alternate `Link` headers), and lookup, authentication, authorization, malformed
address, and unavailable-root failures retain their structured JSON envelopes and operational
headers. Markdown opt-in never turns a denied or missing page into raw content or an HTML shell.

The JSON representation is unchanged, including its body bytes and strong quoted `ETag`. Use that
JSON response's `ETag` value as the `If-Match` base hash for a subsequent page `PUT`; Markdown has
no representation validator. Page reads do not return conditional `304` responses: even a matching
`If-None-Match` tag still performs the authorized lookup and returns the selected representation with
`200` when the read succeeds.

## Page creation

`POST /api/v1/pages` accepts UTF-8 JSON with required `root` and nonblank `title`, plus optional
`folder`, `slug`, `body`, and `type`. `root` names a configured content root; `folder` is its relative
parent directory (omitted/empty means the root). The server mints the id and derives a `.md` filename
from the supplied slug, or otherwise the title. It also owns the returned canonical URL.

```json
{
  "root": "docs",
  "folder": "guides",
  "title": "Deployment reference",
  "type": "Reference",
  "body": "# Deployment reference\n\nCheck the release before deploying.\n"
}
```

Non-null `type` opts into [OKF-compatible concept frontmatter](okf-documents.md). Output uses exact
LF `---` fences and id/type/title/optional slug order; type, title and slug are quoted YAML strings.
The body is appended verbatim. Type must be a nonblank string; accepted values retain their exact
text, including spaces, and unknown descriptive types are supported.

Typed title, supplied slug, and type reject ISO control characters, U+FFFE/U+FFFF, U+2028/U+2029,
and unpaired UTF-16 surrogates. Valid astral characters are accepted. The separator restriction is
Plainbase's single-line producer policy. Typed body rejects unpaired surrogates so UTF-8 encoding
cannot silently replace them; ordinary valid Unicode and body separators remain allowed. A typed
request whose actual derived filename is `index.md` or `log.md` is rejected at any folder depth.
Choose another title or a non-reserved custom slug. Invalid type kinds, blank type, these invalid
values, and typed reserved filenames return `400` with `error.code: "invalid_create_request"`.

Omitting `type` or sending `"type": null` preserves legacy creation bytes and behavior, including
reserved filenames. The ordinary new-page dialog sends `Reference` for every template; section
creation and all save-as-new recovery requests omit type. Later raw saves may change/remove type;
the endpoint does not certify subsequent edits or a whole directory.

Direct creation returns `201` with `id`, server-authoritative `url`, `content_hash`, and `commit`.
A deferred reindex retains `201` but adds the existing warning and sets `url` to null; wait for
reconciliation instead of inventing a URL. An agent request outside direct-commit policy can return
`202` with `proposal_id`, `status`, and `unified_diff`; approval writes the stored composed bytes.
Existing authentication, ownership, exclusion, collision, and body-size rules remain in force,
including the cap on the complete composed document after the new type line is added.

## Discussion passage versions

Discussion page-list, root-list and detail responses include nullable `range_content_hash` beside the resolved `range`. For `exact` and `moved` matches it identifies the precise source bytes used for that range, including a cached match's original source version. Consumers should highlight source blocks only when it matches the displayed page HTML `content_hash`; a different or missing hash means the range cannot safely be painted on that document. Other match states return null. Stored original and reattachment anchors remain unchanged.
