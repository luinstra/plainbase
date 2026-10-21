package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.page.IndexedPage
import com.plainbase.frameworks.config.SiteConfig

/** A validated shell split into immutable segments before any document is served. */
internal class ReaderDocument(shell: String, private val site: SiteConfig) {
    private val beforeTitle: String
    private val afterTitle: String
    private val afterHead: String
    private val afterRoot: String

    init {
        val title = anchorIndex(shell, TITLE_ANCHOR)
        val head = anchorIndex(shell, HEAD_ANCHOR)
        val root = anchorIndex(shell, ROOT_ANCHOR)
        require(title < head && head < root) { "Expected title, head and root anchors in document order" }
        beforeTitle = shell.substring(0, title)
        afterTitle = shell.substring(title + TITLE_ANCHOR.length, head)
        afterHead = shell.substring(head + HEAD_ANCHOR.length, root)
        afterRoot = shell.substring(root + ROOT_ANCHOR.length)
    }

    fun render(page: IndexedPage, url: String): ByteArray = buildString {
        append(beforeTitle)
        append("<title>")
        append(escapeHtml(page.title))
        append(" \u00b7 ")
        append(escapeHtml(site.name ?: "Plainbase"))
        append("</title>")
        append(afterTitle)
        page.frontmatter.scalar("description")?.takeIf { it.isNotBlank() }?.let { description ->
            append("<meta name=\"description\" content=\"")
            append(escapeHtml(description))
            append("\">")
        }
        site.canonicalOrigin?.let { origin ->
            append("<link rel=\"canonical\" href=\"")
            append(escapeHtml(origin + url))
            append("\">")
        }
        append("<meta name=\"plainbase-page-id\" content=\"")
        append(escapeHtml(page.id.value))
        append("\">")
        append(HEAD_ANCHOR)
        append(afterHead)
        append("<div id=\"root\"><article data-pb-initial>")
        append(page.html)
        append("</article></div>")
        append(afterRoot)
    }.toByteArray(Charsets.UTF_8)
}

private const val TITLE_ANCHOR = "<title>Plainbase</title>"
private const val HEAD_ANCHOR = "</head>"
private const val ROOT_ANCHOR = "<div id=\"root\"></div>"

private fun anchorIndex(shell: String, anchor: String): Int {
    val index = shell.indexOf(anchor)
    require(index >= 0 && shell.indexOf(anchor, index + 1) < 0) { "Expected exactly one $anchor anchor in the bundled shell" }
    return index
}

private fun escapeHtml(text: String): String = buildString {
    text.forEach { character ->
        when (character) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(character)
        }
    }
}
