package com.plainbase.frameworks.markdown

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.service.FixtureIndexStub
import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * Acceptance criterion 6 — the `headings` list (the §A4 `/html` payload, Phase 5 recovery + TOC).
 * For a fixture page it must match DOCUMENT ORDER, carry the correct level per heading, and the
 * §A1 text-content extraction (PB-SLUG-1's input rule: code-span/link/emphasis text kept, markup
 * stripped) feeding the allocated id.
 */
class HeadingsListTest : FunSpec({

    val renderer = FlexmarkRenderer(FixtureIndexStub(Fixtures.demoDocs))

    test("deploy-guide.md headings: document order, levels, and PB-SLUG-1 ids") {
        val rel = "guides/deploy-guide.md"
        val page = renderer.render(TreePath.require(rel), Files.readAllBytes(Fixtures.demoDocs.resolve(rel)))
        page.headings.map { Triple(it.id, it.level, it.text) } shouldContainExactly listOf(
            Triple("deploy-guide", 1, "Deploy Guide"),
            Triple("prerequisites", 2, "Prerequisites"),
            Triple("rolling-deploy", 2, "Rolling deploy"),
            Triple("rollback", 2, "Rollback"),
        )
    }

    test("PB-SLUG-1 text extraction keeps code-span / link / emphasis text and strips markup") {
        // Inline markup, a code span, and a link — §A1 keeps their TEXT, drops the delimiters/URL.
        val markdown = "# Use `git status` with **bold** and [a link](https://x.test)\n"
        val page = renderer.render(TreePath.require("notes/just-text.md"), markdown.toByteArray())
        page.headings.map { Triple(it.id, it.level, it.text) } shouldContainExactly listOf(
            Triple("use-git-status-with-bold-and-a-link", 1, "Use git status with bold and a link"),
        )
        page.headings.single().byteStart shouldBe 0
        page.headings.single().byteEnd shouldBe 61
    }
})
