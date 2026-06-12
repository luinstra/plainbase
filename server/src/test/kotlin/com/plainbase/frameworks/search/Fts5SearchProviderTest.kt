package com.plainbase.frameworks.search

import com.plainbase.domain.search.SearchHit
import com.plainbase.domain.search.SearchResults
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * §B5 provider behavior over a real file-backed FTS5 database: field coverage, bm25 ordering +
 * negation, the deterministic tiebreak (§A4), paging, port-level status filtering, per-page
 * replace semantics, indexedState truthfulness, and the A3 snippet/highlight contract including
 * the surrogate-pair invariant over a non-BMP fixture.
 */
class Fts5SearchProviderTest : FunSpec({

    test("every document field is searchable: title, heading, body, tags, aliases, owner") {
        withProvider { provider, _ ->
            provider.rebuild(
                sequenceOf(
                    pageDocuments(
                        1,
                        title = "Quarterly Report",
                        tags = listOf("finance", "fiscal"),
                        aliases = listOf("q-report"),
                        owner = "treasury",
                        preamble = "Numbers for the quarter.",
                        sections = listOf("details" to "Spreadsheets and forecasts."),
                    ),
                ),
            )
            // Page metadata (title/tags/aliases/owner) repeats on BOTH documents of the page (§B4:
            // any hit filters without a join); heading/body live on their one section document.
            mapOf("quarterly" to 2L, "fiscal" to 2L, "q-report" to 2L, "treasury" to 2L, "details" to 1L, "forecasts" to 1L)
                .forEach { (term, expected) ->
                    withClue(term) { provider.search(query(term)).total shouldBe expected }
                }
        }
    }

    test("bm25 weights: a title hit outranks a page whose body repeats the term; scores are finite and descending (negated bm25)") {
        withProvider { provider, _ ->
            provider.rebuild(
                sequenceOf(
                    pageDocuments(1, title = "Deploy Guide", preamble = "Introduction to the knowledge base."),
                    pageDocuments(2, title = "Welcome", preamble = "deploy everywhere: deploy with kubernetes, deploy on metal"),
                ),
            )
            val results = provider.search(query("deploy"))
            results.total shouldBe 2L
            results.hits.first().pageId shouldBe pageId(1)
            results.hits.forEach { it.score.isFinite() shouldBe true }
            results.hits.zipWithNext().forEach { (a, b) -> (a.score >= b.score) shouldBe true }
        }
    }

    test("deterministic tiebreak: equal-score hits order by page_id then heading_id, stably across runs") {
        withProvider { provider, _ ->
            // Identical content on five pages = identical bm25 scores; only the tiebreak orders them.
            provider.rebuild((5 downTo 1).asSequence().map { pageDocuments(it, title = "Clone", preamble = "twin payload") })
            fun ordering() = provider.search(query("twin")).hits.map { it.pageId }
            val first = ordering()
            first shouldBe (1..5).map { pageId(it) } // page_id BLOB ascending
            repeat(3) { ordering() shouldBe first }
        }
    }

    test("limit/offset page windows tile the full result set; total is window-independent") {
        withProvider { provider, _ ->
            provider.rebuild((1..7).asSequence().map { pageDocuments(it, preamble = "needle haystack") })
            val full = provider.search(query("needle", limit = 100))
            full.total shouldBe 7L
            val windows = (0 until 7 step 2).flatMap { offset -> provider.search(query("needle", limit = 2, offset = offset)).hits }
            windows.map { it.pageId } shouldBe full.hits.map { it.pageId }
            provider.search(query("needle", limit = 2, offset = 100)).let { past ->
                past.hits.shouldBeEmpty()
                past.total shouldBe 7L // an empty window never loses the total (§B5 count design)
            }
        }
    }

    test("statusFilter is port-level: filtered out, included when listed, empty set matches nothing, null matches all") {
        withProvider { provider, _ ->
            provider.rebuild(
                sequenceOf(
                    pageDocuments(1, status = "active", preamble = "shared term"),
                    pageDocuments(2, status = "archived", preamble = "shared term"),
                ),
            )
            provider.search(query("shared")).total shouldBe 2L
            provider.search(query("shared", statusFilter = setOf("active"))).hits.map { it.pageId } shouldBe listOf(pageId(1))
            provider.search(query("shared", statusFilter = setOf("active", "archived"))).total shouldBe 2L
            provider.search(query("shared", statusFilter = emptySet())).total shouldBe 0L
        }
    }

    test("a query with no tokens (empty, whitespace, control characters) returns zero hits, never an error") {
        withProvider { provider, _ ->
            provider.rebuild(sequenceOf(pageDocuments(1, preamble = "content")))
            listOf("", "   ", "\t\n", "\u0000\u0001\u0002").forEach { text ->
                provider.search(query(text)) shouldBe SearchResults(0, emptyList())
            }
        }
    }

    test("indexedState is engine truth: tracks index, per-page replace, and delete exactly") {
        withProvider { provider, _ ->
            provider.indexedState() shouldBe emptyMap()
            val one = pageDocuments(1, contentHash = "sha256:v1")
            val two = pageDocuments(2, contentHash = "sha256:v2")
            provider.index(listOf(one, two))
            provider.indexedState().mapValues { it.value.contentHash } shouldBe mapOf(pageId(1) to "sha256:v1", pageId(2) to "sha256:v2")

            provider.index(listOf(pageDocuments(1, contentHash = "sha256:v1b", path = "moved/page-1.md")))
            val state = provider.indexedState().getValue(pageId(1))
            state.contentHash shouldBe "sha256:v1b"
            state.path.value shouldBe "moved/page-1.md"

            provider.delete(listOf(pageId(2)))
            provider.indexedState().keys shouldBe setOf(pageId(1))
        }
    }

    test("per-page replace semantics: a removed heading's document disappears with the replace") {
        withProvider { provider, _ ->
            val twoSections = listOf("alpha" to "first body", "beta" to "second body")
            provider.index(listOf(pageDocuments(1, "docs/p.md", contentHash = "sha256:a", preamble = "intro", sections = twoSections)))
            provider.search(query("second")).hits.map { it.headingId } shouldBe listOf("beta")

            provider.index(
                listOf(pageDocuments(1, "docs/p.md", contentHash = "sha256:b", preamble = "intro", sections = twoSections.take(1))),
            )
            provider.search(query("second")).total shouldBe 0L
            provider.search(query("first")).hits.map { it.headingId } shouldBe listOf("alpha")
        }
    }

    test("delete removes every document of the page from both indexes") {
        withProvider { provider, _ ->
            provider.index(listOf(pageDocuments(1, preamble = "unique marker", sections = listOf("h" to "日本語ガイド"))))
            provider.search(query("unique")).total shouldBe 1L
            provider.search(query("ガイド")).total shouldBe 1L // trigram fallback leg
            provider.delete(listOf(pageId(1)))
            provider.search(query("unique")).total shouldBe 0L
            provider.search(query("ガイド")).total shouldBe 0L
            provider.indexedState() shouldBe emptyMap()
        }
    }

    test("A3 snippet contract: plain text (no sentinels), well-formed offsets that extract the matched term") {
        withProvider { provider, _ ->
            val body = "install kubectl before attempting a rolling deploy of the new release to the cluster"
            provider.rebuild(sequenceOf(pageDocuments(1, preamble = body)))
            val hit = provider.search(query("rolling")).hits.single()
            hit.assertWellFormedHighlights()
            hit.highlights.shouldNotBeEmpty()
            hit.highlights.map { hit.snippet.substring(it.start, it.end).lowercase() } shouldBe listOf("rolling")
        }
    }

    test("A3 surrogate-pair invariant: offsets over a non-BMP snippet always land on code-point boundaries") {
        withProvider { provider, _ ->
            // Astral characters crowd the match so the snippet window and offsets must cross them.
            val body = "🦑🦑 mark 𝕊𝕖𝕔𝕥𝕚𝕠𝕟 sentinel 🦑 mark 🦑𝄞 ending"
            provider.rebuild(sequenceOf(pageDocuments(1, preamble = body)))
            listOf("mark", "sentinel", "ending").forEach { term ->
                val hit = provider.search(query(term)).hits.single()
                withClue("term=$term snippet=${hit.snippet}") {
                    hit.assertWellFormedHighlights()
                    hit.highlights.map { hit.snippet.substring(it.start, it.end).lowercase() }.forEach { it shouldBe term }
                }
            }
        }
    }

    test("highlights may be empty when the engine matched a field the snippet does not show (legal, not an error)") {
        withProvider { provider, _ ->
            provider.rebuild(sequenceOf(pageDocuments(1, tags = listOf("metadata-only"), preamble = "body text without the tag term")))
            val hit = provider.search(query("metadata-only")).hits.single()
            hit.snippet shouldNotBe null // shape identical; emptiness of highlights is legal per A3
        }
    }
})

/** The A3 well-formedness assertions shared by the snippet tests. */
fun SearchHit.assertWellFormedHighlights() {
    check('\u0001' !in snippet && '\u0002' !in snippet) { "sentinel markers leaked into the snippet" }
    var previousEnd = 0
    highlights.forEach { h ->
        check(h.start < h.end) { "empty or inverted range $h" }
        check(h.start >= previousEnd) { "overlapping/descending ranges: $highlights" }
        check(h.end <= snippet.length) { "range $h exceeds snippet length ${snippet.length}" }
        check(!(snippet[h.start].isLowSurrogate())) { "start of $h splits a surrogate pair" }
        check(!(snippet[h.end - 1].isHighSurrogate())) { "end of $h splits a surrogate pair" }
        previousEnd = h.end
    }
}
