package com.plainbase.frameworks.markdown

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.LinkOutcome
import com.plainbase.domain.model.PageLink
import com.plainbase.domain.page.FrontmatterBlock
import com.plainbase.domain.page.Heading
import com.plainbase.domain.page.PageIndexView
import com.plainbase.domain.render.HeadingIdAllocator
import com.plainbase.domain.render.MarkdownRenderer
import com.plainbase.domain.render.RenderedPage
import com.plainbase.domain.render.RenderedSection
import com.plainbase.domain.render.SourceBlock
import com.plainbase.domain.render.SourceBlockKind
import com.plainbase.domain.service.LinkResolver
import com.vladsch.flexmark.ast.AutoLink
import com.vladsch.flexmark.ast.BlockQuote
import com.vladsch.flexmark.ast.BulletList
import com.vladsch.flexmark.ast.FencedCodeBlock
import com.vladsch.flexmark.ast.HtmlBlock
import com.vladsch.flexmark.ast.HtmlBlockBase
import com.vladsch.flexmark.ast.HtmlCommentBlock
import com.vladsch.flexmark.ast.Image
import com.vladsch.flexmark.ast.ImageRef
import com.vladsch.flexmark.ast.IndentedCodeBlock
import com.vladsch.flexmark.ast.Link
import com.vladsch.flexmark.ast.LinkNodeBase
import com.vladsch.flexmark.ast.LinkRef
import com.vladsch.flexmark.ast.ListItem
import com.vladsch.flexmark.ast.MailLink
import com.vladsch.flexmark.ast.OrderedList
import com.vladsch.flexmark.ast.Paragraph
import com.vladsch.flexmark.ast.RefNode
import com.vladsch.flexmark.ast.ThematicBreak
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension
import com.vladsch.flexmark.ext.tables.TableBlock
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.html.AttributeProvider
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.html.HtmlWriter
import com.vladsch.flexmark.html.IndependentAttributeProviderFactory
import com.vladsch.flexmark.html.renderer.AttributablePart
import com.vladsch.flexmark.html.renderer.CoreNodeRenderer
import com.vladsch.flexmark.html.renderer.DelegatingNodeRendererFactory
import com.vladsch.flexmark.html.renderer.HeaderIdGeneratorFactory
import com.vladsch.flexmark.html.renderer.HtmlIdGenerator
import com.vladsch.flexmark.html.renderer.LinkResolverContext
import com.vladsch.flexmark.html.renderer.NodeRenderer
import com.vladsch.flexmark.html.renderer.NodeRendererContext
import com.vladsch.flexmark.html.renderer.NodeRenderingHandler
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.ast.Block
import com.vladsch.flexmark.util.ast.Document
import com.vladsch.flexmark.util.ast.Node
import com.vladsch.flexmark.util.ast.TextCollectingVisitor
import com.vladsch.flexmark.util.data.DataHolder
import com.vladsch.flexmark.util.data.MutableDataSet
import com.vladsch.flexmark.util.html.MutableAttributes
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import com.vladsch.flexmark.ast.Heading as FlexmarkHeading

/**
 * The single [MarkdownRenderer] implementation — the ONLY place flexmark lives (single-renderer
 * rule, §5.8). It wires chunk 2's frozen PB-SLUG-1 slugger and PB-LINK-1 resolver into flexmark:
 *
 *  - **Ids:** a custom [HeaderIdGeneratorFactory] delegates every heading id to chunk 2's
 *    [HeadingIdAllocator] (flexmark's built-in `HeaderIdGenerator` is NOT used — its slug rules are
 *    not PB-SLUG-1). The body AST is walked once up front: headings get §A1 text via flexmark's
 *    [TextCollectingVisitor] (validated identical to the spec by the chunk-2 spike) and an allocated
 *    id; links/images get a [LinkOutcome] from chunk 2's [LinkResolver]. Both are stashed by node
 *    identity for the render hooks to read — one resolution pass, consumed twice.
 *  - **Links:** an [AttributeProvider] rewrites each link/image `href`/`src` to its resolved
 *    `/{root}`/`/assets` URL (§A2); a broken or blocked target is rendered **inert**; the href/src is
 *    dropped and a `data-pb-link-error="{class}"` attribute is added (markup detail, not frozen §A2).
 *  - **M2 bridging:** the authoritative [FrontmatterBlock] detector runs on the raw bytes FIRST and
 *    only the body region is handed to the Markdown parser — flexmark never sees the raw file head,
 *    so it can never disagree with our grammar (trailing-space opener, `...` closer, BOM — all
 *    decided here). Detection is the renderer's ONLY frontmatter involvement: VALUE extraction
 *    (the §C2 parse) happens exactly once per page in the `IndexBuilder`'s `FrontmatterParser`
 *    ([FrontmatterReader]), never re-run here.
 *  - **Sanitization (§C3):** `escapeHtml(true)` — raw HTML renders as visible literal text; every
 *    emitted tag derives from the AST. No sanitizer dependency.
 *
 * The page and fragment parsers, and their option sets, are immutable and built once; each render
 * call allocates only per-page state, so two calls (or two instances) over the same input produce
 * byte-identical HTML.
 *
 * [index] is the [PageIndexView] the resolver consults (chunk 5 supplies the real one; tests a stub).
 */
