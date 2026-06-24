package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.ProposalBaseReader

/**
 * The [ProposalBaseReader] impl (P1a, B4) — the [GuardedReadFacade] neighbor, over the SAME [IndexBuilder]
 * (`current: PageIndex`) + [ContentStore] the read facade uses. Each call reads the published immutable snapshot,
 * so there is no shared mutable state and no `@Volatile`. `domain/` stays framework-free (the port lives there;
 * this impl frameworks-side).
 *
 * `occupied` is the FILE-PATH collision (`byPath` ∪ `assets`) — the analog the apply-time `WritePipeline.create`
 * rejects via `createExclusive` → `AlreadyExists`. The canonical-URL/slug collision (`SlugConflict`) is a SECOND
 * apply-time rejection deferred to P1b's enrichment: apply still rejects it correctly; this pre-apply triage flag
 * merely under-reports that one case, never over-reports.
 */
class IndexProposalBaseReader(
    private val indexBuilder: IndexBuilder,
    private val contentStore: ContentStore,
) : ProposalBaseReader {

    override fun pathOf(pageId: PageId): TreePath? = indexBuilder.current.byId[pageId]?.path

    override fun currentBytes(path: TreePath): ByteArray? = contentStore.read(path)

    override fun occupied(path: TreePath): Boolean {
        val snapshot = indexBuilder.current
        return path in snapshot.byPath || path in snapshot.assets
    }
}
