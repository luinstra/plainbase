package com.plainbase.domain.service

import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import java.util.concurrent.atomic.AtomicReference

/** One instance can bridge both index listener contracts before its pre-compute is attached. */
class DiscussionPublicationSignal : IndexBuilder.PublicationListener, PageReindexListener {
    private val precompute = AtomicReference<AnchorPrecompute?>(null)

    fun attach(precompute: AnchorPrecompute) {
        this.precompute.set(precompute)
    }

    fun rootSynced(root: RootName) {
        precompute.get()?.rootSynced(root)
    }

    override fun published(snapshot: PageIndex, retired: Set<RootedPageId>) {
        precompute.get()?.published(snapshot, retired)
    }

    override fun reindexed(root: RootName, page: IndexedPage) {
        precompute.get()?.reindexed(root, page)
    }
}
