package com.plainbase.domain.service

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.Frontmatter
import com.plainbase.domain.page.FrontmatterParser
import com.plainbase.domain.page.PageIndexView
import com.plainbase.domain.render.GoldenTsv
import com.plainbase.domain.render.MarkdownRenderer
import com.plainbase.domain.render.RenderedPage
import com.plainbase.frameworks.filesystem.Fixtures
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.markdown.FlexmarkRenderer
import com.plainbase.frameworks.markdown.FrontmatterReader
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Chunk-5 startup criteria against the committed fixture tree: every page indexed with a stable id
 * AND its §A4 canonical URL (the golden URL set, incl. `release notes 2026.md` →
 * `/docs/notes/release-notes-2026` and `réunion.md` → `/docs/notes/réunion`, percent-encoded on the
 * wire), the deeply nested page, and the ONE-PASS guarantee (each file read exactly once, its
 * frontmatter values parsed exactly once, each page rendered exactly once — counting-stub
 * assertions at all three seams).
 */
class IndexBuilderFixtureTest : FunSpec({

    test("golden URL set: the complete fixture page->url map matches §A4 construction") {
        IndexHarness(Fixtures.demoDocs).use { harness ->
            val snapshot = harness.builder.rebuild()

            val expected = GoldenTsv.load("/golden/canonical-urls.tsv").associate { (path, url) -> path to url }
            val actual = snapshot.pages.associate { it.path.value to (it.url ?: "<no-url>") }
            actual shouldContainExactly expected

            // The named criterion rows, asserted explicitly so a golden-file edit cannot soften them.
            actual["notes/release notes 2026.md"] shouldBe "/docs/notes/release-notes-2026"
            actual["notes/réunion.md"] shouldBe "/docs/notes/r%C3%A9union"
        }
    }

    test("every page has a stable id, and byId/byPath/byUrlPath agree") {
        IndexHarness(Fixtures.demoDocs).use { harness ->
            val snapshot = harness.builder.rebuild()
            snapshot.byId.size shouldBe snapshot.pages.size
            snapshot.byPath.size shouldBe snapshot.pages.size
            snapshot.byUrlPath.size shouldBe snapshot.pages.size // no collisions in fixtures
            // Stable across a rescan: same ids on rebuild (id_map round-trip).
            val ids = snapshot.pages.associate { it.path to it.id }
            harness.builder.rebuild().pages.associate { it.path to it.id } shouldBe ids
        }
    }

    test("notes/deeply/nested/folder/treasure.md is nested correctly with the full slugified URL") {
        IndexHarness(Fixtures.demoDocs).use { harness ->
            val snapshot = harness.builder.rebuild()
            val treasure = snapshot.byPath.getValue(TreePath.require("notes/deeply/nested/folder/treasure.md"))
            treasure.url shouldBe "/docs/notes/deeply/nested/folder/treasure"
            snapshot.byUrlPath.getValue(TreePath.require("notes/deeply/nested/folder/treasure")) shouldBe treasure
        }
    }

    test("redirect_from on deploy-guide registers an alias through the same URL construction") {
        IndexHarness(Fixtures.demoDocs).use { harness ->
            val snapshot = harness.builder.rebuild()
            val deployGuide = snapshot.byPath.getValue(TreePath.require("guides/deploy-guide.md"))
            harness.registry.find(TreePath.require("old/deployment")) shouldBe deployGuide.id
            harness.aliases.find(TreePath.require("old/deployment")) shouldBe deployGuide.id // persisted, not just in-memory
        }
    }

    test("one pass: each file read once, frontmatter values parsed once, and each page rendered once") {
        val reads = mutableMapOf<String, Int>()
        val renders = mutableMapOf<String, Int>()
        var frontmatterParses = 0
        val store = LocalContentStore(Fixtures.demoDocs)
        val counting = object : ContentStore by store {
            override fun read(path: TreePath): ByteArray? {
                reads.merge(path.value, 1, Int::plus)
                return store.read(path)
            }
        }
        // The §C2 value parse is counted at the FrontmatterParser seam — the builder's single
        // authoritative parse site. Render cannot re-extract behind this count: RenderedPage
        // carries no frontmatter field, so a re-parse would have nowhere to surface; the exact
        // per-page count below pins the builder to one parse each.
        val countingParser = object : FrontmatterParser {
            private val delegate = FrontmatterReader()
            override fun parse(source: ByteArray): Frontmatter {
                frontmatterParses += 1
                return delegate.parse(source)
            }
        }
        val countingFactory = { view: PageIndexView ->
            val delegate = FlexmarkRenderer(view)
            object : MarkdownRenderer {
                override fun render(sourcePath: TreePath, source: ByteArray): RenderedPage {
                    renders.merge(sourcePath.value, 1, Int::plus)
                    return delegate.render(sourcePath, source)
                }
            }
        }

        val harness = IndexHarness(
            Fixtures.demoDocs,
            contentStore = counting,
            frontmatterParser = countingParser,
            rendererFactory = countingFactory,
        )
        harness.use {
            val snapshot = harness.builder.rebuild()
            reads.size shouldBe snapshot.pages.size
            reads.filterValues { it != 1 }.keys.shouldBeEmpty()
            frontmatterParses shouldBe snapshot.pages.size
            renders.size shouldBe snapshot.pages.size
            renders.filterValues { it != 1 }.keys.shouldBeEmpty()
            snapshot.pages.forEach { page -> reads[page.path.value].shouldNotBeNull() }
        }
    }
})
