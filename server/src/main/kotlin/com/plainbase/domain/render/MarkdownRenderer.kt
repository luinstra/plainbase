package com.plainbase.domain.render

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.LinkOutcome
import com.plainbase.domain.page.Frontmatter
import com.plainbase.domain.page.Heading

/**
 * The render port: raw Markdown bytes for a page at a known [TreePath] → a [RenderedPage] (HTML
 * with PB-SLUG-1 anchor ids and PB-LINK-1-rewritten links, the document-order heading list, the
 * resolved link outcomes, and the parsed frontmatter).
 *
 * Pure domain code: the port speaks only domain types. The flexmark adapter
 * (`frameworks/markdown/FlexmarkRenderer`) is the single implementation; nothing else in the tree
 * imports a Markdown library (single-renderer rule, §5.8).
 */
interface MarkdownRenderer {

    /**
     * Renders the page whose raw file bytes are [source] and whose content-relative path is
     * [sourcePath] (the link resolver needs it to resolve relative hrefs). Frontmatter is detected
     * and extracted by the shared grammar (M2); only the body region is rendered to HTML.
     */
    fun render(sourcePath: TreePath, source: ByteArray): RenderedPage
}

/**
 * The frozen-shape product of rendering a page (§A4 `/html` payload feeds off this):
 *  - [html] — body HTML, raw HTML escaped (§C3), anchor ids from PB-SLUG-1, hrefs/srcs rewritten
 *    per PB-LINK-1 (resolved → `/docs`/`/assets` URL; broken/blocked → inert, see [links]);
 *  - [headings] — document order, each carrying its allocated id, level, and §A1 text;
 *  - [links] — every link/image target with its [LinkOutcome] (the link-checker's input, chunk 8);
 *  - [frontmatter] — the parsed §C2 subset ([Frontmatter.EMPTY] when there is no block).
 */
data class RenderedPage(
    val html: String,
    val headings: List<Heading>,
    val links: List<LinkOutcome>,
    val frontmatter: Frontmatter,
)
