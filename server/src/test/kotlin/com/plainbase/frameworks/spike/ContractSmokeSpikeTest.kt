package com.plainbase.frameworks.spike

import com.plainbase.domain.content.Nfc
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.LinkOutcome
import com.plainbase.domain.model.LinkOutcome.BrokenReason
import com.plainbase.domain.render.GoldenTsv
import com.plainbase.domain.render.HeadingIdAllocator
import com.plainbase.domain.service.FixtureIndexStub
import com.plainbase.domain.service.LinkResolver
import com.plainbase.frameworks.filesystem.Fixtures
import com.vladsch.flexmark.ast.Heading
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.ast.Node
import com.vladsch.flexmark.util.ast.TextCollectingVisitor
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Contract-smoke spike (chunk 2). The goldens were authored as PREDICTIONS transcribed from the §A1/§A2
 * spec tables BEFORE any flexmark code existed; this spike turns those predictions into FACTS by
 * driving every row through flexmark's real AST and the domain slugger/resolver.
 *
 * This is the ONE place chunk 2 touches flexmark, and it lives OUTSIDE `domain/` (renderer-independence
 * of the golden/domain tests stays structurally intact). For PB-SLUG-1 it parses each heading's
 * Markdown, extracts the heading node's text content per §A1's input rule, and asserts (a) the extracted
 * text equals the golden `text` column and (b) the slugger maps it to the golden id. For PB-LINK-1 it
 * runs the resolver against the fixture stub.
 *
 * Anti-circularity: this spike VALIDATES the spec's predictions; it never GENERATES goldens from
 * renderer output. Any divergence between a prediction and flexmark reality is a finding for the spike
 * report (correctable only inside the chunk-2 free window).
 */
class ContractSmokeSpikeTest : FunSpec({

    val parser = Parser.builder().build()

    // ---- PB-SLUG-1: parse heading via flexmark, extract text, slug it ----------------------------

    val headingRows = GoldenTsv.load("/golden/heading-ids.tsv")
    val allocators = HashMap<String, HeadingIdAllocator>()

    headingRows.forEach { row ->
        val group = row[0]
        val headingMd = row[1]
        val expectedText = row[2]
        val expectedId = Nfc.normalize(row[3])

        test("SLUG spike [$group]: flexmark text of `$headingMd` == \"$expectedText\" -> $expectedId") {
            val extracted = extractHeadingText(parser, headingMd)
            // (a) the §A1 text-content prediction matches what flexmark yields.
            Nfc.normalize(extracted) shouldBe Nfc.normalize(expectedText)
            // (b) the slugger maps that text to the golden id (allocated within the page group).
            val allocator = allocators.getOrPut(group) { HeadingIdAllocator() }
            allocator.allocate(extracted) shouldBe expectedId
        }
    }

    // ---- PB-LINK-1: resolve every row against the fixture stub -----------------------------------

    val linkRows = GoldenTsv.load("/golden/link-resolution.tsv")
    val resolver = LinkResolver(FixtureIndexStub(Fixtures.demoDocs))

    linkRows.forEach { row ->
        val padded = (row + List(5) { "" }).take(5)
        val rowNo = padded[0]
        val sourcePath = TreePath.require(padded[1])
        val link = padded[2]
        val expectedClass = padded[3]
        val expectedUrl = padded[4]

        test("LINK spike row $rowNo: \"$link\" -> $expectedClass") {
            val outcome = resolver.resolve(sourcePath, link)
            assertLink(outcome, expectedClass, expectedUrl)
        }
    }
})

/**
 * Extracts a heading's §A1 text content from its Markdown source using flexmark's AST. flexmark's
 * [TextCollectingVisitor] concatenates, in document order, the rendered text of text nodes, code-span
 * contents, and link/emphasis/strikethrough text, collapsing soft/hard breaks — which is exactly the
 * §A1 input rule for our golden rows. (The spike asserts this prediction explicitly; any divergence —
 * e.g. image alt text or inline-HTML handling differing from §A1 — surfaces as a failing row, which is
 * the spike's purpose.)
 */
private fun extractHeadingText(parser: Parser, headingMarkdown: String): String {
    val document = parser.parse(headingMarkdown)
    val heading = firstHeading(document) ?: error("no heading parsed from: $headingMarkdown")
    return TextCollectingVisitor().collectAndGetText(heading)
}

private fun firstHeading(node: Node): Heading? {
    if (node is Heading) return node
    var child = node.firstChild
    while (child != null) {
        firstHeading(child)?.let { return it }
        child = child.next
    }
    return null
}

private fun assertLink(outcome: LinkOutcome, expectedClass: String, expectedUrl: String) {
    when (expectedClass) {
        "page" -> {
            check(outcome is LinkOutcome.Resolved.Page) { "expected page, got $outcome" }
            outcome.url shouldBe expectedUrl
        }
        "asset" -> {
            check(outcome is LinkOutcome.Resolved.Asset) { "expected asset, got $outcome" }
            outcome.url shouldBe expectedUrl
        }
        "external" -> {
            check(outcome is LinkOutcome.Resolved.External) { "expected external, got $outcome" }
            outcome.url shouldBe expectedUrl
        }
        "anchor" -> {
            check(outcome is LinkOutcome.Resolved.Anchor) { "expected anchor, got $outcome" }
            outcome.url shouldBe expectedUrl
        }
        else -> {
            check(outcome is LinkOutcome.Broken) { "expected broken($expectedClass), got $outcome" }
            outcome.reason shouldBe BrokenReason.entries.first { it.wireValue == expectedClass }
        }
    }
}
