package com.plainbase.domain.service

import com.plainbase.domain.content.ContentFolder
import com.plainbase.domain.content.RawByteOrder
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.render.HeadingSlugger

/**
 * §A4 canonical-URL construction (frozen — owner decision log #7).
 *
 * A page's canonical URL path is its ancestor directory segments (root-first, each slugified)
 * plus the page slug:
 *  - **directory segment** = the `_folder.yaml` `slug:` override if present, else the directory
 *    name — either way passed through PB-SLUG-1 steps 1–6 ([HeadingSlugger.slugify]), empty →
 *    literal `folder`;
 *  - **page slug** = frontmatter `slug` if present, else the filename stem — same steps 1–6,
 *    empty → literal `page`;
 *  - `index.md` is an ordinary page (no directory-URL special case in Phase 1).
 *
 * **Same-parent slug collisions are same-role only (ADR-0002):** sibling PAGES whose slugs are
 * equal contest the segment, and sibling FOLDERS likewise; a page and a folder sharing a segment
 * do NOT collide — they occupy distinct URLs (`setup.md` → `.../setup`, `setup/intro.md` →
 * `.../setup/intro`; a Phase-1 folder has no URL of its own). Within a role the deterministic
 * winner is the entry whose raw on-disk name bytes sort first ([RawByteOrder] — the same rule as
 * the chunk-1 B3 NFC tie-break). Every loser is excluded from path space (a losing FOLDER takes
 * its whole subtree with it) and recorded as an [IdentityIssue.PathSlugCollision]; a loser page
 * stays fully reachable via `/p/{id}`.
 *
 * Output paths are [TreePath]s of DECODED slug segments; percent-encoding happens on emit
 * (`IndexedPage.url`). Same-role per-parent uniqueness still implies full page-URL uniqueness:
 * two pages with equal full URL paths either share a parent (a page-page collision) or their
 * ancestor chains pass through sibling folders with an equal segment (a folder-folder collision);
 * a page never equals a path under its same-named sibling folder — those are strictly longer.
 *
 * Pure domain code, stateless: inputs in, assignments + issues out.
 */
object CanonicalUrlBuilder {

    /** A page's URL inputs: content path, raw on-disk name (the tie-break key), frontmatter `slug`. */
    data class PageInput(
        val path: TreePath,
        val rawName: String,
        val slugOverride: String?,
    )

    /** One page's URL assignment: its [slug] always; its [urlPath] unless it lost path space. */
    data class Assignment(
        val slug: String,
        val urlPath: TreePath?,
    )

    /** All page assignments (keyed by content path) plus the collision issues to persist. */
    data class Result(
        val byPage: Map<TreePath, Assignment>,
        val issues: List<IdentityIssue.PathSlugCollision>,
    )

    fun build(pages: List<PageInput>, folders: List<ContentFolder>): Result {
        val folderSegments = folders.associate { it.path to folderSegment(it) }
        val pageSlugs = pages.associate { it.path to pageSlug(it) }

        // Two independent same-role buckets (ADR-0002): pages contest slugs among sibling pages,
        // folders among sibling folders — a page-folder segment share is two distinct URLs, not a fight.
        val issues = collisions(pages.map { Sibling(it.path, it.rawName, pageSlugs.getValue(it.path)) }) +
            collisions(folders.map { Sibling(it.path, it.rawName, folderSegments.getValue(it.path)) })
        val losers = issues.map { it.loserPath }.toSet()

        val byPage = pages.associate { page ->
            page.path to Assignment(
                slug = pageSlugs.getValue(page.path),
                urlPath = urlPath(page.path, pageSlugs.getValue(page.path), folderSegments, losers),
            )
        }
        return Result(byPage = byPage, issues = issues)
    }

    /** One role's collisions: within each (parent, segment) group the [RawByteOrder] winner keeps it, the rest lose. */
    private fun collisions(siblings: List<Sibling>): List<IdentityIssue.PathSlugCollision> =
        siblings
            .groupBy { it.path.parent?.value to it.segment }
            .values
            .filter { it.size > 1 }
            .flatMap { group ->
                val ordered = group.sortedWith(compareBy(RawByteOrder) { it.rawName })
                ordered.drop(1).map { IdentityIssue.PathSlugCollision(keptPath = ordered.first().path, loserPath = it.path) }
            }

    /**
     * Converts a `redirect_from` frontmatter value (a file-path string like `/old/deployment.md`)
     * to its alias URL path through the same construction (§A4): strip the `.md` extension,
     * slugify each segment (folder fallback for ancestors, page fallback for the leaf). Returns
     * null when no usable segment remains.
     */
    fun redirectUrlPath(value: String): TreePath? {
        val parts = value.trim().removePrefix("/").split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
        if (parts.isEmpty()) return null
        val segments = parts.dropLast(1).map { HeadingSlugger.slugify(it, HeadingSlugger.FOLDER_FALLBACK) } +
            HeadingSlugger.slugify(parts.last().removeSuffix(".md"), HeadingSlugger.PAGE_FALLBACK)
        return TreePath.require(segments.joinToString("/"))
    }

    /** A sibling entry contesting a URL segment under its parent, within its own role's bucket. */
    private data class Sibling(
        val path: TreePath,
        val rawName: String,
        val segment: String,
    )

    /** §A4 directory segment: the `_folder.yaml` override (itself slugified) else the slugified name. */
    private fun folderSegment(folder: ContentFolder): String =
        HeadingSlugger.slugify(folder.meta?.slug ?: folder.path.name, HeadingSlugger.FOLDER_FALLBACK)

    /** §A4 page slug: frontmatter `slug` (itself slugified) else the slugified filename stem. */
    private fun pageSlug(page: PageInput): String =
        HeadingSlugger.slugify(page.slugOverride ?: page.path.name.removeSuffix(".md"), HeadingSlugger.PAGE_FALLBACK)

    /** The page's full URL path, or null when the page or any ancestor folder lost its segment. */
    private fun urlPath(
        page: TreePath,
        slug: String,
        folderSegments: Map<TreePath, String>,
        losers: Set<TreePath>,
    ): TreePath? {
        val ancestors = ancestorsOf(page)
        if (page in losers || ancestors.any { it in losers }) return null
        // Every ancestor of a scanned page is itself a scanned folder; the fallback only guards a
        // caller handing in pages without their folders (unit-test convenience, never the builder).
        val segments = ancestors.map { folderSegments[it] ?: HeadingSlugger.slugify(it.name, HeadingSlugger.FOLDER_FALLBACK) } + slug
        return TreePath.require(segments.joinToString("/"))
    }

    /** The proper ancestor directories of [path], root-first. */
    private fun ancestorsOf(path: TreePath): List<TreePath> =
        (1 until path.segments.size).map { TreePath.require(path.segments.take(it).joinToString("/")) }
}
