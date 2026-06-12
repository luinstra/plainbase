package com.plainbase.domain.search

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId

/**
 * One page's complete search-document set — the unit [SearchProvider.index] replaces atomically
 * (§B4 per-page atomicity: a concurrent query sees a page's documents entirely old or entirely
 * new, never half). [contentHash] and [path] are what [SearchProvider.indexedState] echoes back
 * for engine-truth diffing: the hash covers every in-file change, the path covers moves without
 * a content change.
 */
data class PageDocuments(
    val pageId: PageId,
    val contentHash: String,
    val path: TreePath,
    val sections: List<SectionDocument>,
)

/**
 * One search document, the §5.6 shape verbatim: a heading section of a page, or the page-level
 * title/metadata document ([headingId] null) whose [body] is the pre-first-heading preamble.
 *
 *  - [heading] carries the section's OWN heading text ONLY — never the joined breadcrumb, which
 *    would double-index ancestor text (§B4 engine note); null exactly for the page-level document.
 *  - [headingPath] is the display breadcrumb (ancestor texts, own text last; empty for the
 *    page-level document). It rides the document shape per §5.6, but display values are always
 *    recomputed from the published snapshot at assembly (§B7) — never read back from an engine.
 *  - [tags]/[aliases] are the §C2 scalar-or-list collapse of the frontmatter keys; [status]
 *    defaults to `active` when the frontmatter has none.
 *  - Every text field is free of C0 control characters except `\t`/`\n` (§B4 invariant — also
 *    what makes the §B5 sentinel-marker snippet conversion safe).
 */
data class SectionDocument(
    val pageId: PageId,
    val headingId: String?,
    val title: String,
    val heading: String?,
    val headingPath: List<String>,
    val body: String,
    val tags: List<String>,
    val owner: String?,
    val aliases: List<String>,
    val path: TreePath,
    val status: String,
)
