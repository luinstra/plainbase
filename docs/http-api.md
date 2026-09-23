# HTTP API reference

This is the concise reference for Plainbase's document-read HTTP surface.

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
