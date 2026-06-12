# 3. Folders gain client-rendered landing views at their URL prefix

- **Status:** Accepted
- **Date:** 2026-06-12
- **Deciders:** luinstra
- **Context:** Post-Phase-1 mini-chunk — `TreeBuilder`/`CanonicalUrlBuilder.folderUrlPaths`
  (server), `FolderLanding`/`FolderListing` in `frontend/src/components/PageView.tsx` (SPA),
  amending §A4's Phase-1 "no directory-URL" rule additively (plan line 300).

## Context

Phase 1 gave folders no URL of their own: `index.md` is an ordinary page, the `/docs/$` route
resolves only pages via `by-path`, and a folder's URL prefix fell through to the 404 view. That
made sidebar folder labels and breadcrumb ancestors inert — navigational dead ends in a tree UI
whose every other node is a link. Real docs trees expect a folder address to show *something*:
its README, or at least its contents.

Two constraints shape the solution. First, the server is the single URL authority (§A4): the SPA
must never slugify folder names client-side. Second, the PB-REST-1 shapes are frozen with an
additive-only amendment rule (the same rule that lets the citation object grow `url` later) — so
the server may ADD a field, but routing and `by-path` semantics must not change.

## Decision

**Server (additive only):** tree folder nodes gain a `url` field — the folder's `/docs` URL
prefix, built from the same §A4 segment chain its descendants' URLs extend and percent-encoded
exactly like page urls; `null` for a collision-loser folder (a losing folder takes its whole
subtree out of path space, per ADR-0002's folder-folder rule). The synthetic root carries
`/docs`. Nothing else moves: the API still 404s folder paths in `by-path`, and the routing
matrix already serves the shell at every `/docs/*` path. The tree golden was regenerated with
exactly this additive diff (recorded in `ForeverApiGoldenSuite`'s policy block).

**SPA:** when `by-path` 404s, the current pathname is matched VERBATIM against the loaded tree's
folder `url`s. On a match:

- **README-preference:** a direct child page whose filename stem is `index` or `readme`
  (case-insensitive, from the node's `path` — never re-slugified) renders AT the folder URL,
  fetched by id like a loser permalink; `index` wins when both exist (web-native beats
  repo-native). No redirect — the address bar stays on the folder URL, and the page remains
  independently reachable at its own canonical URL.
- **Listing fallback:** otherwise a generated directory view — `_folder.yaml` title (else name)
  as heading, children in TREE ORDER (never re-sorted client-side), pages linked via their node
  `url` (losers via `/p/{id}`), subfolders via their folder `url` (a loser subfolder stays inert
  text). Semantic tokens only; stable `data-pb-folder*` hooks.

**Page shadows folder:** resolution order is `by-path` FIRST — a page owning the URL means the
folder view is never consulted. This is the page-vs-folder ordering ADR-0002 anticipated ("if a
folder URL ever exists … would need its own decision"): the page wins, the folder landing only
answers addresses no page owns, so ADR-0002's no-collision stance is preserved.

Sidebar folder labels link to the folder `url` (a separate disclosure button owns
expand/collapse), and breadcrumb ancestor crumbs link likewise.

## Consequences

**Positive**

- Every tree node is now navigable; folder READMEs surface where readers expect them.
- Zero new server routes or semantics — agents and the REST contract see one additive field.

**Trade-offs**

- A folder URL is a *client* construct: a raw `curl /docs/guides` returns the shell, and
  `by-path/guides` still 404s — tools must use the tree's `url` field, not guess.
- The README renders at two URLs (folder prefix + its own canonical). Citations are id-based, so
  identity is unaffected; the duplication is deliberate (no redirect keeps the folder address
  stable for sharing).

**Reversibility**

- Additive and append-only: removing the folder `url` field or the landing views would be a
  forever-API break and a navigation regression — the safe direction of travel is forward
  (e.g. a future server-rendered directory page would slot in behind the same `url`).
