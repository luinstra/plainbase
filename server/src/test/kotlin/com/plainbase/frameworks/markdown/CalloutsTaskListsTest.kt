package com.plainbase.frameworks.markdown

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.service.FixtureIndexStub
import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class CalloutsTaskListsTest : FunSpec({
    val renderer = FlexmarkRenderer(FixtureIndexStub(Fixtures.demoDocs))
    val sourcePath = TreePath.require("callouts/tasks.md")

    fun render(markdown: String) = renderer.render(sourcePath, markdown.toByteArray())

    test("renders every supported callout with a fixed accessible title") {
        val page = render(
            """
            > [!NOTE]
            > note body

            > [!TIP]
            > tip body

            > [!IMPORTANT]
            > important body

            > [!WARNING]  
            > warning body

            > [!CAUTION]\
            > caution body
            """.trimIndent(),
        )

        page.html shouldContain "data-pb-callout=\"note\""
        page.html shouldContain "data-pb-callout=\"tip\""
        page.html shouldContain "data-pb-callout=\"important\""
        page.html shouldContain "data-pb-callout=\"warning\""
        page.html shouldContain "data-pb-callout=\"caution\""
        page.html shouldContain "<p class=\"pb-callout-title\">NOTE</p>"
        page.html shouldContain "<p class=\"pb-callout-title\">CAUTION</p>"
        page.html shouldNotContain "[!NOTE]"
        page.sections.single().text shouldBe "note body\ntip body\nimportant body\nwarning body\ncaution body"
    }

    test("recognizes marker-only, CRLF, and lazy-continuation callouts") {
        val markerOnly = render("> [!NOTE]\r\n>\r\n")
        markerOnly.html shouldContain "data-pb-callout=\"note\""
        markerOnly.html shouldContain "<p class=\"pb-callout-title\">NOTE</p>"
        markerOnly.sections.shouldBeEmpty()

        val lazy = render("> [!TIP]\n> first line\ncontinued after a lazy continuation\n")
        lazy.html shouldContain "data-pb-callout=\"tip\""
        lazy.html shouldContain "continued after a lazy continuation"
        lazy.sections.single().text shouldBe "first line\ncontinued after a lazy continuation"
    }

    test("keeps ordinary, unknown, later, escaped, entity, code, and defined-reference markers ordinary") {
        val markdown = """
            > [!note]
            > lower

            > [!UNKNOWN]
            > unknown

            > before
            > [!NOTE]

            > \[!TIP]
            > escaped

            > &lbrack;!NOTE]
            > entity

            > `[!WARNING]`

            > [!CAUTION] same line stays ordinary

            > [!NOTE]

            [!NOTE]: https://example.test
        """.trimIndent()
        val html = render(markdown).html
        html shouldNotContain "data-pb-callout"
        html shouldContain "[!UNKNOWN]"
        html shouldContain "[!TIP]"
        html shouldContain "[!WARNING]"
        html shouldContain "[!CAUTION] same line stays ordinary"
        html shouldContain "href=\"https://example.test\""
    }

    test("renders checked, unchecked, nested, ordered, and loose task items with source text once") {
        val page = render(
            """
            - [ ] outer open
              - [x] nested done
              - [X] nested uppercase
            1. [x] ordered done
            2. [ ] ordered open

            - [ ] loose open

              continuation

            inline [x] prose and `- [ ] code`
            """.trimIndent(),
        )
        val html = page.html
        html shouldContain "class=\"task-list-item-checkbox\""
        html shouldContain "aria-label=\"Completed task\""
        html shouldContain "aria-label=\"Incomplete task\""
        html shouldContain "checked=\"checked\""
        html shouldContain "disabled=\"disabled\""
        html shouldContain "outer open"
        html shouldContain "nested done"
        html shouldContain "nested uppercase"
        html shouldContain "inline [x] prose"
        html shouldContain "- [ ] code"
        page.sections.single().text shouldBe
            "outer open\n\n\nnested done\n\nnested uppercase\nordered done\n\nordered open\nloose open\n\ncontinuation\ninline x prose and - [ ] code"
    }

    test("preserves headings, links, nested callouts, and blocked-link behavior in one AST") {
        val page = render(
            """
            # Before

            > [!NOTE]
            > See [after](https://example.test) and [blocked](javascript:alert(1)).
            >
            > > [!TIP]
            > > Nested body

            ## After
            """.trimIndent(),
        )
        page.headings.map { it.id } shouldContainExactly listOf("before", "after")
        page.html shouldContain "href=\"https://example.test\""
        page.html shouldContain "data-pb-link-error=\"blocked_scheme\""
        page.html shouldContain "data-pb-callout=\"tip\""
        page.sections.map { it.headingId } shouldContainExactly listOf("before", "after")
        page.sections.map { it.text } shouldContainExactly listOf("See after and blocked.\n\n\nNested body", "")
    }

    test("keeps duplicate heading ids, body text, references, and hostile HTML exact across callout containers") {
        val page = render(
            """
            # Duplicate

            > [!NOTE]
            > ## Duplicate
            > Body once
            > [blocked][bad]
            >
            > <script>alert(1)</script>

            ## Duplicate

            [bad]: javascript:alert(1)
            """.trimIndent(),
        )

        page.headings.map { it.id } shouldContainExactly listOf("duplicate", "duplicate-1", "duplicate-2")
        page.sections.map { it.headingId } shouldContainExactly listOf("duplicate", "duplicate-1", "duplicate-2")
        page.sections.map { it.text } shouldContainExactly listOf("", "Body once\nblocked", "")
        page.html shouldContain "id=\"duplicate\""
        page.html shouldContain "id=\"duplicate-1\""
        page.html shouldContain "id=\"duplicate-2\""
        page.html.split("Body once").size - 1 shouldBe 1
        page.html shouldContain "data-pb-link-error=\"blocked_scheme\""
        page.html shouldContain "&lt;script&gt;alert(1)&lt;/script&gt;"
    }

    test("renders the same callout deterministically on repeated renders") {
        val markdown = "> [!NOTE]\n> Stable body\n"
        render(markdown) shouldBe render(markdown)
    }
})
