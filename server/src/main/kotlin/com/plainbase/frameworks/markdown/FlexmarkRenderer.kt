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
import com.plainbase.domain.service.LinkResolver
import com.vladsch.flexmark.ast.AutoLink
import com.vladsch.flexmark.ast.Image
import com.vladsch.flexmark.ast.ImageRef
import com.vladsch.flexmark.ast.Link
import com.vladsch.flexmark.ast.LinkNodeBase
import com.vladsch.flexmark.ast.LinkRef
import com.vladsch.flexmark.ast.MailLink
import com.vladsch.flexmark.ast.RefNode
import com.vladsch.flexmark.ast.Reference
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.html.AttributeProvider
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.html.IndependentAttributeProviderFactory
import com.vladsch.flexmark.html.renderer.AttributablePart
import com.vladsch.flexmark.html.renderer.HeaderIdGeneratorFactory
import com.vladsch.flexmark.html.renderer.HtmlIdGenerator
import com.vladsch.flexmark.html.renderer.LinkResolverContext
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.ast.Document
import com.vladsch.flexmark.util.ast.Node
import com.vladsch.flexmark.util.ast.TextCollectingVisitor
import com.vladsch.flexmark.util.data.MutableDataSet
import com.vladsch.flexmark.util.html.MutableAttributes
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
 *    `/docs`/`/assets` URL (§A2); a broken or blocked target is rendered **inert** — the href/src is
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
 * The [parser] and the shared options are immutable and built once; each [render] call allocates
 * only the per-page allocator/maps, so two calls (or two instances) over the same input produce
 * byte-identical HTML (the determinism criterion).
 *
 * [index] is the [PageIndexView] the resolver consults (chunk 5 supplies the real one; tests a stub).
 */
class FlexmarkRenderer(private val index: PageIndexView) : MarkdownRenderer {

    private val resolver = LinkResolver(index)

    // The BODY pipeline deliberately carries NO yaml-front-matter extension (M2): the detector has
    // already sliced the head off, and a body parser that still understood front-matter would re-apply
    // flexmark's own lenient notion to the slice — re-opening the disagreement the bridging closes.
    private val bodyOptions =
        MutableDataSet()
            .set(Parser.EXTENSIONS, listOf(TablesExtension.create(), StrikethroughExtension.create()))
            // §C3 sanitization: escape all raw HTML to visible text; every emitted tag derives from the AST.
            .set(HtmlRenderer.ESCAPE_HTML, true)
            // Emit `id` on headings, sourced from our custom generator (the chunk-2 slugger), not flexmark's.
            .set(HtmlRenderer.GENERATE_HEADER_ID, true)
            .set(HtmlRenderer.RENDER_HEADER_ID, true)
            // Clear flexmark's built-in link suppression (it drops `javascript:` by default) — the
            // PB-LINK-1 allowlist is the SINGLE scheme authority (§A2), applied via the resolver +
            // attribute provider, so flexmark must hand us every link untouched to classify.
            .set(HtmlRenderer.SUPPRESSED_LINKS, "")
            .toImmutable()

    private val parser = Parser.builder(bodyOptions).build()

    override fun render(sourcePath: TreePath, source: ByteArray): RenderedPage {
        // M2: the detector decides the frontmatter boundary and only the body region reaches the
        // Markdown parser. Where flexmark's lenient front-matter notion would differ, it never gets
        // the chance — it sees a body that, by construction, has no front-matter head.
        val block = FrontmatterBlock.detect(source)
        val bodyMarkdown = String(source, block.bodyStart, source.size - block.bodyStart, Charsets.UTF_8)

        val document = parser.parse(bodyMarkdown)
        val pass = ResolutionPass(sourcePath, resolver)
        pass.walk(document)

        val html = htmlRenderer(pass).render(document)
        return RenderedPage(html = html, headings = pass.headings, links = pass.links)
    }

    /** Builds a per-render [HtmlRenderer] bound to [pass]'s pre-computed ids and link outcomes. */
    private fun htmlRenderer(pass: ResolutionPass): HtmlRenderer =
        HtmlRenderer.builder(bodyOptions)
            .htmlIdGeneratorFactory(DelegatingIdGeneratorFactory(pass))
            .attributeProviderFactory(LinkRewriteAttributeProvider.Factory(pass))
            .build()
}