class FlexmarkRenderer(private val index: PageIndexView) : MarkdownRenderer {

    private val resolver = LinkResolver(index)

    // The BODY pipeline deliberately carries NO yaml-front-matter extension (M2): the detector has
    // already sliced the head off, and a body parser that still understood front-matter would re-apply
    // flexmark's own lenient notion to the slice — re-opening the disagreement the bridging closes.
    private val bodyOptions = rendererOptions(generateHeadingIds = true)
    private val fragmentOptions = rendererOptions(generateHeadingIds = false)

    private val parser = Parser.builder(bodyOptions).build()
    private val fragmentParser = Parser.builder(fragmentOptions).build()

    override fun render(sourcePath: TreePath, source: ByteArray): RenderedPage {
        // M2: the detector decides the frontmatter boundary and only the body region reaches the
        // Markdown parser. Where flexmark's lenient front-matter notion would differ, it never gets
        // the chance — it sees a body that, by construction, has no front-matter head.
        val block = FrontmatterBlock.detect(source)
        val validBody = decodeStrictUtf8(source, block.bodyStart)
        val bodyMarkdown = validBody ?: String(source, block.bodyStart, source.size - block.bodyStart, Charsets.UTF_8)

        val document = parser.parse(bodyMarkdown)
        val callouts = CalloutAdapter.apply(document)
        val rangeMapper = validBody?.let { SourceRangeMapper(block.bodyStart, it) }
        val pass = ResolutionPass(sourcePath, resolver, rangeMapper)
        pass.walk(document)

        val html = htmlRenderer(pass, callouts).render(document)
        // §B4 sections ride the SAME parse: the collector reuses the pass's allocated heading ids,
        // so a section's headingId is byte-identical to the anchor the HTML carries.
        val sections = SectionCollector(pass).collect(document)
        return RenderedPage(html = html, headings = pass.headings, links = pass.links, sections = sections, blocks = pass.blocks)
    }

    override fun renderFragment(sourcePath: TreePath, markdown: String): String {
        val document = fragmentParser.parse(markdown)
        val callouts = CalloutAdapter.apply(document)
        val pass = ResolutionPass(sourcePath, resolver, rangeMapper = null)
        pass.walk(document)
        return fragmentHtmlRenderer(pass, callouts).render(document)
    }

    /** Builds a per-render [HtmlRenderer] bound to the page's pre-computed ids, links, and ranges. */
    private fun htmlRenderer(pass: ResolutionPass, callouts: CalloutMetadata): HtmlRenderer =
        HtmlRenderer.builder(bodyOptions)
            .htmlIdGeneratorFactory(DelegatingIdGeneratorFactory(pass))
            .attributeProviderFactory(LinkRewriteAttributeProvider.Factory(pass, emitSourceRanges = true))
            .nodeRendererFactory(CalloutNodeRenderer.Factory(callouts))
            .nodeRendererFactory(EscapedHtmlBlockNodeRenderer.Factory())
            .build()

    /** Registers page-parity link and escaped-HTML renderers without page anchors or source ranges. */
    private fun fragmentHtmlRenderer(pass: ResolutionPass, callouts: CalloutMetadata): HtmlRenderer =
        HtmlRenderer.builder(fragmentOptions)
            .attributeProviderFactory(LinkRewriteAttributeProvider.Factory(pass, emitSourceRanges = false))
            .nodeRendererFactory(CalloutNodeRenderer.Factory(callouts))
            .nodeRendererFactory(EscapedHtmlBlockNodeRenderer.Factory())
            .build()
}

