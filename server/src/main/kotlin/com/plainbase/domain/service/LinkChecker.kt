package com.plainbase.domain.service

import com.plainbase.domain.content.PercentCoding
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.LinkOutcome
import com.plainbase.domain.model.PageLink
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageIndex

/**
 * Chunk 8 — whole-tree link + fragment validation over a published [PageIndex]: the engine behind
 * the Phase-1 acceptance gate, and the one Phase 5's `validate_links` MCP tool exposes.
 *
 * The checker AGGREGATES, it never re-resolves: every page's [PageLink] outcomes were produced by
 * the single render-time [LinkResolver] pass, so one resolution model exists in the tree. What the
 * checker ADDS is the cross-page concern resolution cannot decide alone — fragment (anchor)
 * existence against the target page's PB-SLUG-1 heading ids, reported as `broken_anchor`, the
 * error class §A2 deliberately reserves for the link checker (see [LinkOutcome.BrokenReason]).
 *
 * Pure domain code: stdlib + chunk 1.5/2/5 domain types only.
 */
class LinkChecker {

    /** Walks every page of [index] and reports each broken link/anchor, in page-then-document order. */
    fun check(index: PageIndex): LinkReport = LinkReport(Sweep(index).broken())

    /** One sweep over one snapshot; memoizes each target page's emitted anchor set across links. */
    private class Sweep(private val index: PageIndex) {

        private val anchorsByPage = HashMap<TreePath, Set<String>>()

        fun broken(): List<BrokenLink> =
            index.pages.flatMap { page -> page.links.mapNotNull { link -> brokenOrNull(page, link) } }

        private fun brokenOrNull(page: IndexedPage, link: PageLink): BrokenLink? {
            val reason = when (val outcome = link.outcome) {
                is LinkOutcome.Broken -> BrokenLinkReason.Unresolved(outcome.reason)
                // Cross-page fragment: validated against the TARGET page's heading ids.
                is LinkOutcome.Resolved.Page -> brokenAnchorOrNull(outcome.url, targetOf(outcome.page))
                // Same-page anchor (`#…`): validated against THIS page's heading ids.
                is LinkOutcome.Resolved.Anchor -> brokenAnchorOrNull(outcome.url, page)
                // Asset fragments (e.g. SVG views) and external URLs are outside the index's knowledge.
                is LinkOutcome.Resolved.Asset, is LinkOutcome.Resolved.External -> null
            }
            return reason?.let { BrokenLink(page = page.path, target = link.target, text = link.text, reason = it) }
        }

        private fun brokenAnchorOrNull(url: String, target: IndexedPage): BrokenLinkReason? {
            val fragment = url.substringAfter('#', missingDelimiterValue = "")
            // No fragment to validate — including the conventional bare `#` (page top).
            if (fragment.isEmpty()) return null
            return if (fragment in anchorsOf(target)) null else BrokenLinkReason.UnknownAnchor
        }

        /**
         * The page's anchor set in the resolver's EMITTED form: a resolved URL carries its fragment
         * strict-decoded and re-encoded by the single percent-coder (§A2), so heading ids are
         * compared after the same [PercentCoding.encodeSegment] — no decode round-trip here.
         */
        private fun anchorsOf(page: IndexedPage): Set<String> =
            anchorsByPage.getOrPut(page.path) { page.headings.map { PercentCoding.encodeSegment(it.id) }.toSet() }

        /** A snapshot outcome can only reference a page of the SAME snapshot ([PageIndex] coherence). */
        private fun targetOf(path: TreePath): IndexedPage =
            requireNotNull(index.byPath[path]) { "link outcome references a page outside the snapshot: ${path.value}" }
    }
}

/** The aggregated whole-tree result: an empty [broken] list means every link and anchor resolves. */
data class LinkReport(val broken: List<BrokenLink>) {
    val clean: Boolean get() = broken.isEmpty()
}

/** One broken link: the page it appears on, the raw target as written, the link text, and the error class. */
data class BrokenLink(
    val page: TreePath,
    val target: String,
    val text: String,
    val reason: BrokenLinkReason,
)

/**
 * The checker-level error class: every render-time [LinkOutcome.BrokenReason] passes through
 * verbatim ([Unresolved] — the frozen, append-only §A2 classes), plus the one class only the
 * checker can decide — [UnknownAnchor], `broken_anchor` on the wire.
 */
sealed interface BrokenLinkReason {
    val wireValue: String

    data class Unresolved(val reason: LinkOutcome.BrokenReason) : BrokenLinkReason {
        override val wireValue: String get() = reason.wireValue
    }

    data object UnknownAnchor : BrokenLinkReason {
        override val wireValue: String get() = "broken_anchor"
    }
}
