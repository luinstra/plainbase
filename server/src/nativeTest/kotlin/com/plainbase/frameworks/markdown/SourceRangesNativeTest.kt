package com.plainbase.frameworks.markdown

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageIndexView
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val pageIndexStub = object : PageIndexView {
    override fun kindOf(path: TreePath): PageIndexView.EntryKind? = null

    override fun pageUrl(page: TreePath): String = "/docs/" + page.value

    override fun assetUrl(asset: TreePath): String = "/assets/" + asset.value
}

@Tag("native")
class SourceRangesNativeTest {

    @Test
    fun brokenLinkOccurrenceUsesStrictUtf8ByteBoundaries() {
        val source = "\uFEFF---\r\ntitle: Café\r\n---\r\n😀\r[first][missing]\n\n[missing]: lost.md\n".toByteArray(Charsets.UTF_8)
        val renderer = FlexmarkRenderer(pageIndexStub)
        val html = renderer.render(TreePath.require("links.md"), source).html
        val match = Regex("data-pb-link-src=\"(\\d+)-(\\d+)\"").find(html)!!
        val start = match.groupValues[1].toInt()
        val end = match.groupValues[2].toInt()
        assertEquals("[first][missing]", String(source, start, end - start, Charsets.UTF_8))
        val invalid = byteArrayOf(0xC3.toByte(), 0x28) + source
        assertFalse(renderer.render(TreePath.require("links.md"), invalid).html.contains("data-pb-link-src"))
    }

    @Test
    fun malformedUtf8DisablesRangesWhileReplacementRenderingRemainsAvailable() {
        val renderer = FlexmarkRenderer(pageIndexStub)
        val source = "before ".toByteArray(Charsets.UTF_8) +
            byteArrayOf(0xC3.toByte(), 0x28) +
            " after".toByteArray(Charsets.UTF_8)

        val page = renderer.render(TreePath.require("malformed.md"), source)

        assertTrue(page.html.contains('\uFFFD'.toString()))
        assertTrue(page.blocks.isEmpty())
        assertFalse(page.html.contains("data-pb-src"))
    }

    @Test
    fun validMultibyteUtf8RangeUsesByteOffsets() {
        val renderer = FlexmarkRenderer(pageIndexStub)
        val source = "é 漢 😀\n".toByteArray(Charsets.UTF_8)

        val page = renderer.render(TreePath.require("multibyte.md"), source)
        val paragraph = page.blocks.single()

        assertEquals(0, paragraph.start)
        assertEquals(source.size, paragraph.end)
        assertEquals("é 漢 😀\n", String(source, paragraph.start, paragraph.end - paragraph.start, Charsets.UTF_8))
    }
}