private fun rendererOptions(generateHeadingIds: Boolean) =
    MutableDataSet()
        .set(
            Parser.EXTENSIONS,
            listOf(TablesExtension.create(), StrikethroughExtension.create(), TaskListExtension.create()),
        )
        .set(
            TaskListExtension.ITEM_DONE_MARKER,
            "<input type=\"checkbox\" class=\"task-list-item-checkbox\" checked=\"checked\" disabled=\"disabled\" " +
                "aria-label=\"Completed task\" />",
        )
        .set(
            TaskListExtension.ITEM_NOT_DONE_MARKER,
            "<input type=\"checkbox\" class=\"task-list-item-checkbox\" disabled=\"disabled\" aria-label=\"Incomplete task\" />",
        )
        // Raw HTML stays visible as escaped text; all emitted markup comes from the AST.
        .set(HtmlRenderer.ESCAPE_HTML, true)
        .set(HtmlRenderer.GENERATE_HEADER_ID, generateHeadingIds)
        .set(HtmlRenderer.RENDER_HEADER_ID, generateHeadingIds)
        // PB-LINK-1 is the single scheme authority, so flexmark must pass every link to our resolver.
        .set(HtmlRenderer.SUPPRESSED_LINKS, "")
        .toImmutable()

/** Keeps escaped HTML blocks attributable while preserving Flexmark's escaped source text. */
private class EscapedHtmlBlockNodeRenderer : NodeRenderer {

    override fun getNodeRenderingHandlers(): Set<NodeRenderingHandler<*>> =
        setOf(
            NodeRenderingHandler(HtmlBlock::class.java, this::render),
            NodeRenderingHandler(HtmlCommentBlock::class.java, this::render),
        )

    private fun render(node: HtmlBlockBase, context: NodeRendererContext, html: HtmlWriter) {
        if (node is HtmlBlock) html.line()
        val sourceContent = if (node is HtmlBlock) node.contentChars else node.chars
        val content = sourceContent.normalizeEOL().removeSuffix("\n")
        html.withAttr().tag("p")
        html.text(content)
        html.tag("/p")
        if (node is HtmlBlock) {
            html.lineIf(context.htmlOptions.htmlBlockCloseTagEol)
        }
    }

    class Factory : DelegatingNodeRendererFactory {
        override fun apply(options: DataHolder): NodeRenderer =
            EscapedHtmlBlockNodeRenderer()

        override fun getDelegates(): Set<Class<*>> = setOf(CoreNodeRenderer.Factory::class.java)
    }
}

/**
 * One AST walk records valid block byte ranges, allocates a PB-SLUG-1 id per heading (in document
 * order, sharing one [HeadingIdAllocator] namespace per page), and resolves every link/image target
 * via the [LinkResolver]. Results are stashed in maps keyed by the flexmark node (whose equals is
 * identity) so the render-time hooks read them back in O(1).
 *
 * EVERY link-bearing node type flexmark can emit that actually carries a navigable target is routed
 * here, not just inline [Link]/[Image] — a node that reached render with a target but no
 * [LinkResolver] outcome would keep flexmark's default href, and since [HtmlRenderer.SUPPRESSED_LINKS]
 * is cleared (§A2) that default is un-vetted (a `javascript:` autolink would render live). The §A2
 * allowlist must classify them all: core CommonMark angle-bracket autolinks ([AutoLink]) and mail
 * autolinks ([MailLink]), and reference-style links and images ([LinkRef]/[ImageRef], whose target
 * lives on the resolved reference, not the ref node).
 *
 * **Only DEFINED references are routed:** an UNdefined `[bracket]` ref (`[TODO]`, `[1]`, `[x]`) has no
 * matching `[x]: url` definition, so flexmark renders it as literal text — no `<a>`, no href, hence no
 * security exposure (the fail-closed attribute provider still covers anything that DOES render). Were
 * such nodes routed, every undefined bracket in prose would resolve to `Broken(MALFORMED)` and pollute
 * [links] with phantom "broken links" that the chunk-8 link checker would then flag falsely. So
 * a [RefNode] is routed only when [RefNode.getReferenceNode] finds its definition.
 */
