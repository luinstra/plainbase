package com.plainbase.domain.service

import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath

sealed interface DiscussionPageResolution {
    enum class Match { BY_ID, BY_PATH }

    data object Unavailable : DiscussionPageResolution
    data object Orphaned : DiscussionPageResolution

    data class Found(val page: IndexedPage, val match: Match) : DiscussionPageResolution
}

class DiscussionPageResolver(
    private val sync: DiscussionSyncState,
    private val availability: RootAvailability,
    private val absence: AbsenceClassifier,
) {
    fun resolve(root: RootName, pageId: PageId, pagePath: TreePath?, snapshot: PageIndex): DiscussionPageResolution {
        sync.current(root)
        if (!availability.current().isAvailable(root)) return DiscussionPageResolution.Unavailable
        snapshot.pageAt(RootedPageId(root, pageId))?.let {
            return DiscussionPageResolution.Found(it, DiscussionPageResolution.Match.BY_ID)
        }
        pagePath?.let { path ->
            snapshot.byPath[RootedPath(root, path)]?.let {
                return DiscussionPageResolution.Found(it, DiscussionPageResolution.Match.BY_PATH)
            }
        }
        val idAbsence = absence.absenceOfId(root, pageId)
        val pathAbsence = pagePath?.let { absence.absenceAt(RootedPath(root, it)) } ?: ContentRead.ConfirmedAbsent
        return if (idAbsence == ContentRead.ConfirmedAbsent && pathAbsence == ContentRead.ConfirmedAbsent) {
            DiscussionPageResolution.Orphaned
        } else {
            DiscussionPageResolution.Unavailable
        }
    }
}
