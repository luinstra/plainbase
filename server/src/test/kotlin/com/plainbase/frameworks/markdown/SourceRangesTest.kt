package com.plainbase.frameworks.markdown

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.render.SourceBlock
import com.plainbase.domain.render.SourceBlockKind
import com.plainbase.domain.service.FixtureIndexStub
import com.plainbase.frameworks.filesystem.Fixtures
import com.vladsch.flexmark.ast.BlockQuote
import com.vladsch.flexmark.ast.BulletList
import com.vladsch.flexmark.ast.FencedCodeBlock
import com.vladsch.flexmark.ast.HtmlBlock
import com.vladsch.flexmark.ast.HtmlCommentBlock
import com.vladsch.flexmark.ast.IndentedCodeBlock
import com.vladsch.flexmark.ast.ListItem
import com.vladsch.flexmark.ast.OrderedList
import com.vladsch.flexmark.ast.Paragraph
import com.vladsch.flexmark.ast.ThematicBreak
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension
import com.vladsch.flexmark.ext.tables.TableBlock
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.ast.Block
import com.vladsch.flexmark.util.ast.Document
import com.vladsch.flexmark.util.ast.Node
import com.vladsch.flexmark.util.data.MutableDataSet
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import com.vladsch.flexmark.ast.Heading as FlexmarkHeading

