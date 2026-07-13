package com.plainbase.domain.service

import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.UnavailableCause
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
 *  - a hit whose ROOT is not serving is DROPPED the same way (ADR-0011 D5's liveness filter, pinned HERE and
 *    not only through the REST suite: it is a §B7 assembly rule, and the assembly is what this spec owns);
 *  - a hit whose heading left `page.headings` DEGRADES to a page-level hit — stale anchors are
 *    never emitted, and the citation carries the degraded (null) heading id;
 *  - display fields (`title`/`url`/`heading_path`) always come from the snapshot.
 */
class SearchServiceTest : FunSpec({

    fun hit(pageId: PageId, headingId: String?, score: Double = 1.0, root: RootName = RootName.MAIN) =
        SearchHit(pageId = pageId, root = root, headingId = headingId, snippet = "…body…", highlights = emptyList(), score = score)

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

    test("a hit whose ROOT is not serving is dropped at assembly; total stays engine-truth (ADR-0011 D5)") {
        withTempTree(seed = { root -> writePage(root, "alpha.md", "# Alpha\n\nshared body text.\n") }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val alpha = harness.builder.current.pages.single()
                val service = SearchService(providerReturning(hit(alpha.id, "alpha")), harness.builder, harness.availability)

                resultsOf(service.search("shared")).hits.map { it.pageId } shouldBe listOf(alpha.id)

                // No rebuild: an unavailable root's section is CARRIED FORWARD, so the page is still in `byId` and the
                // engine still returns it. The liveness filter is the ONLY thing standing between a downed root and a
                // live search result served from its stale index rows.
                harness.availability.markUnavailable(RootName.MAIN, UnavailableCause.VANISHED)

                val payload = resultsOf(service.search("shared"))
                payload.hits shouldBe emptyList()
                payload.total shouldBe 1L // the engine's count is untouched - the documented §A2 short-page shape
            }
        }
    }

    test("a hit the engine indexed under ANOTHER root is dropped - one root's snippet is never served as another's") {
        withTempTree(seed = { root -> writePage(root, "alpha.md", "# Alpha\n\nshared body text.\n") }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val alpha = harness.builder.current.pages.single() // root = main in the snapshot
                // The engine still holds this id under a DIFFERENT root: exactly what a rebuild that re-awarded the id
                // across roots leaves behind between the snapshot publish and the search sync that follows it. Joining
                // on the id alone would pair the OLD root's snippet with main's url, main's citation and main's
                // availability check - and, when the old root is the unavailable one, walk straight past the liveness
                // filter, which reads the page's CURRENT root.
                val service = SearchService(providerReturning(hit(alpha.id, "alpha", root = RootName.require("archive"))), harness.builder)

                val payload = resultsOf(service.search("shared"))

                payload.hits shouldBe emptyList()
                payload.total shouldBe 1L // engine-truth count, the same §A2 short-page shape a departed page produces
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
                assembled.url shouldBe "/docs/main/alpha"
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