private class ResolutionPass(
    private val sourcePath: TreePath,
    private val resolver: LinkResolver,
    private val rangeMapper: SourceRangeMapper?,
) {

    private val allocator = HeadingIdAllocator()
    private val idByHeading = HashMap<FlexmarkHeading, String>()
    private val outcomeByNode = HashMap<Node, LinkOutcome>()
    private val sourceBlockByNode = HashMap<Node, SourceBlock>()

    val headings = mutableListOf<Heading>()
    val links = mutableListOf<PageLink>()
    val blocks = mutableListOf<SourceBlock>()

    fun walk(document: Document) {
        document.descendants.forEach { node ->
            visitBlock(node)
            when (node) {
                is FlexmarkHeading -> visitHeading(node)
                is Link, is Image, is AutoLink, is MailLink -> visitLink(document, node)
                // An undefined ref has no definition → flexmark renders it as literal text (no href);
                // routing it would manufacture a phantom Broken(MALFORMED) in linkOutcomes. Route only
                // DEFINED refs, whose target lives on the resolved Reference.
                is LinkRef, is ImageRef -> if (node.getReferenceNode(document) != null) visitLink(document, node)
            }
        }
    }

    private fun visitHeading(node: FlexmarkHeading) {
        // §A1 text content: flexmark's TextCollectingVisitor yields exactly the spec's input string
        // (text nodes, code-span contents, link/emphasis/strikethrough text, image alt text, breaks
        // → space) — the chunk-2 contract-smoke spike pins this equivalence row by row.
        val text = TextCollectingVisitor().collectAndGetText(node)
        val id = allocator.allocate(text)
        idByHeading[node] = id
        val range = sourceBlockByNode[node]
        headings += Heading(
            id = id,
            level = node.level,
            text = text,
            byteStart = range?.start,
            byteEnd = range?.end,
        )
    }

    private fun visitBlock(node: Node) {
        if (node !is Block || node is Document) return
        val sourceBlock = rangeMapper?.sourceBlock(node) ?: return
        sourceBlockByNode[node] = sourceBlock
        blocks += sourceBlock
    }

    private fun visitLink(document: Document, node: Node) {
        val target = rawTarget(document, node)
        val context = if (node is Image || node is ImageRef) LinkResolver.LinkContext.IMAGE else LinkResolver.LinkContext.ORDINARY
        val outcome = resolver.resolve(sourcePath, target, context)
        outcomeByNode[node] = outcome
        // The raw target and the link's text content travel with the outcome so the chunk-8 link
        // checker can report WHAT broke without ever re-resolving (PageLink doc).
        links += PageLink(target = target, text = TextCollectingVisitor().collectAndGetText(node), outcome = outcome)
    }

    /**
     * The raw target href/src of a link-bearing [node], exactly as flexmark would otherwise emit it,
     * normalized so the §A2 allowlist sees the same scheme the browser would:
     *  - [MailLink] carries no url — flexmark synthesizes a `mailto:` href from the address text, so we
     *    do the same here. `mailto:` is allowlisted, so a real `<a@b.com>` stays a LIVE link (it must
     *    not be over-stripped); a `javascript:` autolink is classified `blocked_scheme` and goes inert.
     *  - [RefNode] ([LinkRef]/[ImageRef]) holds only a label; the target is on the resolved reference
     *    definition. An undefined reference has no definition (and no live href) → empty → malformed.
     */
    private fun rawTarget(document: Document, node: Node): String =
        when (node) {
            is MailLink -> "mailto:${node.text}"
            is RefNode -> node.getReferenceNode(document)?.url?.toString().orEmpty()
            is LinkNodeBase -> node.url.toString()
            else -> error("unrouted link node: ${node.javaClass.name}")
        }

    fun idOf(node: Node): String? = (node as? FlexmarkHeading)?.let(idByHeading::get)

    fun outcomeOf(node: Node): LinkOutcome? = outcomeByNode[node]

    fun blockOf(node: Node): SourceBlock? = sourceBlockByNode[node]
}

/**
 * Maps Flexmark UTF-16 offsets to absolute UTF-8 byte boundaries in a strictly decoded body.
 * UTF-8 uses one byte through U+007F, two through U+07FF, three for other BMP code points, and four
 * for a surrogate pair.
 */
@Suppress("MagicNumber")
private class SourceRangeMapper(private val bodyStart: Int, private val body: String) {

    private val byteOffsetByCharOffset = IntArray(body.length + 1)

