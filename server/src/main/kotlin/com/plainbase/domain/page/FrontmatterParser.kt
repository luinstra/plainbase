package com.plainbase.domain.page

/**
 * The frontmatter-only parse port: raw page bytes → the §C2 [Frontmatter] subset, without rendering
 * the body.
 *
 * This is the ONLY frontmatter value-extraction site in the tree — exactly one parse per page, in
 * the `IndexBuilder`. It runs there because URL construction needs every page's `slug` and
 * `redirect_from` (§A4) BEFORE any page can be rendered — link resolution embeds other pages'
 * canonical URLs into the HTML, so URL assignment must complete tree-wide first. The renderer
 * shares only the cheap [FrontmatterBlock] DETECTION (the body boundary); it never re-extracts
 * values (`RenderedPage` carries no frontmatter).
 *
 * The single implementation is the frameworks `FrontmatterReader`, over the same [FrontmatterBlock]
 * detection grammar the renderer slices the body with — exactly one frontmatter grammar in the
 * tree (M2).
 */
interface FrontmatterParser {

    /** Parses [source]'s frontmatter block ([Frontmatter.EMPTY] when absent), never touching the body. */
    fun parse(source: ByteArray): Frontmatter
}
