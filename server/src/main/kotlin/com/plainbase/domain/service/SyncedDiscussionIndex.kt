package com.plainbase.domain.service

import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionIndex
import com.plainbase.domain.discussion.DiscussionRows
import com.plainbase.domain.discussion.DiscussionStore
import com.plainbase.domain.discussion.EntriesRead
import com.plainbase.domain.discussion.RowDerivation
import com.plainbase.domain.discussion.RowUpdate
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName

@Suppress("TooGenericExceptionCaught")
class SyncedDiscussionIndex(
    private val rows: DiscussionRows,
    private val store: DiscussionStore,
    internal val fullReads: DiscussionFullReads,
    private val sync: DiscussionSyncState,
) : DiscussionIndex {
    override fun publish(root: RootName, id: DiscussionId, markerChanged: Boolean, read: () -> EntriesRead) {
        sync.current(root)
        rows.writing {
            val stamp = store.stamp(root, id)
            val derived = fullReads.withPermit { RowDerivation.derive(id, read()) }
            val update = derived.update
            if (update is RowUpdate.Unknown) throw IllegalStateException(update.cause)
            apply(root, id, update, stamp, dropMatch = markerChanged)
        }
    }

    override fun pageDiscussionCount(root: RootName, pageId: PageId): Int {
        if (sync.isUnsynced(root)) throw DiscussionReadFailed(root, "unsynced")
        return try {
            rows.pageCount(root, pageId)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        } catch (failure: Exception) {
            val reason = failure.message ?: "discussion row count failed"
            sync.enter(root, reason)
            throw DiscussionReadFailed(root, reason, failure)
        }
    }

    override fun publishFailed(root: RootName, cause: Exception) {
        try {
            sync.enter(root, cause.message ?: "discussion publish failed")
        } catch (_: Throwable) {
            // Publication failure reporting must not obscure the write result.
        }
    }
}
