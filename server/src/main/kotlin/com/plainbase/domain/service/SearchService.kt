package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.Citation
import com.plainbase.domain.page.Heading
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageId
import com.plainbase.domain.search.Highlight
import com.plainbase.domain.search.SearchHit
import com.plainbase.domain.search.SearchProvider
import com.plainbase.domain.search.SearchQuery

/**
 * The read service behind PB-SEARCH-1 (§A1/§A2/§B7) — raw query parameters in, a complete
 * snapshot-coherent search payload out. The §A1 grammar lives HERE in the domain (the route only
 * maps [Outcome.InvalidQuery] to a 400 `invalid_query`); every violation message names the rule it
 * breaks, and those messages are frozen by the PB-SEARCH-1 error-envelope goldens.
 *
 * Assembly (§B7): engines return minimal hits — display fields are joined against the CURRENT
 * published [PageIndex][com.plainbase.domain.page.PageIndex] snapshot, never read back from
 * engine-stored copies. A hit whose page left the snapshot in the race window between engine query
 * and assembly is DROPPED (never served stale — [Outcome.Results.payload]'s `hits` may therefore
 * run shorter than `min(limit, total - offset)`, the documented §A2 narrow race); a hit whose
 * heading left `page.headings` degrades to a page-level hit (stale anchors are never emitted).
 * The coherence prize: a search citation always verifies against what `GET /pages/{id}` serves at
 * that moment, because both read the same snapshot.
 */
class SearchService(
    private val provider: SearchProvider,
    private val indexBuilder: IndexBuilder,
) {

    sealed interface Outcome {
        data class Results(val payload: SearchPayload) : Outcome

        /** An §A1 grammar violation; [message] names the violated rule (the route's 400 `invalid_query`). */
        data class InvalidQuery(val message: String) : Outcome
    }

    /** Runs the frozen §A1 grammar over the raw parameters, then queries and assembles (§B7). */
    fun search(q: String?, limit: String? = null, offset: String? = null): Outcome {
        val query = q?.trim().orEmpty()
        if (query.isEmpty()) return Outcome.InvalidQuery("q is required and must be non-empty after trimming")
        if (query.length > MAX_QUERY_UTF16_UNITS) return Outcome.InvalidQuery("q exceeds $MAX_QUERY_UTF16_UNITS UTF-16 code units")
        val limitValue = limit.boundedInt(MIN_LIMIT, MAX_LIMIT, DEFAULT_LIMIT)
            ?: return Outcome.InvalidQuery("limit must be an integer between $MIN_LIMIT and $MAX_LIMIT")
        val offsetValue = offset.boundedInt(MIN_OFFSET, MAX_OFFSET, DEFAULT_OFFSET)
            ?: return Outcome.InvalidQuery("offset must be an integer between $MIN_OFFSET and $MAX_OFFSET")

        val results = provider.search(SearchQuery(text = query, limit = limitValue, offset = offsetValue))
        val snapshot = indexBuilder.current
        return Outcome.Results(
            SearchPayload(
                query = query,
                engine = ENGINE,
                limit = limitValue,
                offset = offsetValue,
                total = results.total,
                hits = results.hits.mapNotNull { hit -> snapshot.byId[hit.pageId]?.let { page -> assemble(hit, page) } },
            ),
        )
    }

    /**
     * Adjudicated race window (the plan's accepted letter, not an oversight): when a page's
     * CONTENT changes under the SAME id, an engine queried between the snapshot publish and the
     * sync that follows it may return a snippet/highlights from the OLD text while everything
     * assembled here (title, url, citation `content_hash`) is CURRENT. That is §B7 working as
     * designed — display text is engine quality and may lag ("a lagging engine can embarrass
     * ranking but never citations"); the citation still verifies against the concurrent
     * `GET /pages/{id}`, and the §B4 listener seam closes the window at the next sync. Engine
     * visibility is always absent-or-old-or-new, never torn (the Appendix G rule).
     */
    private fun assemble(hit: SearchHit, page: IndexedPage): SearchHitPayload {
        // §B7: null breadcrumb = the heading left the snapshot since the engine indexed — degrade
        // to a page-level hit. The citation carries the DEGRADED heading id for the same reason.
        val breadcrumb = hit.headingId?.let { breadcrumbFor(page, it) }
        val headingId = breadcrumb?.let { hit.headingId }
        return SearchHitPayload(
            pageId = page.id,
            path = page.path,
            url = page.url,
            title = page.title,
            headingId = headingId,
            headingText = breadcrumb?.last(),
            headingPath = breadcrumb.orEmpty(),
            snippet = hit.snippet,
            highlights = hit.highlights,
            score = hit.score,
            citation = Citation(
                pageId = page.id,
                headingId = headingId,
                path = page.path,
                contentHash = page.contentHash,
                commit = page.commit,
            ),
        )
    }

    /**
     * The §B7 breadcrumb: heading TEXTS ancestors-first, the hit's own text last (ancestor = the
     * nearest preceding heading of a lower level), recomputed from the snapshot's ordered headings.
     * Null when [headingId] is no longer on the page — the degradation signal.
     */
    private fun breadcrumbFor(page: IndexedPage, headingId: String): List<String>? {
        val trail = ArrayDeque<Heading>()
        for (heading in page.headings) {
            while (trail.isNotEmpty() && trail.last().level >= heading.level) trail.removeLast()
            trail.addLast(heading)
            if (heading.id == headingId) return trail.map { it.text }
        }
        return null
    }

    /** §A1 int rule: absent → [default]; otherwise a strict integer within [min]..[max], else null. */
    private fun String?.boundedInt(min: Int, max: Int, default: Int): Int? =
        if (this == null) default else toIntOrNull()?.takeIf { it in min..max }

    companion object {
        /** §A2: the only `engine` value Phase 2 emits; future identifiers are purely additive. */
        const val ENGINE: String = "embedded"

        /** §A1, frozen: `q` is at most 512 UTF-16 code units after trimming. */
        const val MAX_QUERY_UTF16_UNITS: Int = 512

        const val MIN_LIMIT: Int = 1
        const val MAX_LIMIT: Int = 100
        const val DEFAULT_LIMIT: Int = 20

        const val MIN_OFFSET: Int = 0

        /** §A1, frozen: the full 0–10000 offset range is honored on every enabled engine. */
        const val MAX_OFFSET: Int = 10_000
        const val DEFAULT_OFFSET: Int = 0
    }
}

/** The assembled §A2 response, before DTO mapping ([total] from the engine snapshot, hits §B7-joined). */
data class SearchPayload(
    val query: String,
    val engine: String,
    val limit: Int,
    val offset: Int,
    val total: Long,
    val hits: List<SearchHitPayload>,
)

/** One §A2 hit: engine fields ([snippet]/[highlights]/[score]) + snapshot fields (everything else, §B7). */
data class SearchHitPayload(
    val pageId: PageId,
    val path: TreePath,
    val url: String?,
    val title: String,
    val headingId: String?,
    val headingText: String?,
    val headingPath: List<String>,
    val snippet: String,
    val highlights: List<Highlight>,
    val score: Double,
    val citation: Citation,
)
