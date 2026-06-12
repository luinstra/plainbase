package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.Citation
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex

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
 *  - [byUrlPath] — the *decoded, NFC* `/docs/`-relative slug path, matched case-sensitively
 *    against canonical paths first, then the alias registry; an alias hit returns the page whose
 *    payload carries the CURRENT canonical `url`, so clients self-correct (§A4).
 */
class PageService(
    private val indexBuilder: IndexBuilder,
    private val aliasRegistry: UrlAliasRegistry,
    private val citations: CitationFactory,
) {

    /** The published index snapshot the routing layer reads from. */
    val index: PageIndex get() = indexBuilder.current

    /** The full page payload for [id], or null when unknown. */
    fun byId(id: PageId): PagePayload? = index.byId[id]?.let(::payload)

    /** The full page payload at the canonical-or-alias URL [path], or null (§A4 by-path rules). */
    fun byUrlPath(path: TreePath): PagePayload? {
        val snapshot = index
        val page = snapshot.byUrlPath[path]
            ?: aliasRegistry.find(path)?.let { snapshot.byId[it] }
            ?: return null
        return payload(page)
    }

    /** The rendered-HTML payload for [id], or null when unknown. */
    fun htmlById(id: PageId): PageHtmlPayload? = index.byId[id]?.let { page ->
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
