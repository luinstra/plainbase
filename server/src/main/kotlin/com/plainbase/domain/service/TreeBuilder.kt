package com.plainbase.domain.service

import com.plainbase.domain.content.ContentFolder
import com.plainbase.domain.content.PercentCoding
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex

/**
 * A node of the nav tree (§A4 `/api/v1/tree` shape, chunk 6 maps it to DTOs).
 *
 * Page nodes carry [Page.url] — the canonical path URL, null for a slug-collision loser (the UI
 * links those via `/p/{id}`). Folder nodes carry [Folder.url] — the folder's `/docs` URL prefix,
 * where the SPA renders the folder landing view (ADR-0003); null for a collision-loser subtree.
 * Folder [Folder.title] comes from `_folder.yaml`, else null (the UI falls back to [Folder.name]);
 * the root folder is the synthetic node with empty name and null path.
 */
sealed interface TreeNode {

    data class Folder(
        val name: String,
        val title: String?,
        /** The folder's content path; null only for the synthetic root node. */
        val path: TreePath?,
        /** The folder's `/docs` URL prefix on the wire (encoded like page urls); null for a collision-loser subtree. */
        val url: String?,
        val children: List<TreeNode>,
    ) : TreeNode

    data class Page(
        val id: PageId,
        val title: String,
        val slug: String,
        val path: TreePath,
        val url: String?,
        val status: String,
    ) : TreeNode
}

/**
 * Builds the nav tree from a [PageIndex] snapshot, per the §A4 rules:
 *  - **ordering (documented, not frozen):** within a folder, children sort by (`_folder.yaml`
 *    `order` if present else MAX, then lowercased title-or-name compared by code point — locale
 *    independent, no ICU). Pages have no order key.
 *  - `index.md` is an ordinary child page (no folder-attachment special case in Phase 1).
 *  - all pages appear regardless of `status` (`active` default — filtering arrives with auth);
 *  - folders with no pages anywhere beneath are omitted.
 *
 * Pure and deterministic over the snapshot; chunk 6 memoizes the tree JSON per snapshot (§C4).
 */
object TreeBuilder {

    fun build(index: PageIndex): TreeNode.Folder {
        val pagesByParent = index.pages.groupBy { it.path.parent }
        val foldersByParent = index.folders.groupBy { it.path.parent }
        val folderUrls = CanonicalUrlBuilder.folderUrlPaths(index.folders)
        // The synthetic root's URL prefix is bare `/docs` (its own route in the SPA, not a landing view).
        return TreeNode.Folder(
            name = "",
            title = null,
            path = null,
            url = "/docs",
            children = childrenOf(null, pagesByParent, foldersByParent, folderUrls),
        )
    }

    private fun childrenOf(
        dir: TreePath?,
        pagesByParent: Map<TreePath?, List<IndexedPage>>,
        foldersByParent: Map<TreePath?, List<ContentFolder>>,
        folderUrls: Map<TreePath, TreePath?>,
    ): List<TreeNode> {
        val folders = foldersByParent[dir].orEmpty().mapNotNull { folder ->
            val children = childrenOf(folder.path, pagesByParent, foldersByParent, folderUrls)
            if (children.isEmpty()) return@mapNotNull null // no pages anywhere beneath -> omitted
            Sortable(
                order = folder.meta?.order,
                sortTitle = folder.meta?.title ?: folder.path.name,
                node = TreeNode.Folder(
                    name = folder.path.name,
                    title = folder.meta?.title,
                    path = folder.path,
                    url = folderUrls.getValue(folder.path)?.let { "/docs/" + PercentCoding.encodePath(it.value) },
                    children = children,
                ),
            )
        }
        val pages = pagesByParent[dir].orEmpty().map { page ->
            Sortable(
                order = null, // pages have no order key (§5.2)
                sortTitle = page.title,
                node = TreeNode.Page(
                    id = page.id,
                    title = page.title,
                    slug = page.slug,
                    path = page.path,
                    url = page.url,
                    status = page.frontmatter.scalar("status") ?: "active",
                ),
            )
        }
        return (folders + pages).sortedWith(ORDERING).map { it.node }
    }

    private data class Sortable(
        val order: Int?,
        val sortTitle: String,
        val node: TreeNode,
    )

    private val ORDERING: Comparator<Sortable> =
        compareBy<Sortable> { it.order ?: Int.MAX_VALUE }
            .thenComparing({ it.sortTitle.lowercase() }, ::compareCodePoints)

    /**
     * Code-point comparison, not UTF-16 [Char] comparison: a `String.compareTo` would sort BMP code
     * points U+E000..U+FFFF before any supplementary character (their UTF-16 units sort below
     * surrogates) — the documented collation says code points.
     */
    private fun compareCodePoints(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a.codePointAt(i)
            val cb = b.codePointAt(j)
            if (ca != cb) return ca.compareTo(cb)
            i += Character.charCount(ca)
            j += Character.charCount(cb)
        }
        return (a.length - i).compareTo(b.length - j)
    }
}
