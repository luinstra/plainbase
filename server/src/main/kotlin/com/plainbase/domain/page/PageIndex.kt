package com.plainbase.domain.page

import com.plainbase.domain.content.ContentFolder
import com.plainbase.domain.content.PercentCoding
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.PageLink
import com.plainbase.domain.render.RenderedSection
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath

/** One root's slice of a snapshot, in the builder's per-root scan order. */
data class RootSection(
    val root: RootName,
    val pages: List<IndexedPage>,
    val folders: List<ContentFolder>,
    val assets: Set<TreePath>,
)

/**
 * The immutable page-index snapshot (chunk 5, caching decision §C4; keyed (root, path) since
 * multi-root C2, ADR-0011): every page with its stable identity, canonical URL (§A4), and render
 * metadata, plus the lookup maps the read path serves from — [byRootedId], [byPath], and [byUrlPath].
 *
 * **Deeply immutable, by construction:** every collection is copied once here and never mutated;
 * there is no post-publication mutation path. That is what makes the `IndexBuilder`'s
 * `AtomicReference` swap safe — a reader holding a snapshot always sees a complete, internally
 * consistent index, never a torn one.
 *
 * **Per-root keying (C2/C5):** the snapshot is a list of per-root [sections] in registry (D7) order;
 * [byPath]/[byUrlPath] key on [RootedPath] and [byRootedId] keys on [RootedPageId] (one page id may
 * live in several roots under per-root identity - a within-root duplicate is init-checked here).
 * Folders and assets are deliberately NOT
 * flattened across roots (a cross-root union of root-relative paths is a semantic trap); every
 * consumer goes through [section] or [view], both total (an unknown root yields the empty
 * section/view, so `EMPTY`-snapshot readers stay total from startup).
 *
 * **URL emission is root-qualified (C3):** `IndexedPage.url` is `/{root}/{path}` and asset
 * URLs are `/assets/{root}/{path}`, so two roots holding the same relative URL path can no longer
 * emit identical `url` strings. [byUrlPath] stays keyed (root, urlPath) - the root segment on the
 * wire and the composite key agree by construction.
 *
 * The class no longer implements [PageIndexView]; it vends per-root views instead - [view] is the
 * render/link seam, one [PageIndexView] per root, so `LinkResolver`, the renderer factory, and the
 * frozen [PageIndexView] shape are untouched. All path keys are chunk 1.5 [TreePath]s (file paths
 * for [byPath], URL-slug segment paths for [byUrlPath]), root-qualified via [RootedPath].
 */
class PageIndex(sections: List<RootSection>) {

    /** The per-root slices, in the builder's registry (D7) order. Copied DEEPLY: the inner page/
     * folder/asset collections are re-materialized too, so the deep-immutability contract above
     * never rests on a caller-owned (possibly mutable) list. */
    val sections: List<RootSection> =
        sections.map { it.copy(pages = it.pages.toList(), folders = it.folders.toList(), assets = it.assets.toSet()) }

    /** Every indexed page: section order, then each section's file-path scan order. */
    val pages: List<IndexedPage> = this.sections.flatMap { it.pages }

    /** Page by rooted stable id ([RootedPageId]) — the permalink and citation lookup, per-root (C5). */
    val byRootedId: Map<RootedPageId, IndexedPage> = pages.associateBy { it.rooted }

    /** Page by rooted content file path. */
    val byPath: Map<RootedPath, IndexedPage> = pages.associateBy { RootedPath(it.root, it.path) }

    /**
     * Page by rooted canonical URL path (the ROOT-relative slug segments, decoded - the tail after
     * `/{root}/` since C3) - the `by-path` lookup. Collision losers have no URL path and are
     * absent (§A4): reachable by id only. URL uniqueness is per root (the composite key); see the
     * class doc's root-qualified emission note.
     */
    val byUrlPath: Map<RootedPath, IndexedPage> =
        pages.mapNotNull { page -> page.urlPath?.let { RootedPath(page.root, it) to page } }.toMap()

    private val sectionsByRoot: Map<RootName, RootSection> = this.sections.associateBy { it.root }

    private val viewsByRoot: Map<RootName, PageIndexView> = this.sections.associate { it.root to SectionView(it) }

    init {
        require(sectionsByRoot.size == this.sections.size) { "duplicate root section in snapshot" }
        this.sections.forEach { section ->
            require(section.pages.all { it.root == section.root }) { "page under a foreign root in section '${section.root}'" }
        }
        // §A4 invariant, per root: per-parent segment uniqueness (the CanonicalUrlBuilder's
        // collision policy) implies FULL URL-path uniqueness within one tree. The composite
        // (root, urlPath) key makes this exact check per-root; a duplicate means the builder is broken.
        check(byUrlPath.size == pages.count { it.urlPath != null }) { "duplicate canonical URL path in snapshot" }
        // Per-root identity (C5): a cross-root duplicate id is LEGAL and must NOT trip; a WITHIN-root duplicate
        // (a genuine builder bug) still collides on (root, id) and fails as loudly as the URL check.
        check(byRootedId.size == pages.size) { "duplicate (root, page id) in snapshot" }
    }

