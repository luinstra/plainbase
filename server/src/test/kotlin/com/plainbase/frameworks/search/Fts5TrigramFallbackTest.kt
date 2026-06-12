package com.plainbase.frameworks.search

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.SectionSplitter
import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.core.Tag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The Kotest tag carrying the §A6 per-engine/per-tokenizer marking (the typo-exemption pattern,
 * declared like [com.plainbase.acceptance.Acceptance]): everything tagged with it pins the
 * EMBEDDED engine's unicode61+trigram pair specifically, so a platform or engine leg that
 * legitimately differs excludes it via `-Dkotest.tags='!Fts5Trigram'` instead of editing pins.
 */
object Fts5Trigram : Tag()

/**
 * The §B5 trigram rescue, gated IN by the S0 PASS verdict. **Tagged [Fts5Trigram] per the §A6
 * golden guidance: these assertions pin behavior of the EMBEDDED engine's unicode61+trigram pair
 * only — they may never migrate into a forever or cross-engine golden** (a different tokenizer
 * legitimately changes every one of them).
 *
 * Criteria (plan S2): `ガイド` finds the `日本語ガイド` fixture page (the exact case unicode61
 * cannot satisfy); the fallback NEVER fires when the primary MATCH has hits; the response shape
 * is identical either way (the fallback is invisible to PB-SEARCH-1).
 */
class Fts5TrigramFallbackTest : FunSpec({
    tags(Fts5Trigram)

    test("CJK rescue over the fixture corpus: ガイド finds the 日本語ガイド page") {
        withProvider { provider, _ ->
            IndexHarness(Fixtures.demoDocs).use { harness ->
                val snapshot = harness.builder.rebuild()
                val splitter = SectionSplitter()
                provider.rebuild(snapshot.pages.asSequence().map(splitter::split))

                val cjkPage = snapshot.byPath.getValue(TreePath.require("notes/日本語ガイド.md"))
                val results = provider.search(query("ガイド"))
                results.hits.shouldNotBeEmpty()
                results.hits.map { it.pageId }.toSet() shouldBe setOf(cjkPage.id)

                // Shape identical to a primary-index answer: same fields, same invariants.
                results.total shouldBe results.hits.size.toLong()
                results.hits.forEach { hit ->
                    hit.score.isFinite() shouldBe true
                    hit.assertWellFormedHighlights()
                }
            }
        }
    }

    test("the fallback never fires when the primary MATCH has hits: substring-only matches stay invisible") {
        withProvider { provider, _ ->
            provider.rebuild(
                sequenceOf(
                    pageDocuments(1, preamble = "alphabet soup recipe"),
                    pageDocuments(2, preamble = "xalphabety camouflage"), // trigram-only match for 'alphabet'
                ),
            )
            val primary = provider.search(query("alphabet"))
            primary.total shouldBe 1L // page 2 would join ONLY if the trigram fallback fired
            primary.hits.map { it.pageId } shouldBe listOf(pageId(1))
        }
    }

    test("zero primary hits fall back to substring semantics (non-CJK too), ranked by the trigram bm25") {
        withProvider { provider, _ ->
            provider.rebuild(
                sequenceOf(
                    pageDocuments(1, preamble = "alphabet soup recipe"),
                    pageDocuments(2, preamble = "xalphabety camouflage"),
                ),
            )
            // 'lphabe' is no token prefix, so the primary index has nothing; the trigram leg sees both.
            val fallback = provider.search(query("lphabe"))
            fallback.total shouldBe 2L
            fallback.hits.map { it.pageId }.toSet() shouldBe setOf(pageId(1), pageId(2))
            fallback.hits.forEach { it.snippet shouldContain "lphabe" }
        }
    }

    test("the fallback respects the same status filter and page window") {
        withProvider { provider, _ ->
            provider.rebuild(
                sequenceOf(
                    pageDocuments(1, status = "active", preamble = "xalphabety one"),
                    pageDocuments(2, status = "archived", preamble = "xalphabety two"),
                ),
            )
            provider.search(query("lphabe", statusFilter = setOf("active"))).hits.map { it.pageId } shouldBe listOf(pageId(1))
            val window = provider.search(query("lphabe", limit = 1, offset = 1))
            window.total shouldBe 2L
            window.hits.size shouldBe 1
        }
    }
})
