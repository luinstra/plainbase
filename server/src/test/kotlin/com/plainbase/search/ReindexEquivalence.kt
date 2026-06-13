package com.plainbase.search

import com.plainbase.domain.search.SearchHit
import com.plainbase.domain.search.SearchProvider
import com.plainbase.domain.search.SearchQuery
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe

/**
 * The §Verification criterion-4 harness: capture the full answers to a FIXED query set, rebuild
 * the derived state (reindex on the same engine, a fresh engine over the same corpus, or — in
 * S8 — kill `search.db` and `reindex`), capture again, and compare with the ENGINE'S comparator:
 *
 *  - **Embedded** ([exactOrderedSequence]): the exact ordered hit sequence, byte for byte — the
 *    deterministic tiebreak (§A4: score DESC, page id, heading id) plus deterministic scoring
 *    make anything less than exact equality a real regression.
 *  - **A future engine** joins by passing its own [Comparator] when it subclasses the contract —
 *    pinned-but-reviewable per §A6 tier 2 (Appendix G), never by loosening the embedded one.
 */
object ReindexEquivalence {

    /** The fixed query set, aimed at [contractCorpus]: field hits, a tie cluster, a prefix, a multi-token, a zero-hit. CJK-free (§A6). */
    val querySet: List<String> = listOf(
        "deploy",
        "rolling deploy",
        "fiscal",
        "treasury",
        "homepage",
        "rollback",
        "twin",
        "deplo",
        "archive",
        "xyzzy-no-such-term",
    )

    /** One captured answer: the query, the engine's total, and the full ordered hit sequence. */
    data class QueryAnswer(val query: String, val total: Long, val hits: List<SearchHit>)

    fun capture(provider: SearchProvider): List<QueryAnswer> = querySet.map { text ->
        val results = provider.search(SearchQuery(text = text, limit = 50, offset = 0))
        QueryAnswer(query = text, total = results.total, hits = results.hits)
    }

    /** How one engine defines "equivalent before/after a reindex" (criterion 4's per-engine comparator). */
    fun interface Comparator {
        fun compare(before: List<QueryAnswer>, after: List<QueryAnswer>)
    }

    /** The embedded comparator: exact ordered sequence via the deterministic tiebreak — totals, order, snippets, scores. */
    val exactOrderedSequence: Comparator = Comparator { before, after ->
        after.zip(before).forEach { (got, expected) -> withClue(expected.query) { got shouldBe expected } }
        after.size shouldBe before.size
    }
}