/**
 * One AST walk that does both jobs: allocates a PB-SLUG-1 id per heading (in document order, sharing
 * one [HeadingIdAllocator] namespace per page) and resolves every link/image target via the
 * [LinkResolver]. Results are stashed in maps keyed by the flexmark node (whose `equals` is identity)
 * so the render-time hooks — the id generator and the attribute provider — read them back in O(1).
 *
 * EVERY link-bearing node type flexmark can emit that actually carries a navigable target is routed
 * here, not just inline [Link]/[Image] — a node that reached render with a target but no
 * [LinkResolver] outcome would keep flexmark's default href, and since [HtmlRenderer.SUPPRESSED_LINKS]
 * is cleared (§A2) that default is un-vetted (a `javascript:` autolink would render live). The §A2
 * allowlist must classify them all: core CommonMark angle-bracket autolinks ([AutoLink]) and mail
 * autolinks ([MailLink]), and reference-style links and images ([LinkRef]/[ImageRef], whose target
 * lives on the resolved [Reference], not the ref node).
 *
 * **Only DEFINED references are routed:** an UNdefined `[bracket]` ref (`[TODO]`, `[1]`, `[x]`) has no
 * matching `[x]: url` definition, so flexmark renders it as literal text — no `<a>`, no href, hence no
 * security exposure (the fail-closed attribute provider still covers anything that DOES render). Were
 * such nodes routed, every undefined bracket in prose would resolve to `Broken(MALFORMED)` and pollute
 * [links] with phantom "broken links" that the chunk-8 link checker would then flag falsely. So
 * a [RefNode] is routed only when [RefNode.getReferenceNode] finds its definition.
 */
private class ResolutionPass(private val sourcePath: TreePath, private val resolver: LinkResolver) {

    private val allocator = HeadingIdAllocator()
    private val idByHeading = HashMap<FlexmarkHeading, String>()
    private val outcomeByNode = HashMap<Node, LinkOutcome>()

    val headings = mutableListOf<Heading>()
    val links = mutableListOf<PageLink>()

    fun walk(document: Document) {
        document.descendants.forEach { node ->
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
        headings += Heading(id = id, level = node.level, text = text)
    }

    private fun visitLink(document: Document, node: Node) {
        val target = rawTarget(document, node)
        val outcome = resolver.resolve(sourcePath, target)
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
     *  - [RefNode] ([LinkRef]/[ImageRef]) holds only a label; the target is on the resolved [Reference]
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
 * The [AttributeProvider] that rewrites link/image targets through PB-LINK-1 (§A2). For a resolved
 * target it replaces `href` (links) / `src` (images) with the emitted URL; for a broken or blocked
 * target it drops the navigable attribute and tags the element `data-pb-link-error="{class}"` so it
 * renders inert (the wrapper markup is explicitly non-frozen — only the error class is).
 *
 * Fail-closed safety net: any LINK part with NO resolution outcome — a link-bearing node type the
 * [ResolutionPass] does not (yet) route, e.g. a new flexmark node after an upgrade — has its `href`/
 * `src` STRIPPED rather than left as flexmark's un-vetted default. With [HtmlRenderer.SUPPRESSED_LINKS]
 * cleared (§A2), an unrouted node would otherwise leak whatever scheme flexmark emitted; here it
 * degrades to a non-navigable element instead. This is what makes clearing SUPPRESSED_LINKS safe.
 */
private class LinkRewriteAttributeProvider(private val pass: ResolutionPass) : AttributeProvider {

    override fun setAttributes(node: Node, part: AttributablePart, attributes: MutableAttributes) {
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

    /** Independent factory: the rewrite depends on no other attribute provider and affects no global scope. */
    class Factory(private val pass: ResolutionPass) : IndependentAttributeProviderFactory() {
        override fun apply(context: LinkResolverContext): AttributeProvider = LinkRewriteAttributeProvider(pass)
    }
}
