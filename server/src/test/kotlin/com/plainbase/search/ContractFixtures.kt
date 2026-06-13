package com.plainbase.search

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.search.PageDocuments
import com.plainbase.domain.search.SearchHit
import com.plainbase.domain.search.SearchQuery
import com.plainbase.domain.search.SectionDocument

// Engine-NEUTRAL fixture builders for the SearchProviderContract (chunk S3). They live in this
// package — not frameworks.search — because the contract suite must hold for every engine that
// ever implements the port, so nothing here may know an engine exists (enforced structurally by
// SearchContractPurityTest).

/** Deterministic test ids: canonical-shape UUIDs whose last group carries [n] (byte order == numeric order). */
fun pageId(n: Int): PageId = PageId.require("0197bbbb-0000-7000-8000-%012x".format(n))

/** One page whose sections are (headingId to body) pairs after the page-level document. */
fun page(
    n: Int,
    path: String = "docs/page-$n.md",
    title: String = "Page $n",
    contentHash: String = "sha256:$n",
    tags: List<String> = emptyList(),
    aliases: List<String> = emptyList(),
    owner: String? = null,
    status: String = "active",
    preamble: String = "",
    sections: List<Pair<String, String>> = emptyList(),
): PageDocuments {
    val id = pageId(n)
    val treePath = TreePath.require(path)
    fun doc(headingId: String?, heading: String?, body: String) = SectionDocument(
        pageId = id,
        headingId = headingId,
        title = title,
        heading = heading,
        headingPath = heading?.let { listOf(title, it) } ?: emptyList(),
        body = body,
        tags = tags,
        aliases = aliases,
        owner = owner,
        path = treePath,
        status = status,
    )
    return PageDocuments(
        pageId = id,
        contentHash = contentHash,
        path = treePath,
        sections = listOf(doc(null, null, preamble)) + sections.map { (headingId, body) -> doc(headingId, headingId, body) },
    )
}

fun query(text: String, limit: Int = 20, offset: Int = 0, statusFilter: Set<String>? = null) =
    SearchQuery(text = text, limit = limit, offset = offset, statusFilter = statusFilter)

/**
 * The corpus behind the reindex-equivalence harness: varied fields (title/tag/owner/alias hits),
 * a multi-section page, an archived page, and an identical-content tie cluster so the captured
 * sequences exercise the deterministic tiebreak, not just scoring. CJK-free on purpose — every
 * pinned/captured query in the contract follows the §A6 golden guidance.
 */
fun contractCorpus(): List<PageDocuments> = listOf(
    page(
        1,
        title = "Deploy Guide",
        tags = listOf("ops"),
        owner = "platform",
        preamble = "Rolling deploy checklist for the cluster.",
        sections = listOf(
            "steps" to "Drain nodes before the rolling deploy begins.",
            "rollback" to "Rollback restores the previous release.",
        ),
    ),
    page(2, title = "Welcome", aliases = listOf("homepage"), preamble = "deploy notes: deploy early, deploy often"),
    page(
        3,
        title = "Quarterly Report",
        tags = listOf("finance", "fiscal"),
        owner = "treasury",
        preamble = "Numbers for the quarter.",
        sections = listOf("details" to "Spreadsheets and forecasts."),
    ),
    page(4, status = "archived", title = "Old Runbook", preamble = "Legacy deploy instructions, kept for the archive."),
    page(5, title = "Clone", preamble = "twin payload"),
    page(6, title = "Clone", preamble = "twin payload"),
    page(7, title = "Clone", preamble = "twin payload"),
)

/** The §A3 well-formedness invariants (tier 3 — every enabled engine): plain text, ordered ranges, code-point boundaries. */
fun SearchHit.shouldHaveWellFormedHighlights() {
    check('\u0001' !in snippet && '\u0002' !in snippet) { "marker characters leaked into the snippet" }
    var previousEnd = 0
    highlights.forEach { h ->
        check(h.start < h.end) { "empty or inverted range $h" }
        check(h.start >= previousEnd) { "overlapping/descending ranges: $highlights" }
        check(h.end <= snippet.length) { "range $h exceeds snippet length ${snippet.length}" }
        check(!snippet[h.start].isLowSurrogate()) { "start of $h splits a surrogate pair" }
        check(!snippet[h.end - 1].isHighSurrogate()) { "end of $h splits a surrogate pair" }
        previousEnd = h.end
    }
}
