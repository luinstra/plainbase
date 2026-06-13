package com.plainbase.domain.service

import com.plainbase.domain.page.PageId
import com.plainbase.domain.search.SearchHit
import com.plainbase.domain.search.SearchProvider
import com.plainbase.domain.search.SearchResults
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk

/**
 * §B7 assembly at the unit level, with the engine MockK'd to return hits the published snapshot
 * no longer agrees with — the narrow race window between engine query and assembly:
 *
 *  - a hit whose page left the snapshot is DROPPED (never served stale; `total` stays the
 *    engine's count, the documented §A2 shortfall);
 *  - a hit whose heading left `page.headings` DEGRADES to a page-level hit — stale anchors are
 *    never emitted, and the citation carries the degraded (null) heading id;
 *  - display fields (`title`/`url`/`heading_path`) always come from the snapshot.
 */
class SearchServiceTest : FunSpec({

    fun hit(pageId: PageId, headingId: String?, score: Double = 1.0) =
        SearchHit(pageId = pageId, headingId = headingId, snippet = "…body…", highlights = emptyList(), score = score)

    fun providerReturning(vararg hits: SearchHit): SearchProvider = mockk {
        every { search(any()) } returns SearchResults(total = hits.size.toLong(), hits = hits.toList())
    }

    fun resultsOf(outcome: SearchService.Outcome): SearchPayload =
        outcome.shouldBeInstanceOf<SearchService.Outcome.Results>().payload

    test("a hit whose page left the published snapshot is dropped; total stays engine-truth (§B7/§A2)") {
        withTempTree(seed = { root -> writePage(root, "alpha.md", "# Alpha\n\nshared body text.\n") }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val alpha = harness.builder.current.pages.single()
                val vanished = PageId.require("0197dead-aaaa-7bbb-8ccc-000000000001")
                val provider = providerReturning(hit(alpha.id, "alpha", score = 2.0), hit(vanished, null, score = 1.0))

                val payload = resultsOf(SearchService(provider, harness.builder).search("shared"))

                payload.total shouldBe 2L
                payload.hits.map { it.pageId } shouldBe listOf(alpha.id)
            }
        }
    }

    test("a hit whose heading left the snapshot degrades to a page-level hit (§B7: stale anchors never emitted)") {
        withTempTree(seed = { root -> writePage(root, "alpha.md", "# Alpha\n\n## Setup\n\nshared body text.\n") }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val alpha = harness.builder.current.pages.single()
                val provider = providerReturning(hit(alpha.id, "renamed-away"))

                val degraded = resultsOf(SearchService(provider, harness.builder).search("shared")).hits.single()

                degraded.headingId shouldBe null
                degraded.headingText shouldBe null
                degraded.headingPath shouldBe emptyList()
                degraded.url shouldBe alpha.url
                degraded.citation.headingId shouldBe null
                degraded.citation.contentHash shouldBe alpha.contentHash
            }
        }
    }

    test("display fields come from the snapshot: title, url, and the recomputed breadcrumb incl. skipped levels (§B7)") {
        val markdown = "---\ntitle: Alpha Guide\n---\n\n# Alpha\n\n### Deep Dive\n\n## Setup\n\n### Wiring\n\nshared body text.\n"
        withTempTree(seed = { root -> writePage(root, "alpha.md", markdown) }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val alpha = harness.builder.current.pages.single()
                val provider = providerReturning(hit(alpha.id, "wiring"))

                val assembled = resultsOf(SearchService(provider, harness.builder).search("shared")).hits.single()

                assembled.title shouldBe "Alpha Guide"
                assembled.url shouldBe "/docs/alpha"
                assembled.headingId shouldBe "wiring"
                assembled.headingText shouldBe "Wiring"
                // Ancestor = nearest preceding heading of a LOWER level: the sibling "Deep Dive"
                // (level 3 under the same H1) must not appear in the trail.
                assembled.headingPath shouldBe listOf("Alpha", "Setup", "Wiring")
                assembled.citation.headingId shouldBe "wiring"
                assembled.citation.uri shouldBe "plainbase://${alpha.id.value}#wiring@${alpha.contentHash}"
            }
        }
    }

    test("§A1 validation rejects before any engine call: the provider is never queried for an invalid request") {
        withTempTree(seed = { root -> writePage(root, "alpha.md", "# Alpha\n") }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val provider = mockk<SearchProvider>() // no stubbing: any call would throw
                val service = SearchService(provider, harness.builder)

                service.search(null).shouldBeInstanceOf<SearchService.Outcome.InvalidQuery>()
                service.search("   ").shouldBeInstanceOf<SearchService.Outcome.InvalidQuery>()
                service.search("a".repeat(513)).shouldBeInstanceOf<SearchService.Outcome.InvalidQuery>()
                service.search("ok", limit = "0").shouldBeInstanceOf<SearchService.Outcome.InvalidQuery>()
                service.search("ok", limit = "101").shouldBeInstanceOf<SearchService.Outcome.InvalidQuery>()
                service.search("ok", limit = "x").shouldBeInstanceOf<SearchService.Outcome.InvalidQuery>()
                service.search("ok", offset = "-1").shouldBeInstanceOf<SearchService.Outcome.InvalidQuery>()
                service.search("ok", offset = "10001").shouldBeInstanceOf<SearchService.Outcome.InvalidQuery>()
            }
        }
    }
})
