package com.plainbase.domain.discussion

import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId

data class PagedDiscussionRows(val rows: List<DiscussionRow>, val next: DiscussionId?)

object PageAttachment {
    private const val RAW_BATCH_SIZE = 200

    fun rows(
        rows: DiscussionRows,
        root: RootName,
        page: IndexedPage,
        snapshot: PageIndex,
        after: DiscussionId?,
        limit: Int,
    ): PagedDiscussionRows {
        require(limit in 1..RAW_BATCH_SIZE)
        val kept = ArrayList<DiscussionRow>(limit)
        var cursor = after
        while (kept.size < limit) {
            val batch = rows.onPageRaw(root, page.id, page.path, cursor, RAW_BATCH_SIZE)
            if (batch.isEmpty()) return PagedDiscussionRows(kept, null)
            for (row in batch) {
                cursor = row.id
                if (belongsToPage(row, root, page, snapshot)) {
                    kept += row
                    if (kept.size == limit) return PagedDiscussionRows(kept, kept.last().id)
                }
            }
            if (batch.size < RAW_BATCH_SIZE) return PagedDiscussionRows(kept, null)
        }
        return PagedDiscussionRows(kept, kept.lastOrNull()?.id)
    }

    private fun belongsToPage(row: DiscussionRow, root: RootName, page: IndexedPage, snapshot: PageIndex): Boolean =
        row.pageId == page.id ||
            (row.pagePath == page.path && row.pageId?.let { snapshot.pageAt(RootedPageId(root, it)) == null } != false)
}