    /** The page at [rooted]'s exact ([root], [id]) identity, or null - a direct read of the total per-root map. */
    fun pageAt(rooted: RootedPageId): IndexedPage? = byRootedId[rooted]

    /** [root]'s slice; TOTAL - an unknown root yields an empty section, mirroring [view]. */
    fun section(root: RootName): RootSection =
        sectionsByRoot[root] ?: RootSection(root, emptyList(), emptyList(), emptySet())

    /** [root]'s lookup/URL seam (the render/link view); TOTAL - an unknown root yields the empty view. */
    fun view(root: RootName): PageIndexView = viewsByRoot[root] ?: EMPTY_VIEW

    /** One root's [PageIndexView]: every lookup (and the §A2 lowercase rescue) scoped to that section. */
    private class SectionView(section: RootSection) : PageIndexView {

        private val root: RootName = section.root

        private val byPath: Map<TreePath, IndexedPage> = section.pages.associateBy { it.path }

        private val assets: Set<TreePath> = section.assets

        private val directories: Set<TreePath> = section.folders.map { it.path }.toSet()

        /** Case-insensitive value → indexed paths, for the §A2 step-6 rescue scan (never crosses roots). */
        private val byLowercaseValue: Map<String, List<TreePath>> =
            (section.pages.map { it.path } + assets + directories).groupBy { it.value.lowercase() }

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

        override fun assetUrl(asset: TreePath): String = "/assets/" + root.value + "/" + PercentCoding.encodePath(asset.value)

        override fun caseInsensitiveMatches(path: TreePath): List<TreePath> =
            byLowercaseValue[path.value.lowercase()].orEmpty().filterNot { it == path }
    }

    companion object {
        /** The pre-first-build snapshot: empty but fully usable, so readers are total from startup. */
        val EMPTY: PageIndex = PageIndex(emptyList())

        /** [view]'s unknown-root answer: a [SectionView] over nothing (root-independent lookups). */
        private val EMPTY_VIEW: PageIndexView = SectionView(RootSection(RootName.PRIMARY, emptyList(), emptyList(), emptySet()))
    }
}

/**
 * One indexed page: identity (chunk 4), its root (multi-root C2), canonical URL (§A4), render
 * metadata (chunk 3), and the read payload ([markdown] + [contentHash]) captured from the same
 * bytes the render saw.
 *
 * [urlPath] is the canonical URL as a [TreePath] of DECODED slug segments (e.g.
 * `notes/release-notes-2026`) - the form the alias registry stores, root-relative; null marks a
 * same-parent slug-collision loser, excluded from path space but fully reachable via its
 * [permalink]. [url] is the wire form: `/{root}/` + the RFC 3986 percent-encoded segments
 * (unicode slugs are legal and encoded on emit; the root slug is URL-safe by construction and
 * never encoded - C3, ADR-0011 D3).
 *
 * Carrying [markdown] and [contentHash] here is what makes every page response internally
 * coherent: markdown, html, hash, and citation all come from ONE published snapshot, so an
 * on-disk edit between rescans can never pair stale html with a fresh hash (the exact citation
 * invariant Phase 5 heading-citations lean on).
 */
data class IndexedPage(
    val id: PageId,
    val root: RootName,
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
     * The last commit touching this file in Git mode, captured at index time and served disk-free
     * exactly like [contentHash]; null off Git, for an as-yet-uncommitted page, or in Phase 1-2.
     *
     * **As-of-the-last-reindex invariant:** `commit` reflects the page's history at the moment of its last
     * (re)index, never a request-time Git read. During an in-flight save a citation may momentarily pair a
     * NEW [contentHash] with the PRIOR `commit` (a watcher rebuild slipping into the post-CAS/pre-commit
     * window) until the save's own reindex republishes a coherent snapshot — bounded, self-healing,
     * sub-second, never a durable mismatch.
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
    val url: String? = urlPath?.let { "/" + root.value + "/" + PercentCoding.encodePath(it.value) }

    /** This page's real identity: the [RootedPageId] seam every id-bearing surface funnels through. */
    val rooted: RootedPageId get() = RootedPageId(root, id)

    /** The permanent ID permalink — the §A4 durability layer, unaffected by any path change. */
    val permalink: String get() = rooted.permalink
}
