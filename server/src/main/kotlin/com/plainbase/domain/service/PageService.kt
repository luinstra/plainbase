package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.Citation
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath

/**
 * The read service behind PB-REST-1's page endpoints (§A4) — and, unchanged, behind Phase 5's
 * `read_page` MCP tool: id or URL path in, a complete page payload out. Pure domain code over the
 * published [PageIndex] snapshot, and ONLY the snapshot: no disk read at request time, so every
 * payload is internally coherent (markdown/html/hash/citation all from one published world) and an
 * on-disk edit between rescans can never produce stale html under a fresh hash, nor a torn
 * old-index/new-store mismatch mid-rescan. (Assets are the deliberate opposite — see `AssetRoute`.)
 *
 * Lookup semantics (frozen):
 *  - [byId] — index `byId`; a shape-valid unknown id is the caller's `page_not_found`.
 *  - [byUrlPath] - the *decoded, NFC* ROOT-relative slug path (the tail after `/docs/{root}/`,
 *    C3; the route parses the root segment off first), matched case-sensitively
 *    against canonical paths first, then the alias registry; an alias hit returns the page whose
 *    payload carries the CURRENT canonical `url`, so clients self-correct (§A4).
 */
class PageService(
    private val indexBuilder: IndexBuilder,
    private val aliasRegistry: UrlAliasRegistry,
    private val citations: CitationFactory,
) {

    /**
     * The published index snapshot, freshly read. Kept for the callers that legitimately want CURRENT truth; the
     * GATED read paths do NOT use it - they thread the ONE snapshot their facade already read (below).
     */
    val index: PageIndex get() = indexBuilder.current

    /**
     * The full page payload for [id] in [snapshot], or null when unknown.
     *
     * [snapshot] is a PARAMETER, not a fresh `indexBuilder.current` read, and that is the one-snapshot rule the write
     * side already holds itself to (ADR-0011 D17): the facade gates on the root it resolved from ITS snapshot, and a
     * rebuild landing between the two reads can re-award a cross-root duplicate id to a DIFFERENT root - one whose
     * section may be a carried-forward one from a root that is DOWN. The facade would then have gated root A and
     * served root B's stale bytes with a 200. Gate-root and serve-root are now one object's answer by construction.
     */
    fun byId(snapshot: PageIndex, id: PageId): PagePayload? = snapshot.byId[id]?.let(::payload)

    /** The full page payload at [root]'s canonical-or-alias URL [path] in [snapshot], or null (§A4 by-path rules). */
    fun byUrlPath(snapshot: PageIndex, root: RootName, path: TreePath): PagePayload? {
        val rooted = RootedPath(root, path)
        val page = snapshot.byUrlPath[rooted]
            ?: aliasRegistry.find(rooted)?.let { snapshot.byId[it.id] }
            ?: return null
        return payload(page)
    }

    /** The rendered-HTML payload for [id] in [snapshot], or null when unknown. */
    fun htmlById(snapshot: PageIndex, id: PageId): PageHtmlPayload? = snapshot.byId[id]?.let { page ->
        PageHtmlPayload(page = page, citation = citations.pageLevel(page, page.contentHash))
    }

    private fun payload(page: IndexedPage): PagePayload =
        PagePayload(page = page, citation = citations.pageLevel(page, page.contentHash))
}

/** One page's full read payload (§A4 `GET /pages/{id}` / `by-path` shape, before DTO mapping). */
data class PagePayload(
    val page: IndexedPage,
    val citation: Citation,
)

/** One page's rendered payload (§A4 `GET /pages/{id}/html` shape, before DTO mapping). */
data class PageHtmlPayload(
    val page: IndexedPage,
    val citation: Citation,
)
