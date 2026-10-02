package com.plainbase.domain.render

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.PageLink
import com.plainbase.domain.page.Heading

/**
 * The render port: raw Markdown bytes for a page at a known [TreePath] → a [RenderedPage] (HTML
 * with PB-SLUG-1 anchor ids and PB-LINK-1-rewritten links, the document-order heading list, the
 * resolved link outcomes, and rendered source blocks). [renderFragment] renders a fragment
 * without page frontmatter, page anchors, or source ranges.
 *
 * Pure domain code: the port speaks only domain types. The flexmark adapter
 * (`frameworks/markdown/FlexmarkRenderer`) is the single implementation; nothing else in the tree
 * imports a Markdown library (single-renderer rule, §5.8).
 */
interface MarkdownRenderer {

    /**
     * Renders the page whose raw file bytes are [source] and whose content-relative path is
     * [sourcePath] (the link resolver needs it to resolve relative hrefs). The shared detector
     * (M2) finds the frontmatter BOUNDARY so only the body region renders to HTML; frontmatter
     * VALUE extraction is the [com.plainbase.domain.page.FrontmatterParser]'s job — exactly one
     * parse per page, owned by the `IndexBuilder`, never re-run here.
     */
    fun render(sourcePath: TreePath, source: ByteArray): RenderedPage

    /** Renders a Markdown fragment without page frontmatter handling, page anchors, or source ranges. */
    fun renderFragment(sourcePath: TreePath, markdown: String): String
}

/**
 * The frozen-shape product of rendering a page (§A4 `/html` payload feeds off this):
 *  - [html] — body HTML, raw HTML escaped (§C3), anchor ids from PB-SLUG-1, hrefs/srcs rewritten
 *    per PB-LINK-1 (resolved → `/{root}`/`/assets` URL; broken/blocked → inert, see [links]);
 *  - [headings] — document order, each carrying its allocated id, level, and §A1 text;
 *  - [links] — every link/image occurrence as a [PageLink] (raw target, text, resolved outcome) —
 *    the link-checker's input (chunk 8);
 *  - [sections] — the §B4 plain-text section stream (Phase 2 search), produced by the SAME render
 *    pass's AST walk — no second Markdown parser ever (§5.6/§5.8 single-renderer rule).
 *  - [blocks] — one half-open UTF-8 byte range for each attributed rendered block in the raw page file.
 */
data class RenderedPage(
    val html: String,
    val headings: List<Heading>,
    val links: List<PageLink>,
    val sections: List<RenderedSection>,
    val blocks: List<SourceBlock>,
)

/** The supported source-bearing Markdown block types. */
enum class SourceBlockKind {
    HEADING,
    PARAGRAPH,
    BLOCKQUOTE,
    LIST,
    LIST_ITEM,
    CODE,
    TABLE,
    THEMATIC_BREAK,
    HTML_BLOCK,
}

/**
 * A rendered Markdown block's half-open UTF-8 byte range in the original page file. This is the AST
 * node span, not the rendered text: heading and fenced-code spans end before the trailing EOL, while
 * paragraph and indented-code spans include it. `data-pb-src` values are not unique per element: an
 * indented code block that is a container's sole content can share its range, so consumers should
 * use the nearest attributed ancestor. HTML block ranges cover `HtmlBlock` and `HtmlCommentBlock` nodes.
 */
data class SourceBlock(
    val start: Int,
    val end: Int,
    val kind: SourceBlockKind,
)

/**
 * One plain-text slice of a page body (§B4 section policy, exact): the text strictly between
 * [headingId]'s heading and the NEXT heading of ANY level — every body character belongs to
 * exactly one section, so concatenating the stream reproduces the whole body text with no loss
 * and no duplication (ancestors never double-index; `heading_path` carries ancestry instead).
 *
 * [headingId] is null ONLY for the preamble — text before the first heading — which feeds the
 * page-level search document's body; a preamble section is present only when it has text, while
 * a heading's section is always present (an empty [text] is meaningful: the heading itself is
 * still searchable). The heading's OWN text is NOT part of [text] — it travels as the section
 * document's `heading` field (§B4 engine note).
 */
data class RenderedSection(
    /** The PB-SLUG-1 id of the heading that opens this section; null for the preamble. */
    val headingId: String?,
    /** Plain text (AST text collection, blocks joined by `\n`) — never HTML, never Markdown. */
    val text: String,
)
