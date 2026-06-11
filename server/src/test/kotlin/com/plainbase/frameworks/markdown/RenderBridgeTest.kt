package com.plainbase.frameworks.markdown

import com.plainbase.domain.content.Nfc
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.render.GoldenTsv
import com.plainbase.domain.service.FixtureIndexStub
import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * Acceptance criterion 1 — the spec-conformance BRIDGE. Chunk 2 froze the renderer-independent
 * goldens (`heading-ids.tsv`, `link-resolution.tsv`) and proved the domain slugger/resolver against
 * them. This test proves the chunk-3 **flexmark wiring reproduces that frozen spec**: rendering
 * generated Markdown for every golden row yields exactly the golden ids in the HTML `id` attributes,
 * and exactly the golden hrefs in the HTML — the wiring adds nothing and drops nothing.
 *
 * Goldens are FROZEN; this test never edits them. A divergence here is a wiring bug, not a spec change.
 */
class RenderBridgeTest : FunSpec({

    val renderer = FlexmarkRenderer(FixtureIndexStub(Fixtures.demoDocs))
    val sourcePath = TreePath.require("guides/deploy-guide.md")

    // ---- Heading-id bridge: one page per allocator group, ids must match in document order --------

    val headingRows = GoldenTsv.load("/golden/heading-ids.tsv")
    headingRows.groupBy { it[0] }.forEach { (group, rows) ->
        test("heading-id bridge [$group]: rendered HTML ids reproduce the frozen goldens") {
            // Each group is one page (one allocator namespace); render its headings in document order.
            val markdown = rows.joinToString("\n") { it[1] }
            val page = renderer.render(sourcePath, markdown.toByteArray())

            val expectedIds = rows.map { Nfc.normalize(it[3]) }
            // The renderer's allocated heading ids (the same list it injects into the HTML id attributes).
            page.headings.map { it.id } shouldContainExactly expectedIds
            // And those ids actually appear as HTML `id="…"` attributes (the wiring, end to end).
            val htmlIds = ID_ATTR.findAll(page.html).map { it.groupValues[1] }.toList()
            htmlIds shouldContainExactly expectedIds
        }
    }

    // ---- Link bridge: rendered hrefs/srcs reproduce the frozen resolution URLs --------------------

    val linkRows = GoldenTsv.load("/golden/link-resolution.tsv")
    linkRows.forEach { row ->
        val rowNo = row.getOrElse(0) { "" }
        val rowSource = TreePath.require(row.getOrElse(1) { "" })
        val link = row.getOrElse(2) { "" }
        val outcomeClass = row.getOrElse(3) { "" }
        val expectedUrl = row.getOrElse(4) { "" }

        // A few golden rows are NOT renderable as CommonMark inline links — the empty target `[]()`
        // (row 24) and any destination containing a raw space (`#100% done`, row 31) parse as literal
        // text, so flexmark produces no link node. Those decode/resolve rules are fully exercised by
        // the renderer-INDEPENDENT chunk-2 golden test; the render bridge proves only that flexmark
        // hands us every link it DOES parse and we reproduce its outcome.
        if (link.isEmpty() || link.any { it == ' ' }) return@forEach

        test("link bridge row $rowNo: \"$link\" renders as $outcomeClass") {
            // A single image row (asset) exercises `src`; everything else is a link (`href`).
            val isImage = outcomeClass == "asset"
            val markdown = if (isImage) "![alt]($link)" else "[text]($link)"
            val page = renderer.render(rowSource, markdown.toByteArray())

            when (outcomeClass) {
                "page", "asset", "external", "anchor" -> {
                    val attr = if (isImage) "src" else "href"
                    attrValue(page.html, attr) shouldBe expectedUrl
                    page.html.contains("data-pb-link-error") shouldBe false
                }
                else -> {
                    // Broken/blocked: inert — the error class is present, the navigable attribute is gone.
                    errorClass(page.html) shouldBe outcomeClass
                    page.html.contains("href=") shouldBe false
                    page.html.contains("src=") shouldBe false
                }
            }
        }
    }
})

private val ID_ATTR = Regex("""<h[1-6][^>]*\bid="([^"]*)"""")

private fun attrValue(html: String, attr: String): String? =
    Regex("""\b$attr="([^"]*)"""").find(html)?.groupValues?.get(1)

private fun errorClass(html: String): String? =
    Regex("""data-pb-link-error="([^"]*)"""").find(html)?.groupValues?.get(1)
