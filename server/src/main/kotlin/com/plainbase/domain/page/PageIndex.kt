package com.plainbase.domain.page

import com.plainbase.domain.content.ContentFolder
import com.plainbase.domain.content.PercentCoding
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.PageLink
import com.plainbase.domain.render.RenderedSection

/**
 * The immutable page-index snapshot (chunk 5, caching decision §C4): every page with its stable
 * identity, canonical URL (§A4), and render metadata, plus the lookup maps the read path serves
 * from — [byId], [byPath], and [byUrlPath].
 *
 * **Deeply immutable, by construction:** every collection is copied once here and never mutated;
 * there is no post-publication mutation path. That is what makes the `IndexBuilder`'s
 * `AtomicReference` swap safe — a reader holding a snapshot always sees a complete, internally
 * consistent index, never a torn one.
 *
 * All path keys are chunk 1.5 [TreePath]s — file paths for [byPath], URL-slug segment paths for
 * [byUrlPath] — so no path semantics are re-derived here. The class also implements
 * [PageIndexView], the lookup/URL seam chunk 2's `LinkResolver` resolves against; the
 * `IndexBuilder` renders against a URL-complete skeleton of the very same type.
 */
class PageIndex(
    pages: List<IndexedPage>,
    folders: List<ContentFolder>,
    assets: Set<TreePath>,
) : PageIndexView {

    /** Every indexed page, in file-path order (the scan order the builder fixes). */
    val pages: List<IndexedPage> = pages.toList()

    /** Every indexed folder with its `_folder.yaml` meta — the `TreeBuilder`'s input. */
    val folders: List<ContentFolder> = folders.toList()

    /** Every indexed non-page file. */
    val assets: Set<TreePath> = assets.toSet()

    /** Page by stable id — the `/p/{id}` permalink and citation lookup. */
    val byId: Map<PageId, IndexedPage> = this.pages.associateBy { it.id }

    /** Page by content file path. */
    val byPath: Map<TreePath, IndexedPage> = this.pages.associateBy { it.path }

    /**
     * Page by canonical URL path (the `/docs/`-relative slug segments, decoded) — the `by-path`
     * lookup. Collision losers have no URL path and are absent (§A4): reachable by id only.
     */
    val byUrlPath: Map<TreePath, IndexedPage> = this.pages.mapNotNull { page -> page.urlPath?.let { it to page } }.toMap()

    private val directories: Set<TreePath> = this.folders.map { it.path }.toSet()

    /** Case-insensitive value → indexed paths, for the §A2 step-6 rescue scan. */
    private val byLowercaseValue: Map<String, List<TreePath>> =
        (this.pages.map { it.path } + this.assets + directories).groupBy { it.value.lowercase() }

    init {
        // §A4 invariant: per-parent segment uniqueness (the CanonicalUrlBuilder's collision policy)
        // implies FULL URL-path uniqueness — two equal full paths would need equal segments at every
        // level, colliding at the first shared parent. A duplicate here means the builder is broken.
        check(byUrlPath.size == this.pages.count { it.urlPath != null }) { "duplicate canonical URL path in snapshot" }
    }

    override fun kindOf(path: TreePath): PageIndexView.EntryKind? = when (path) {
        in byPath -> PageIndexView.EntryKind.PAGE
        in assets -> PageIndexView.EntryKind.ASSET
        in directories -> PageIndexView.EntryKind.DIRECTORY
        else -> null
    }

    override fun pageUrl(page: TreePath): String {
        val indexed = requireNotNull(byPath[page]) { "pageUrl called on a non-page path: ${page.value}" }
        // A collision loser is excluded from path space; rendered links emit its permalink (§A4/§A2).
        return indexed.url ?: indexed.permalink
    }

    override fun assetUrl(asset: TreePath): String = "/assets/" + PercentCoding.encodePath(asset.value)

    override fun caseInsensitiveMatches(path: TreePath): List<TreePath> =
        byLowercaseValue[path.value.lowercase()].orEmpty().filterNot { it == path }

    companion object {
        /** The pre-first-build snapshot: empty but fully usable, so readers are total from startup. */
        val EMPTY: PageIndex = PageIndex(emptyList(), emptyList(), emptySet())
    }
}

/**
 * One indexed page: identity (chunk 4), canonical URL (§A4), render metadata (chunk 3), and the
 * read payload ([markdown] + [contentHash]) captured from the same bytes the render saw.
 *
 * [urlPath] is the canonical URL as a [TreePath] of DECODED slug segments (e.g.
 * `notes/release-notes-2026`) — the form the alias registry stores; null marks a same-parent
 * slug-collision loser, excluded from path space but fully reachable via its [permalink]. [url] is
 * the wire form: `/docs/` + the RFC 3986 percent-encoded segments (unicode slugs are legal and
 * encoded on emit).
 *
 * Carrying [markdown] and [contentHash] here is what makes every page response internally
 * coherent: markdown, html, hash, and citation all come from ONE published snapshot, so an
 * on-disk edit between rescans can never pair stale html with a fresh hash (the exact citation
 * invariant Phase 5 heading-citations lean on).
 */
data class IndexedPage(
    val id: PageId,
    val path: TreePath,
    /** The page-slug component of the URL construction (frontmatter `slug` else filename stem, slugified). */
    val slug: String,
    val urlPath: TreePath?,
    /** Frontmatter `title` → first H1 text → filename stem (§A4 derivation). */
    val title: String,
    val frontmatter: Frontmatter,
    /** True iff the id also lives in the file's frontmatter (§5.2). */
    val materialized: Boolean,
    /**
     * The §A4 `markdown` payload, VERBATIM: a plain (lenient) UTF-8 decode of the raw file bytes —
     * BOM char included, frontmatter included, invalid sequences as U+FFFD. Deliberately unlike the
     * patcher's strict decode: what an agent reads must be exactly what `base_hash` hashes, ever after.
     */
    val markdown: String,
    /** The frozen §5.3 content hash (`CitationFactory.contentHash`) over the same raw bytes. */
    val contentHash: String,
    /**
     * The last commit touching this file in Git mode (W5), captured at index time and served disk-free
     * exactly like [contentHash]; null off Git, for an as-yet-uncommitted page, or in Phase 1-2.
     *
     * **As-of-the-last-reindex invariant:** `commit` reflects the page's history at the moment of its last
     * (re)index, never a request-time Git read. During an in-flight save a citation may momentarily pair a
     * NEW [contentHash] with the PRIOR `commit` (a watcher rebuild slipping into the post-CAS/pre-commit
     * window) until the save's own reindex republishes a coherent snapshot — bounded, self-healing,
     * sub-second, never a durable mismatch (W5 D-1 / debate MUST-FIX 4).
     */
    val commit: String?,
    val html: String,
    val headings: List<Heading>,
    val links: List<PageLink>,
    /**
     * The §B4 plain-text sections, captured from the same single render as [html]/[headings]. One
     * more text copy per page is an accepted cost (a page already carries [markdown] AND [html]);
     * what it buys is a search sync that never re-reads or re-parses anything — `SectionSplitter`
     * works entirely off the published snapshot.
     */
    val sections: List<RenderedSection>,
) {

    /** The canonical path URL on the wire (§A4), or null for a collision loser (REST `url` field). */
    val url: String? = urlPath?.let { "/docs/" + PercentCoding.encodePath(it.value) }

    /** The permanent ID permalink — the §A4 durability layer, unaffected by any path change. */
    val permalink: String get() = "/p/${id.value}"
}
