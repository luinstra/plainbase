package com.plainbase.domain.service

import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionStore
import com.plainbase.domain.discussion.EntriesRead
import com.plainbase.domain.root.RootName
import java.util.concurrent.Semaphore

class DiscussionFullReads(private val store: DiscussionStore) {
    private val full = Semaphore(1, true)
    private val narrowed = Semaphore(8, true)

    fun <T> withFullRead(root: RootName, id: DiscussionId, block: (EntriesRead) -> T): T =
        withPermit { block(store.read(root, id)) }

    fun <T> withPermit(block: () -> T): T = full.withPermit(block)

    fun <T> withNarrowed(block: () -> T): T = narrowed.withPermit(block)

    private inline fun <T> Semaphore.withPermit(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }
}