class SourceRangesTest : FunSpec({

    val renderer = FlexmarkRenderer(FixtureIndexStub(Fixtures.demoDocs))
    val sourcePath = TreePath.require("discussions/source-ranges.md")

    test("broken link occurrence ranges identify exact bytes instead of the paragraph or reference definition") {
        val occurrences = listOf("[first](missing.md)", "[again][missing]", "![image](absent.png)")
        val source = "\uFEFF---\r\ntitle: Links\r\n---\r\nUnicode 😀 漢字\r\n" +
            "${occurrences[0]} and\r${occurrences[1]}\n${occurrences[2]}\n\n[missing]: missing.md\n"
        val bytes = source.toByteArray(Charsets.UTF_8)
        val page = renderer.render(sourcePath, bytes)
        val ranges = Regex("data-pb-link-src=\"(\\d+)-(\\d+)\"").findAll(page.html).toList()
        ranges.map { match ->
            bytes.copyOfRange(match.groupValues[1].toInt(), match.groupValues[2].toInt()).toString(Charsets.UTF_8)
        }.shouldContainExactly(occurrences)
        page.html shouldContain "data-pb-src="
        renderer.renderFragment(sourcePath, occurrences.first()) shouldNotContain "data-pb-link-src"
        renderer.render(sourcePath, byteArrayOf(0xFF.toByte()) + bytes).html shouldNotContain "data-pb-link-src"
    }

    test("source ranges map rendered blocks and headings to raw UTF-8 bytes") {
        val body = buildString {
            append("# First 😀\r\n\r\n")
            append("Decomposed e\u0301, CJK 漢字, NUL \u0000, and tab\t.\r")
            append("Hard break  \\\nmixed newline\n\n")
            append("Setext title\n--------------\r\n")
            append("# Duplicate\r# Duplicate\n\n")
            append("- [ ] task item\n  - [x] nested item\r\n\n")
            append("| left | right |\r\n| --- | --- |\n| table cell | CJK 漢字 |\r\n\n")
            append("> [!NOTE]\r\n> callout body 😀\r\n> continued\n\n")
            append("> ordinary blockquote\n> quote body\n\n")
            append("```mermaid\ngraph TD\n  A-->B\n```\r\n\n")
            append("    indented code\r\n\r\n")
            append("---\n\n")
            append("Escaped <script>alert(1)</script> and [reference][target].\n\n")
            append("[target]: https://example.test\n")
        }
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val frontmatter = "---\r\ntitle: Source ranges\n...\r\n".toByteArray(Charsets.UTF_8)
        val source = bom + frontmatter + body.toByteArray(Charsets.UTF_8)
        val bodyStart = bom.size + frontmatter.size
        val sourceDocument = Parser.builder(
            MutableDataSet().set(
                Parser.EXTENSIONS,
                listOf(TablesExtension.create(), StrikethroughExtension.create(), TaskListExtension.create()),
            ),
        ).build().parse(body)
        val sourceBlocks = sourceDocument.descendants.filterIsInstance<Block>().filterNot { it is Document }

        val page = renderer.render(sourcePath, source)

        page.blocks.shouldNotBeEmpty()
        page.headings.map { it.text }
            .filter { it == "Duplicate" }
            .shouldContainExactly(listOf("Duplicate", "Duplicate"))
        page.headings.forEach { heading ->
            val start = requireNotNull(heading.byteStart)
            val end = requireNotNull(heading.byteEnd)
            val headingBlock =
                page.blocks.singleOrNull { it.kind == SourceBlockKind.HEADING && it.start == start && it.end == end }
                    ?: error("No heading source block for ${heading.id}: $start-$end")
            sourceText(source, headingBlock).trimEnd('\r', '\n') shouldContain heading.text
        }

        val blockValues = page.blocks.map { "${it.start}-${it.end}" }
        val emittedValues = SOURCE_ATTRIBUTE.findAll(page.html).map { it.groupValues[1] }.toList()
        blockValues.toSet().shouldContainAll(emittedValues.toSet())
        emittedValues.toSet().shouldContainAll(blockValues.toSet())
        emittedValues.shouldHaveSize(page.blocks.size)
        page.blocks.forEach { block ->
            block.start shouldBeGreaterThan bodyStart - 1
            block.end shouldBeGreaterThan block.start
            source.size shouldBeGreaterThan block.end - 1
            val sourceNode = sourceBlocks.firstOrNull { node ->
                val nodeStart = bodyStart + body.substring(0, sourceStartOffset(body, node)).toByteArray(Charsets.UTF_8).size
                val nodeEnd = bodyStart + body.substring(0, node.endOffset).toByteArray(Charsets.UTF_8).size
                nodeStart == block.start && nodeEnd == block.end && sourceNodeKind(node) == block.kind
            } ?: error("No source AST block matched $block")
            val expected = body.substring(sourceStartOffset(body, sourceNode), sourceNode.endOffset).toByteArray(Charsets.UTF_8)
            source.copyOfRange(block.start, block.end).toList() shouldBe expected.toList()
        }

        val representativeStarts: List<Pair<SourceBlockKind, String>> = listOf(
            SourceBlockKind.HEADING to "# First 😀",
            SourceBlockKind.PARAGRAPH to "Decomposed e\u0301",
            SourceBlockKind.LIST to "- [ ] task item",
            SourceBlockKind.TABLE to "| left | right |",
            SourceBlockKind.BLOCKQUOTE to "> [!NOTE]",
            SourceBlockKind.CODE to "\u0060\u0060\u0060mermaid",
            SourceBlockKind.CODE to "    indented code",
            SourceBlockKind.THEMATIC_BREAK to "---\n\n",
        )
        representativeStarts.forEach { (kind, snippet) ->
            val startOffsets = source.offsetsOf(snippet.toByteArray(Charsets.UTF_8)).filter { it >= bodyStart }
            startOffsets.shouldNotBeEmpty()
            startOffsets.forEach { expectedStart ->
                val block =
                    page.blocks.singleOrNull { it.kind == kind && it.start == expectedStart }
                        ?: error("No $kind source block at byte $expectedStart; blocks=${page.blocks}")
                val sourceNode = sourceBlocks.singleOrNull { node ->
                    val nodeStart = bodyStart + body.substring(0, sourceStartOffset(body, node)).toByteArray(Charsets.UTF_8).size
                    nodeStart == block.start && sourceNodeKind(node) == block.kind
                } ?: error("No source AST block matched representative $block")
                val expected = body.substring(sourceStartOffset(body, sourceNode), sourceNode.endOffset).toByteArray(Charsets.UTF_8)
                source.copyOfRange(block.start, block.end).toList() shouldBe expected.toList()
            }
        }

        listOf(
            "# First 😀",
            "Decomposed e\u0301",
            "CJK 漢字",
            "NUL \u0000",
            "task item",
            "table cell",
            "[!NOTE]",
            "callout body 😀",
            "ordinary blockquote",
            "graph TD",
            "indented code",
            "Escaped <script>",
            "[reference][target]",
        ).forEach { expected ->
            page.blocks.map { sourceText(source, it) }.joinToString("\n") shouldContain expected
        }

        page.html shouldContain "data-pb-callout=\"note\""
        page.html shouldContain "task-list-item-checkbox"
        page.html shouldContain "<table"
        page.html shouldContain "<hr"
        page.html shouldContain "href=\"https://example.test\""
        page.html shouldContain "&lt;script&gt;"
        val preTags = PRE_TAG.findAll(page.html).map { it.value }.toList()
        listOf("\u0060".repeat(3) + "mermaid", "    indented code").forEach { snippet ->
            val range =
                page.blocks.singleOrNull { it.kind == SourceBlockKind.CODE && sourceText(source, it).startsWith(snippet) }
                    ?: error("No code source block starting with $snippet")
            val rangeValue = range.start.toString() + "-" + range.end
            preTags.filter { it.contains("data-pb-src=\"" + rangeValue + "\"") }.shouldHaveSize(1)
        }
    }

    test("each mapped rendered block has one source attribute across the supported block corpus") {
        val source = """
            # Heading

            paragraph

            - ordinary tight one
            - ordinary tight two

            - ordinary loose one

              ordinary loose continuation

            - [ ] task tight one
            - [x] task tight two

            - [ ] task loose one

              task loose continuation

            <div>
            block HTML
            </div>

            <!-- top-level comment -->

            > [!TIP]
            > callout body

            ```kotlin
            val fenced = true
            ```

                top-level indented code

            >     blockquote indented code

            - list item with indented code

                  nested list indented code

            | left | right |
            | --- | --- |
            | one | two |

            ```mermaid
            flowchart LR
              Start --> Finish
            ```
        """.trimIndent().plus("\n")
        val page = renderer.render(sourcePath, source.toByteArray(Charsets.UTF_8))
        val blockRanges = page.blocks.map { "${it.start}-${it.end}" }
        val emittedRanges = SOURCE_ATTRIBUTE.findAll(page.html).map { it.groupValues[1] }.toList()

        blockRanges.toSet().shouldContainAll(emittedRanges.toSet())
        emittedRanges.toSet().shouldContainAll(blockRanges.toSet())
        emittedRanges.shouldContainExactly(blockRanges)
        page.blocks.map { it.kind }.shouldContainAll(
            listOf(
                SourceBlockKind.HEADING,
                SourceBlockKind.PARAGRAPH,
                SourceBlockKind.LIST,
                SourceBlockKind.LIST_ITEM,
                SourceBlockKind.HTML_BLOCK,
                SourceBlockKind.BLOCKQUOTE,
                SourceBlockKind.CODE,
                SourceBlockKind.TABLE,
            ),
        )
        page.html shouldContain Regex("""<p data-pb-src="\d+-\d+">\s*&lt;div&gt;""")
        page.html shouldContain Regex("""<p class="pb-callout-title">TIP</p>""")
        Regex("""<p class="pb-callout-title"[^>]*data-pb-src="""").findAll(page.html).toList().shouldBeEmpty()
        page.blocks.filter { it.kind == SourceBlockKind.LIST_ITEM }.shouldHaveSize(7)
        page.blocks.filter { it.kind == SourceBlockKind.HTML_BLOCK }.shouldHaveSize(2)
    }

    test("escaped HTML blocks preserve Flexmark core output and map container source spans") {
        val sources = listOf(
            "<div>\nTop-level HTML.\n</div>\n" to "<div>\nTop-level HTML.\n</div>\n",
            "<div>\r\nTop-level CRLF HTML.\r\n</div>\r\n" to "<div>\r\nTop-level CRLF HTML.\r\n</div>\r\n",
            "> <div>\n> Blockquote HTML.\n> </div>\n" to "<div>\n> Blockquote HTML.\n> </div>\n",
            "> <div>\r\n> Blockquote CRLF HTML.\r\n> </div>\r\n" to "<div>\r\n> Blockquote CRLF HTML.\r\n> </div>\r\n",
            "> [!NOTE]\n> <div>\n> Callout HTML.\n> </div>\n" to "<div>\n> Callout HTML.\n> </div>\n",
            "> [!NOTE]\r\n> <div>\r\n> Callout CRLF HTML.\r\n> </div>\r\n" to
                "<div>\r\n> Callout CRLF HTML.\r\n> </div>\r\n",
        )

        sources.forEach { (markdown, expectedRawBlock) ->
            val source = markdown.toByteArray(Charsets.UTF_8)
            val page = renderer.render(sourcePath, source)
            val htmlBlock = page.blocks.single { it.kind == SourceBlockKind.HTML_BLOCK }
            val rawBlock = sourceText(source, htmlBlock)

            rawBlock shouldBe expectedRawBlock
            page.html shouldNotContain "&gt; "
            page.html.replace(SOURCE_ATTRIBUTE_WITH_SPACE, "") shouldBe renderWithCore(markdown)
        }
    }

    test("escaped HTML comments carry source ranges across line endings and containers") {
        val sources = listOf(
            "<!-- top-level LF comment -->\n" to "<!-- top-level LF comment -->\n",
            "<!-- top-level CRLF comment -->\r\n" to "<!-- top-level CRLF comment -->\r\n",
            "> <!-- blockquote LF comment\n> continued comment -->\n" to
                "<!-- blockquote LF comment\n> continued comment -->\n",
            "> <!-- blockquote CRLF comment\r\n> continued comment -->\r\n" to
                "<!-- blockquote CRLF comment\r\n> continued comment -->\r\n",
            "> [!NOTE]\n> <!-- callout LF comment\n> continued comment -->\n" to
                "<!-- callout LF comment\n> continued comment -->\n",
            "> [!NOTE]\r\n> <!-- callout CRLF comment\r\n> continued comment -->\r\n" to
                "<!-- callout CRLF comment\r\n> continued comment -->\r\n",
        )

        sources.forEach { (markdown, expectedRawBlock) ->
            val source = markdown.toByteArray(Charsets.UTF_8)
            val page = renderer.render(sourcePath, source)
            val commentBlock = page.blocks.single { it.kind == SourceBlockKind.HTML_BLOCK }
            val range = "${commentBlock.start}-${commentBlock.end}"
            val rawBlock = sourceText(source, commentBlock)

            rawBlock shouldBe expectedRawBlock
            SOURCE_ATTRIBUTE.findAll(page.html).map { it.groupValues[1] }.toList().count { it == range } shouldBe 1
            page.html shouldContain "<p data-pb-src=\"$range\">"
            page.html shouldContain "&lt;!--"
            val coreWithCommentCarrier = ESCAPED_COMMENT.replace(renderWithCore(markdown)) { match ->
                "<p>${match.value}</p>"
            }
            page.html.replace(SOURCE_ATTRIBUTE_WITH_SPACE, "") shouldBe coreWithCommentCarrier
        }
    }

    test("literal source slices keep paragraph endings and distinguish fenced from indented code endings") {
        val source = """
            paragraph with a newline

                top-level indented code

            ```kotlin
            val fenced = true
            ```

            >     blockquote indented code

            - list item with indented code

                  nested list indented code
        """.trimIndent().plus("\n").toByteArray(Charsets.UTF_8)
        val page = renderer.render(sourcePath, source)

        val paragraph = page.blocks.single { it.kind == SourceBlockKind.PARAGRAPH }
        String(source, paragraph.start, paragraph.end - paragraph.start, Charsets.UTF_8) shouldBe "paragraph with a newline\n"

        val fenced = page.blocks.single { block ->
            block.kind == SourceBlockKind.CODE && sourceText(source, block).startsWith("```kotlin")
        }
        sourceText(source, fenced) shouldBe "```kotlin\nval fenced = true\n```"

        val codeSlices = page.blocks.filter { it.kind == SourceBlockKind.CODE }
            .map { block -> String(source, block.start, block.end - block.start, Charsets.UTF_8) }
        codeSlices.shouldContainAll(
            listOf(
                "    top-level indented code\n",
                ">     blockquote indented code\n",
                "      nested list indented code\n",
            ),
        )
    }

    test("callout wrapper range retains its removed marker line") {
        val source = "> [!TIP]\r\n> callout text\r\n".toByteArray(Charsets.UTF_8)
        val page = renderer.render(sourcePath, source)
        val range = CALL_OUT_ATTRIBUTE.find(page.html)?.groupValues?.get(1)

        range shouldBe page.blocks.single { it.kind == SourceBlockKind.BLOCKQUOTE }.let { "${it.start}-${it.end}" }
        val block = page.blocks.single { it.kind == SourceBlockKind.BLOCKQUOTE }
        val raw = sourceText(source, block)
        raw shouldContain "> [!TIP]"
        raw shouldContain "callout text"
    }

    test("callout body paragraph range retains the removed NOTE marker line") {
        val source = "> [!NOTE]\n> callout body\n".toByteArray(Charsets.UTF_8)
        val page = renderer.render(sourcePath, source)
        val paragraph = page.blocks.single { it.kind == SourceBlockKind.PARAGRAPH }

        sourceText(source, paragraph) shouldContain "[!NOTE]"
    }

    test("leading-indented blockquote code starts no earlier than its blockquote") {
        val source = "  >     code\n".toByteArray(Charsets.UTF_8)
        val page = renderer.render(sourcePath, source)
        val blockquote = page.blocks.single { it.kind == SourceBlockKind.BLOCKQUOTE }
        val code = page.blocks.single { it.kind == SourceBlockKind.CODE }

        code.start shouldBeGreaterThan blockquote.start - 1
    }

    test("leading-indented list item code starts no earlier than its list item") {
        val source = "  -     code\n".toByteArray(Charsets.UTF_8)
        val page = renderer.render(sourcePath, source)
        val item = page.blocks.single { it.kind == SourceBlockKind.LIST_ITEM }
        val code = page.blocks.single { it.kind == SourceBlockKind.CODE }

        code.start shouldBeGreaterThan item.start - 1
        code.end shouldBeLessThan item.end + 1
    }

    test("fragment rendering keeps leading thematic breaks and setext headings as body Markdown") {
        val html = renderer.renderFragment(sourcePath, "---\ntext\n---\n")

        html shouldBe "<hr />\n<h2>text</h2>\n"
        html shouldNotContain "id=\""
        html shouldNotContain "data-pb-src"
    }

    test("fragment rendering escapes HTML and keeps link rewriting") {
        val html = renderer.renderFragment(sourcePath, "<script>alert(1)</script>\n\n[blocked](javascript:alert(1))")

        html shouldContain "&lt;script&gt;alert(1)&lt;/script&gt;"
        html shouldContain "data-pb-link-error=\"blocked_scheme\""
        html shouldNotContain "href=\"javascript:"
        html shouldNotContain "id=\""
        html shouldNotContain "data-pb-src"
    }
})

private val SOURCE_ATTRIBUTE = Regex("data-pb-src=\"(\\d+-\\d+)\"")
private val CALL_OUT_ATTRIBUTE = Regex("data-pb-callout=\"tip\"[^>]*data-pb-src=\"(\\d+-\\d+)\"")
private val PRE_TAG = Regex("""<pre\b[^>]*>""")
private val SOURCE_ATTRIBUTE_WITH_SPACE = Regex(""" data-pb-src="\d+-\d+"""")
private val ESCAPED_COMMENT = Regex("""&lt;!--[\s\S]*?--&gt;""")

private fun sourceText(source: ByteArray, block: SourceBlock): String =
    String(source, block.start, block.end - block.start, Charsets.UTF_8)

private fun sourceNodeKind(node: Node): SourceBlockKind? =
    when (node) {
        is Paragraph -> if (node.parent is ListItem) null else SourceBlockKind.PARAGRAPH
        is FlexmarkHeading -> SourceBlockKind.HEADING
        is BlockQuote -> SourceBlockKind.BLOCKQUOTE
        is BulletList, is OrderedList -> SourceBlockKind.LIST
        is ListItem -> SourceBlockKind.LIST_ITEM
        is FencedCodeBlock, is IndentedCodeBlock -> SourceBlockKind.CODE
        is TableBlock -> SourceBlockKind.TABLE
        is ThematicBreak -> SourceBlockKind.THEMATIC_BREAK
        is HtmlBlock, is HtmlCommentBlock -> SourceBlockKind.HTML_BLOCK
        else -> null
    }

private fun sourceStartOffset(body: String, node: Node): Int {
    if (node !is IndentedCodeBlock) return node.startOffset
    var lineStart = node.startOffset
    while (lineStart > 0 && body[lineStart - 1] != '\n' && body[lineStart - 1] != '\r') {
        lineStart--
    }
    return lineStart
}

private fun ByteArray.offsetsOf(needle: ByteArray): List<Int> =
    (0..size - needle.size).filter { start ->
        needle.indices.all { index -> this[start + index] == needle[index] }
    }

private fun renderWithCore(markdown: String): String {
    val options =
        MutableDataSet()
            .set(
                Parser.EXTENSIONS,
                listOf(TablesExtension.create(), StrikethroughExtension.create(), TaskListExtension.create()),
            )
            .set(HtmlRenderer.ESCAPE_HTML, true)
            .set(HtmlRenderer.GENERATE_HEADER_ID, true)
            .set(HtmlRenderer.RENDER_HEADER_ID, true)
            .toImmutable()
    val document = Parser.builder(options).build().parse(markdown)
    val callouts = CalloutAdapter.apply(document)
    return HtmlRenderer.builder(options)
        .nodeRendererFactory(CalloutNodeRenderer.Factory(callouts))
        .build()
        .render(document)
}
