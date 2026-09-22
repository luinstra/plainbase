package com.plainbase.frameworks.markdown

import com.vladsch.flexmark.ast.BlockQuote
import com.vladsch.flexmark.ast.Paragraph
import com.vladsch.flexmark.ast.RefNode
import com.vladsch.flexmark.html.HtmlWriter
import com.vladsch.flexmark.html.renderer.CoreNodeRenderer
import com.vladsch.flexmark.html.renderer.DelegatingNodeRendererFactory
import com.vladsch.flexmark.html.renderer.NodeRenderer
import com.vladsch.flexmark.html.renderer.NodeRendererContext
import com.vladsch.flexmark.html.renderer.NodeRenderingHandler
import com.vladsch.flexmark.util.ast.Document
import com.vladsch.flexmark.util.data.DataHolder

/** The five deliberately narrow, fixed-format GitHub-style alert types. */
internal enum class CalloutType(val wireValue: String, val title: String) {
    NOTE("note", "NOTE"),
    TIP("tip", "TIP"),
    IMPORTANT("important", "IMPORTANT"),
    WARNING("warning", "WARNING"),
    CAUTION("caution", "CAUTION"),
}

internal class CalloutMetadata private constructor(private val types: Map<BlockQuote, CalloutType>) {
    fun typeOf(node: BlockQuote): CalloutType? = types[node]

    companion object {
        fun of(types: Map<BlockQuote, CalloutType>): CalloutMetadata = CalloutMetadata(types.toMap())
    }
}

/**
 * Adapts only the first literal marker line in a parsed blockquote. The body nodes stay in the original
 * AST, so links, headings, nested containers, source slices, and the section collector retain Flexmark's
 * normal behavior. No modified Markdown is reparsed and no generated body HTML is trusted as raw HTML.
 */
internal object CalloutAdapter {
    private val marker = Regex("^\\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)](?:[ \\t]*|[ \\t]*\\\\)$")

    fun apply(document: Document): CalloutMetadata {
        val recognized = linkedMapOf<BlockQuote, CalloutType>()
        document.descendants.filterIsInstance<BlockQuote>().toList().forEach { quote ->
            recognize(document, quote)?.let { recognized[quote] = it }
        }
        return CalloutMetadata.of(recognized)
    }

    private fun recognize(document: Document, quote: BlockQuote): CalloutType? {
        val paragraph = quote.firstChild as? Paragraph ?: return null
        val chars = paragraph.chars.toString()
        val lineBreak = chars.indexOfAny(charArrayOf('\r', '\n'))
        val line = if (lineBreak < 0) chars else chars.substring(0, lineBreak)
        val match = marker.matchEntire(line) ?: return null
        val lineBoundary = paragraph.startOffset + if (lineBreak < 0) chars.length else lineBreak + lineEndingLength(chars, lineBreak)
        if (
            paragraph.children.any { child ->
                child.startOffset < lineBoundary && child is RefNode && child.getReferenceNode(document) != null
            }
        ) {
            return null
        }

        var child = paragraph.firstChild
        while (child != null) {
            val next = child.next
            if (child.startOffset < lineBoundary) child.unlink()
            child = next
        }
        if (!paragraph.hasChildren()) paragraph.unlink()
        return CalloutType.valueOf(match.groupValues[1])
    }

    private fun lineEndingLength(chars: String, index: Int): Int = if (chars[index] == '\r' && chars.getOrNull(index + 1) == '\n') 2 else 1
}

/** Renders recognized callouts and delegates ordinary blockquotes to Flexmark's core renderer. */
internal class CalloutNodeRenderer(private val metadata: CalloutMetadata) : NodeRenderer {
    override fun getNodeRenderingHandlers(): Set<NodeRenderingHandler<*>> =
        setOf(NodeRenderingHandler(BlockQuote::class.java, this::render))

    private fun render(node: BlockQuote, context: NodeRendererContext, html: HtmlWriter) {
        val type = metadata.typeOf(node)
        if (type == null) {
            context.delegateRender()
            return
        }
        html.withAttr()
            .attr("class", "pb-callout")
            .attr("data-pb-callout", type.wireValue)
            .attr("role", "note")
            .tag("div")
        html.withAttr().attr("class", "pb-callout-title").tagLine("p", { html.text(type.title) })
        context.renderChildren(node)
        html.closeTag("div")
    }

    class Factory(private val metadata: CalloutMetadata) : DelegatingNodeRendererFactory {
        override fun apply(options: DataHolder): NodeRenderer = CalloutNodeRenderer(metadata)

        override fun getDelegates(): Set<Class<*>> = setOf(CoreNodeRenderer.Factory::class.java)
    }
}
