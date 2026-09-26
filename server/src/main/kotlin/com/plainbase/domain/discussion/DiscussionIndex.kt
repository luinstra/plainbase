package com.plainbase.domain.discussion

import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName

/** Rebuildable read and publication seam for discussions; the files remain authoritative. */
interface DiscussionIndex {
    fun pageDiscussionCount(root: RootName, pageId: PageId): Int
    fun publish(root: RootName, id: DiscussionId, read: DiscussionRead, markerChanged: Boolean)
    fun publishFailed(root: RootName, cause: Exception)
}