    init {
        var charOffset = 0
        var byteOffset = 0
        while (charOffset < body.length) {
            val current = body[charOffset]
            if (current.isHighSurrogate() && body.getOrNull(charOffset + 1)?.isLowSurrogate() == true) {
                byteOffsetByCharOffset[charOffset + 1] = INVALID_BOUNDARY
                byteOffset += 4
                byteOffsetByCharOffset[charOffset + 2] = byteOffset
                charOffset += 2
            } else {
                byteOffset += current.utf8Length()
                byteOffsetByCharOffset[charOffset + 1] = byteOffset
                charOffset++
            }
        }
    }

    fun sourceBlock(node: Node): SourceBlock? {
        val kind = sourceBlockKind(node) ?: return null
        if (node is Paragraph && node.parent is ListItem) return null

        val startOffset =
            if (node is IndentedCodeBlock) {
                val lineStart = physicalLineStart(node.startOffset)
                maxOf(lineStart, containerStart(node))
            } else {
                node.startOffset
            }
        val endOffset = node.endOffset
        if (startOffset < 0 || endOffset <= startOffset || endOffset > byteOffsetByCharOffset.lastIndex) return null

        val relativeStart = byteOffsetByCharOffset[startOffset]
        val relativeEnd = byteOffsetByCharOffset[endOffset]
        if (relativeStart < 0 || relativeEnd < 0) return null

        return SourceBlock(
            start = bodyStart + relativeStart,
            end = bodyStart + relativeEnd,
            kind = kind,
        )
    }

    private fun physicalLineStart(offset: Int): Int {
        var lineStart = offset
        while (lineStart > 0 && body[lineStart - 1] != '\n' && body[lineStart - 1] != '\r') {
            lineStart--
        }
        return lineStart
    }

    /** The walk back to the physical line start must never escape the enclosing block (blockquote or list item). */
    private fun containerStart(node: Node): Int = node.parent?.takeIf { it !is Document }?.startOffset ?: 0

    private fun sourceBlockKind(node: Node): SourceBlockKind? =
        when (node) {
            is FlexmarkHeading -> SourceBlockKind.HEADING
            is Paragraph -> SourceBlockKind.PARAGRAPH
            is BlockQuote -> SourceBlockKind.BLOCKQUOTE
            is BulletList, is OrderedList -> SourceBlockKind.LIST
            is ListItem -> SourceBlockKind.LIST_ITEM
            is FencedCodeBlock, is IndentedCodeBlock -> SourceBlockKind.CODE
            is TableBlock -> SourceBlockKind.TABLE
            is ThematicBreak -> SourceBlockKind.THEMATIC_BREAK
            is HtmlBlock, is HtmlCommentBlock -> SourceBlockKind.HTML_BLOCK
            else -> null
        }

    private fun Char.utf8Length(): Int =
        when {
            code <= 0x7F -> 1
            code <= 0x7FF -> 2
            else -> 3
        }

    private companion object {
        const val INVALID_BOUNDARY = -1
    }
}

/** Returns null for malformed bytes so rendering can keep its replacement behavior without ranges. */
private fun decodeStrictUtf8(source: ByteArray, start: Int): String? =
    try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(source, start, source.size - start))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

/**
 * The §B4 section walk, run over the SAME parsed document as the render (no second parser): plain
 * text accumulates into the current section and a NEW section opens at every [FlexmarkHeading] —
 * any level, any nesting — so every body character lands in exactly one section. Heading text is
 * deliberately NOT collected (it is the section document's own `heading` field, §B4 engine note).
 *
 * Containers are split only when they must be: a block with no heading anywhere beneath it is
 * collected whole (one [TextCollectingVisitor] pass, the same §A1 text semantics headings use),
 * while a block that DOES contain one — a heading inside a blockquote or list item — is descended
 * into so the boundary still falls exactly at the heading. Chunks are end-trimmed (the collected
 * text carries the block's trailing source EOL) and join with `\n`; a chunk with no text at all
 * (thematic breaks and the like) is dropped rather than joined as noise.
 */
private class SectionCollector(private val pass: ResolutionPass) {

    private val sections = mutableListOf<RenderedSection>()
    private val chunks = mutableListOf<String>()
    private var openHeadingId: String? = null

    fun collect(document: Document): List<RenderedSection> {
        descend(document)
        closeSection()
        return sections
    }

