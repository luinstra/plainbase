package com.plainbase.frameworks.search

import com.plainbase.domain.render.GoldenTsv
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.SectionSplitter
import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe

/**
 * The S2 BM25 weight-tuning golden: every query's TOP-1 hit over the fixture corpus, pinned with
 * the tier-2 "pinned-but-reviewable, NOT frozen" discipline (banner + regeneration rules in the
 * TSV header). The `Fts5SearchProvider` weight constants were tuned until this set passed; a
 * deliberate re-tune regenerates the pins WITH review, never blindly.
 */
class Bm25GoldenTest : FunSpec({

    val golden = GoldenTsv.load("/golden/search/bm25-queries.tsv")

    test("the golden set has the agreed coverage (~10-15 queries)") {
        golden shouldHaveAtLeastSize 10
    }

    IndexHarness(Fixtures.demoDocs).use { harness ->
        val snapshot = harness.builder.rebuild()
        val splitter = SectionSplitter()

        golden.forEach { (text, expectedPath, expectedHeading) ->
            test("top-1 for '$text' is $expectedPath#$expectedHeading") {
                withProvider { provider, _ ->
                    provider.rebuild(snapshot.pages.asSequence().map(splitter::split))
                    val top = provider.search(query(text)).hits.first()
                    val page = snapshot.byId.getValue(top.pageId)
                    page.path.value shouldBe expectedPath
                    (top.headingId ?: "-") shouldBe expectedHeading
                }
            }
        }
    }
})
