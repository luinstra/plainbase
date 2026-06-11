package com.plainbase.frameworks.markdown

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.Frontmatter
import com.plainbase.domain.service.FixtureIndexStub
import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Acceptance criterion 4 — the M2 frontmatter bridging rule. The authoritative [FrontmatterBlock]
 * detector decides what is frontmatter; flexmark's lenient yaml-front-matter extension never sees
 * the raw file head and so can never disagree. These tests pin the three known divergence
 * candidates where flexmark's own notion WOULD differ from ours:
 *
 *  - **trailing-space opener** (`---␠`): NOT a frontmatter opener per §A3 (the first line must be
 *    exactly `---`) → the block is absent, so `---␠` renders as an ordinary thematic break (`<hr/>`)
 *    in the body and the following `title:` line is just paragraph text;
 *  - **`...` closer**: the detector accepts `...` as a closing delimiter (§A3) and the values still
 *    parse;
 *  - **BOM-prefixed opener**: detected after the 3 BOM bytes, frontmatter recognized, body clean.
 */
class FrontmatterBridgeTest : FunSpec({

    val renderer = FlexmarkRenderer(FixtureIndexStub(Fixtures.demoDocs))
    val sourcePath = TreePath.require("notes/just-text.md")
    val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    test("trailing-space opener is NOT frontmatter — the head is body content, no values parsed") {
        // First line is `--- ` (a trailing space) → our grammar rejects it as an opener, so the
        // whole input is body. A blank line after the `--- ` makes it an unambiguous thematic break
        // and `title: …` an ordinary paragraph — i.e. the head was NOT consumed as frontmatter.
        val source = "--- \n\ntitle: Not Frontmatter\n\n# Body\n"
        val page = renderer.render(sourcePath, source.toByteArray())
        page.frontmatter shouldBe Frontmatter.EMPTY
        // `--- ` parsed by the body Markdown parser as a thematic break.
        page.html shouldContain "<hr"
        // The would-be `title:` is ordinary body text, never a parsed value.
        page.html shouldContain "title: Not Frontmatter"
    }

    test("... closer is accepted by the detector and values still parse") {
        val source = "---\ntitle: Dotted Close\nslug: dotted\n...\n\n# Body\n"
        val page = renderer.render(sourcePath, source.toByteArray())
        page.frontmatter.values shouldContainKey "title"
        page.frontmatter.scalar("title") shouldBe "Dotted Close"
        page.frontmatter.scalar("slug") shouldBe "dotted"
        // The closer is consumed, not rendered into the body.
        page.html shouldContain "<h1"
    }

    test("BOM-prefixed opener is detected after byte 3 and frontmatter is recognized") {
        val body = "---\ntitle: With BOM\n---\n\n# Body\n".toByteArray()
        val source = bom + body
        val page = renderer.render(sourcePath, source)
        page.frontmatter.scalar("title") shouldBe "With BOM"
        // The body renders cleanly — the BOM and block are stripped, not leaked into the HTML.
        page.html shouldContain "<h1"
        page.html.contains("title: With BOM") shouldBe false
    }
})
