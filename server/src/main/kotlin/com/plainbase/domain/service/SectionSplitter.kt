package com.plainbase.domain.service

import com.plainbase.domain.page.Heading
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.search.PageDocuments
import com.plainbase.domain.search.SectionDocument

/**
 * Shapes one published [IndexedPage] into its search-document set (§B4 section policy, exact).
 * Pure domain code over snapshot data — the renderer already did the only parse; nothing here
 * reads a file or touches Markdown.
 *
 *  - The FIRST document is always the page-level title/metadata document (`headingId` null);
 *    its body is the pre-first-heading preamble (empty when the page opens with a heading).
 *  - One document per heading section follows, in document order, carrying the section's OWN
 *    heading text (never the breadcrumb — §B4 engine note) and the display `headingPath` derived
 *    from heading levels (ancestor = nearest preceding heading of a lower level, the same §B7
 *    rule assembly uses).
 *  - Frontmatter metadata (§C2 scalar-or-list collapse for `tags`/`aliases`, scalar `owner`,
 *    `status` defaulting to `active`) repeats on every document of the page so any hit can be
 *    filtered/ranked without a join.
 *  - C0 control characters except `\t`/`\n` are stripped from every text field (§B4 invariant;
 *    `\r` goes with them) — what makes the §B5 sentinel-marker snippet conversion safe.
 */
class SectionSplitter {

    fun split(page: IndexedPage): PageDocuments {
        val headingById = page.headings.associateBy { it.id }
        val breadcrumbs = breadcrumbsOf(page.headings)
        val title = page.title.indexable()
        val tags = page.frontmatter.strings("tags").map { it.indexable() }
        val aliases = page.frontmatter.strings("aliases").map { it.indexable() }
        val owner = page.frontmatter.scalar("owner")?.indexable()
        val status = (page.frontmatter.scalar("status") ?: DEFAULT_STATUS).indexable()

        fun document(headingId: String?, body: String): SectionDocument {
            val heading = headingId?.let(headingById::getValue)
            return SectionDocument(
                pageId = page.id,
                headingId = headingId,
                title = title,
                heading = heading?.text?.indexable(),
                headingPath = heading?.let(breadcrumbs::getValue).orEmpty(),
                body = body.indexable(),
                tags = tags,
                owner = owner,
                aliases = aliases,
                path = page.path,
                status = status,
            )
        }

        val preamble = page.sections.firstOrNull { it.headingId == null }?.text.orEmpty()
        val documents = buildList {
            add(document(headingId = null, body = preamble))
            page.sections.filter { it.headingId != null }.forEach { add(document(it.headingId, it.text)) }
        }
        return PageDocuments(pageId = page.id, contentHash = page.contentHash, path = page.path, sections = documents)
    }

    /** The display breadcrumb per heading (§B7 rule): ancestor texts root-first, own text last. */
    private fun breadcrumbsOf(headings: List<Heading>): Map<Heading, List<String>> = buildMap {
        val ancestors = ArrayDeque<Heading>()
        headings.forEach { heading ->
            while (ancestors.isNotEmpty() && ancestors.last().level >= heading.level) ancestors.removeLast()
            put(heading, ancestors.map { it.text.indexable() } + heading.text.indexable())
            ancestors.addLast(heading)
        }
    }

    /** §B4: indexed text never carries a C0 control character other than `\t`/`\n`. */
    private fun String.indexable(): String = filterNot { it < ' ' && it != '\t' && it != '\n' }

    companion object {
        /** §5.2: a page without a `status` key is an active page. */
        const val DEFAULT_STATUS: String = "active"
    }
}
