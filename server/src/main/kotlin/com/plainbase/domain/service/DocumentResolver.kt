package com.plainbase.domain.service

import com.plainbase.domain.content.PercentCoding
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath

sealed interface DocumentResolution {
    data class Serve(val page: IndexedPage, val canonicalUrl: String) : DocumentResolution
    data class Redirect(val page: IndexedPage, val location: String) : DocumentResolution
    data object FolderListing : DocumentResolution
    data object Miss : DocumentResolution
}

/** One snapshot's navigation tree and folder lookups for document resolution. */
class DocumentIndex internal constructor(
    val snapshot: PageIndex,
    val root: RootName,
    val tree: TreeNode.Folder,
) {
    private val folders = buildList {
        fun visit(folder: TreeNode.Folder) {
            add(folder)
            folder.children.filterIsInstance<TreeNode.Folder>().forEach(::visit)
        }
        visit(tree)
    }
    internal val foldersByUrl = folders.mapNotNull { folder -> folder.url?.let { it to folder } }.toMap()
    internal val foldersByPath = folders.associateBy { it.path }
}

/** Resolves document URLs against the same tree that drives browser navigation. */
object DocumentResolver {
    fun index(snapshot: PageIndex, root: RootName, folderLabels: Map<String, String>): DocumentIndex =
        DocumentIndex(snapshot, root, TreeBuilder.build(snapshot, root, folderLabels))

    fun resolve(
        index: DocumentIndex,
        path: TreePath?,
        aliasOf: (RootedPath) -> RootedPageId?,
        bindsLive: (RootedPageId) -> Boolean,
        servable: (IndexedPage) -> Boolean,
        unverifiedAlias: (RootedPageId) -> Unit,
    ): DocumentResolution {
        fun landing(folder: TreeNode.Folder): IndexedPage? = TreeBuilder.landingPage(folder)
            ?.let { index.snapshot.pageAt(RootedPageId(index.root, it.id)) }
            ?.takeIf(servable)

        fun owner(path: TreePath?): IndexedPage? = when (val claimed = claim(index, path, aliasOf, bindsLive, servable)) {
            is Claim.Page -> claimed.page
            is Claim.Folder -> landing(claimed.folder)
            is Claim.Unverified, Claim.Blocked, Claim.None -> null
        }

        fun destination(page: IndexedPage): String {
            val fallback = page.url ?: page.permalink
            if (page.root != index.root) return fallback
            val folder = index.foldersByPath[page.path.parent] ?: return fallback
            val folderUrl = folder.url ?: return fallback
            if (landing(folder)?.rooted != page.rooted) return fallback
            val tail = folderUrl.removePrefix("/${index.root.value}").removePrefix("/")
            val folderPath = if (tail.isEmpty()) {
                null
            } else {
                val decoded = checkNotNull(PercentCoding.decodeOnce(tail) as? PercentCoding.DecodeResult.Success)
                checkNotNull(TreePath.of(decoded.value))
            }
            return if (owner(folderPath)?.rooted == page.rooted) folderUrl else fallback
        }

        return when (val claimed = claim(index, path, aliasOf, bindsLive, servable)) {
            is Claim.Page -> {
                val target = destination(claimed.page)
                if (target == url(index.root, path)) {
                    DocumentResolution.Serve(claimed.page, target)
                } else {
                    DocumentResolution.Redirect(claimed.page, target)
                }
            }
            is Claim.Folder -> landing(claimed.folder)
                ?.let { DocumentResolution.Serve(it, checkNotNull(claimed.folder.url)) }
                ?: DocumentResolution.FolderListing
            is Claim.Unverified -> {
                unverifiedAlias(claimed.target)
                DocumentResolution.Miss
            }
            Claim.Blocked, Claim.None -> DocumentResolution.Miss
        }
    }

    private fun url(root: RootName, path: TreePath?): String = "/${root.value}" +
        (path?.let { "/${PercentCoding.encodePath(it.value)}" } ?: "")

    private fun claim(
        index: DocumentIndex,
        path: TreePath?,
        aliasOf: (RootedPath) -> RootedPageId?,
        bindsLive: (RootedPageId) -> Boolean,
        servable: (IndexedPage) -> Boolean,
    ): Claim {
        if (path == null) return Claim.Folder(index.tree)
        val rooted = RootedPath(index.root, path)
        index.snapshot.byUrlPath[rooted]?.let { page ->
            return if (servable(page)) Claim.Page(page) else Claim.Blocked
        }
        aliasOf(rooted)?.let { target ->
            index.snapshot.pageAt(target)?.let { page ->
                return if (servable(page)) Claim.Page(page) else Claim.Blocked
            }
            if (bindsLive(target)) return Claim.Unverified(target)
        }
        return index.foldersByUrl[url(index.root, path)]?.let(Claim::Folder) ?: Claim.None
    }

    private sealed interface Claim {
        data class Page(val page: IndexedPage) : Claim
        data class Folder(val folder: TreeNode.Folder) : Claim
        data class Unverified(val target: RootedPageId) : Claim
        data object Blocked : Claim
        data object None : Claim
    }
}
