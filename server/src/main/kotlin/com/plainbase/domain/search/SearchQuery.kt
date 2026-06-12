package com.plainbase.domain.search

import com.plainbase.domain.page.PageId

/**
 * One engine query (§B4): plain-text [text] (the engine adapter owns turning it into engine
 * syntax safely — never the caller), a [limit]/[offset] page window, and an optional
 * [statusFilter] restricting hits to documents whose `status` is in the set. The filter is
 * port-level only in Phase 2 — the REST `status` param is deliberately unfrozen Phase-5 surface.
 */
data class SearchQuery(
    val text: String,
    val limit: Int,
    val offset: Int,
    val statusFilter: Set<String>? = null,
)

/**
 * One engine answer: [total] matching section documents and the requested page window of [hits],
 * both read from the SAME engine snapshot (§B5 — a hits/total pair never mixes generations).
 */
data class SearchResults(
    val total: Long,
    val hits: List<SearchHit>,
)

/**
 * The minimal engine hit (§B4/§B7): identity, snippet, highlights, score — nothing more. Display
 * fields (title, url, heading text/path, citation) are assembled from the published snapshot at
 * response time, never stored engine-side, which is why a lagging engine can embarrass ranking
 * but never citations.
 */
data class SearchHit(
    val pageId: PageId,
    /** The matched section's PB-SLUG-1 anchor; null for a page-level hit. */
    val headingId: String?,
    /** §A3: plain text — no HTML, no markup, no marker characters. */
    val snippet: String,
    /** §A3: ascending, non-overlapping, never splitting a surrogate pair. May be empty. */
    val highlights: List<Highlight>,
    /** §A4: finite; higher = more relevant; values are engine-scaled, never comparable across engines. */
    val score: Double,
)

/** A §A3 highlight range: UTF-16 code-unit offsets into the snippet, half-open `[start, end)`. */
data class Highlight(
    val start: Int,
    val end: Int,
)