    private fun descend(container: Node) {
        container.children.forEach { child ->
            when {
                child is FlexmarkHeading -> {
                    closeSection()
                    openHeadingId = pass.idOf(child)
                }
                child.descendants.any { it is FlexmarkHeading } -> descend(child)
                else -> TextCollectingVisitor().collectAndGetText(child).trimEnd().takeIf { it.isNotEmpty() }?.let(chunks::add)
            }
        }
    }

    /** A preamble (null id) section is emitted only when it has text; a heading's section always is. */
    private fun closeSection() {
        val text = chunks.joinToString("\n")
        if (openHeadingId != null || text.isNotEmpty()) sections += RenderedSection(headingId = openHeadingId, text = text)
        chunks.clear()
    }
}

/**
 * The custom [HeaderIdGeneratorFactory] that replaces flexmark's built-in id scheme: [getId] returns
 * the PB-SLUG-1 id the [ResolutionPass] already allocated for the heading. [generateIds] is a no-op —
 * allocation happened in the single up-front pass, not here.
 */
private class DelegatingIdGeneratorFactory(private val pass: ResolutionPass) : HeaderIdGeneratorFactory {

    override fun create(context: LinkResolverContext): HtmlIdGenerator = generator
    override fun create(): HtmlIdGenerator = generator

    private val generator =
        object : HtmlIdGenerator {
            override fun generateIds(document: Document) = Unit
            override fun getId(node: Node): String? = pass.idOf(node)
            override fun getId(text: CharSequence): String? = null
        }
}

/**
 * The [AttributeProvider] that rewrites link/image targets through PB-LINK-1 (§A2) and attaches a
 * source range to mapped block nodes when page rendering enables them. For a resolved link target it
 * replaces href / src with the emitted URL; for a broken or blocked target it drops the navigable
 * attribute and tags the element with `data-pb-link-error="{class}"`. The wrapper markup is not
 * frozen; only the `data-pb-link-error` class value is frozen.
 *
 * Fail-closed safety net: any LINK part with NO resolution outcome — a link-bearing node type the
 * [ResolutionPass] does not (yet) route, e.g. a new flexmark node after an upgrade — has its `href`/
 * `src` STRIPPED rather than left as flexmark's un-vetted default. With [HtmlRenderer.SUPPRESSED_LINKS]
 * cleared (§A2), an unrouted node would otherwise leak whatever scheme flexmark emitted; here it
 * degrades to a non-navigable element instead. This is what makes clearing SUPPRESSED_LINKS safe.
 */
private class LinkRewriteAttributeProvider(
    private val pass: ResolutionPass,
    private val emitSourceRanges: Boolean,
) : AttributeProvider {

    override fun setAttributes(node: Node, part: AttributablePart, attributes: MutableAttributes) {
        if (emitSourceRanges && isSourceRangePart(node, part)) {
            pass.blockOf(node)?.let { range ->
                attributes.replaceValue("data-pb-src", "${range.start}-${range.end}")
            }
        }
        if (part != AttributablePart.LINK) return
        val urlAttribute = if (node is Image || node is ImageRef) "src" else "href"
        when (val outcome = pass.outcomeOf(node)) {
            is LinkOutcome.Resolved -> attributes.replaceValue(urlAttribute, outcome.url)
            is LinkOutcome.Broken -> {
                attributes.remove(urlAttribute)
                attributes.replaceValue("data-pb-link-error", outcome.reason.wireValue)
            }
            // Fail closed: an unrouted link node never keeps flexmark's default scheme — strip and tag.
            null -> {
                attributes.remove(urlAttribute)
                attributes.replaceValue("data-pb-link-error", "unresolved")
            }
        }
    }

    private fun isSourceRangePart(node: Node, part: AttributablePart): Boolean =
        when (node) {
            is ListItem -> part == CoreNodeRenderer.TIGHT_LIST_ITEM || part == CoreNodeRenderer.LOOSE_LIST_ITEM
            else -> part == AttributablePart.NODE
        }

    /** Independent factory: the rewrite depends on no other attribute provider and affects no global scope. */
    class Factory(
        private val pass: ResolutionPass,
        private val emitSourceRanges: Boolean,
    ) : IndependentAttributeProviderFactory() {
        override fun apply(context: LinkResolverContext): AttributeProvider =
            LinkRewriteAttributeProvider(pass, emitSourceRanges)
    }
}
