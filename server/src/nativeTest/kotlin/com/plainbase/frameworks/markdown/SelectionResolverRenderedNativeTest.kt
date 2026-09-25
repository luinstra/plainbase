package com.plainbase.frameworks.markdown

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.discussion.AnchorSelection
import com.plainbase.domain.discussion.SelectionRequest
import com.plainbase.domain.discussion.SelectionResolver
import com.plainbase.domain.discussion.SelectionResult
import com.plainbase.domain.page.FrontmatterBlock
import com.plainbase.domain.page.PageIndexView
import com.plainbase.domain.render.SourceBlockKind
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

private val nativeSelectionPageIndexStub = object : PageIndexView {
    override fun kindOf(path: TreePath): PageIndexView.EntryKind? = null
    override fun pageUrl(page: TreePath): String = "/docs/" + page.value
    override fun assetUrl(asset: TreePath): String = "/assets/" + asset.value
}

@Tag("native")
class SelectionResolverRenderedNativeTest {

    @Test
    fun renderedBlockOffsetsNarrowMultibyteSelection() {
        val raw = "\uFEFF---\r\ntitle: T\r\n---\r\nAlpha é 漢 😀 one.\r\n\r\nBeta two.\r\n".encodeToByteArray()
        val renderer = FlexmarkRenderer(nativeSelectionPageIndexStub)
        val page = renderer.render(TreePath.require("selection.md"), raw)
        val block = page.blocks.first { it.kind == SourceBlockKind.PARAGRAPH }

        val result = SelectionResolver.resolve(
            raw,
            page.blocks,
            page.headings,
            SelectionRequest.Spa(block.start.toLong(), block.end.toLong(), "é 漢 😀"),
        )

        val capture = (result as? SelectionResult.Resolved)?.capture ?: fail("expected a resolved selection")
        assertEquals(AnchorSelection.NARROWED, capture.selection)
        assertEquals("é 漢 😀", raw.decodeToString(capture.byteStart.toInt(), capture.byteEnd.toInt(), true))
        assertEquals(FrontmatterBlock.detect(raw).bodyStart.toLong(), capture.bodyStart)
    }
}
