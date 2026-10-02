package com.plainbase.frameworks.markdown

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.discussion.AnchorSelection
import com.plainbase.domain.discussion.QuoteCapture
import com.plainbase.domain.discussion.SelectionRequest
import com.plainbase.domain.discussion.SelectionResolver
import com.plainbase.domain.discussion.SelectionResult
import com.plainbase.domain.page.FrontmatterBlock
import com.plainbase.domain.page.PageIndexView
import com.plainbase.domain.render.SourceBlockKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private val selectionPageIndexStub = object : PageIndexView {
    override fun kindOf(path: TreePath): PageIndexView.EntryKind? = null
    override fun pageUrl(page: TreePath): String = "/docs/" + page.value
    override fun assetUrl(asset: TreePath): String = "/assets/" + asset.value
}

class SelectionResolverRenderedTest : FunSpec({
    val renderer = FlexmarkRenderer(selectionPageIndexStub)
    val raw = "\uFEFF---\r\ntitle: T\r\n---\r\nAlpha é 漢 😀 one.\r\n\r\nBeta two.\r\n".encodeToByteArray()

    test("rendered block offsets narrow a multibyte selection") {
        val page = renderer.render(TreePath.require("selection.md"), raw)
        val block = page.blocks.first { it.kind == SourceBlockKind.PARAGRAPH }
        val result = SelectionResolver.resolve(
            raw,
            page.blocks,
            page.headings,
            SelectionRequest.Spa(block.start.toLong(), block.end.toLong(), "é 漢 😀"),
        )
        val capture = result.capture()
        capture.selection shouldBe AnchorSelection.NARROWED
        raw.copyOfRange(capture.byteStart.toInt(), capture.byteEnd.toInt()).decodeToString() shouldBe "é 漢 😀"
        capture.bodyStart shouldBe FrontmatterBlock.detect(raw).bodyStart.toLong()
    }

    test("snapping to a rendered paragraph keeps its crlf") {
        val page = renderer.render(TreePath.require("selection.md"), raw)
        val block = page.blocks.first { it.kind == SourceBlockKind.PARAGRAPH }
        val result = SelectionResolver.resolve(
            raw,
            page.blocks,
            page.headings,
            SelectionRequest.Spa(block.start.toLong(), block.end.toLong(), "not present"),
        )
        val capture = result.capture()
        capture.selection shouldBe AnchorSelection.SNAPPED
        capture.quote.endsWith("\r\n") shouldBe true
    }
})

private fun SelectionResult.capture(): QuoteCapture = when (this) {
    is SelectionResult.Resolved -> capture
    is SelectionResult.Refused -> error("expected a resolved quote, got ${refusal.code}")
}
