package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.root.RootName

/** Completeness observed while materializing one root in a full index pass. */
data class ScanCompleteness(val walkComplete: Boolean, val pageReadsComplete: Boolean)

internal data class IndexedSources(val pages: Set<TreePath>, val otherFiles: Set<TreePath>)

internal object FirstIndexCompleteness {
    fun indexedSources(index: PageIndex, root: RootName, metaFileName: String): IndexedSources {
        val section = index.sections.firstOrNull { it.root == root } ?: return IndexedSources(emptySet(), emptySet())
        return IndexedSources(
            pages = section.pages.mapTo(mutableSetOf()) { it.path },
            otherFiles = section.assets + section.folders.filter { it.meta != null }.map { it.path.resolveChild(metaFileName) },
        )
    }

    fun refusal(listed: Set<TreePath>, indexed: IndexedSources, scan: ScanCompleteness?): String? {
        val pages = listed.filterTo(mutableSetOf(), ::isPageSource)
        if (pages.isEmpty()) {
            return "the object prefix holds no page (.md): an empty prefix, or one with no eligible pages, serves nothing"
        }
        if (scan == null) return "the first pass skipped the root"
        if (pages != indexed.pages) {
            val unindexed = pages - indexed.pages
            val unlisted = indexed.pages - pages
            return "the first index has ${unindexed.size} listed page(s) not indexed: ${sample(unindexed)}; " +
                "${unlisted.size} indexed page(s) not listed: ${sample(unlisted)}"
        }
        val otherFiles = listed.filterNotTo(mutableSetOf(), ::isPageSource)
        if (otherFiles != indexed.otherFiles) {
            val unindexed = otherFiles - indexed.otherFiles
            val unlisted = indexed.otherFiles - otherFiles
            return "the first index has ${unindexed.size} listed other file(s) not indexed: ${sample(unindexed)}; " +
                "${unlisted.size} indexed other file(s) not listed: ${sample(unlisted)}"
        }
        if (!scan.walkComplete) {
            return "the first scan did not see the whole listed generation: " +
                "a file was unreadable, missing, stale, or replaced between its listing and its fetch"
        }
        if (!scan.pageReadsComplete) return "the first pass did not complete every page read"
        return null
    }

    private fun sample(paths: Set<TreePath>): String = paths.map { it.value }.sorted().take(5).joinToString(", ")
}
