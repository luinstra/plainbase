package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.ProposalBaseReader

/**
 * The [ProposalBaseReader] impl (P1a, B4) — the [GuardedReadFacade] neighbor, over the SAME [IndexBuilder]
 * (`current: PageIndex`) + per-root [ContentStore]s the read facade uses. Each call reads the published immutable
 * snapshot, so there is no shared mutable state and no `@Volatile`. `domain/` stays framework-free (the port lives
 * there; this impl frameworks-side).
 *
 * It is the ONE place the store's root-loss classification crosses OUT of `LocalContentStore` into another
 * subsystem: [currentBytes] is a straight pass-through of [ContentRead] from the store port to the domain service -
 * no translation, no re-nulling - which is what lets each of the four `ProposalService` consumers name its own
 * behavior for a downed root at the compiler's insistence.
 *
 * `occupied` is the FILE-PATH collision (`byPath` ∪ `assets`) — the analog the apply-time `WritePipeline.create`
 * rejects via `createExclusive` → `AlreadyExists`. The canonical-URL/slug collision (`SlugConflict`) is a SECOND
 * apply-time rejection deferred to P1b's enrichment: apply still rejects it correctly; this pre-apply triage flag
 * merely under-reports that one case, never over-reports.
 */
class IndexProposalBaseReader(
    private val indexBuilder: IndexBuilder,
    private val stores: (RootName) -> ContentStore,
) : ProposalBaseReader {

    override fun pathOf(root: RootName, pageId: PageId): RootedPath? =
        indexBuilder.current.byId[pageId]?.takeIf { it.root == root }?.let { RootedPath(it.root, it.path) }

    override fun currentBytes(target: RootedPath): ContentRead =
        stores(target.root).readClassified(target.path)

    override fun occupied(target: RootedPath): Boolean {
        val snapshot = indexBuilder.current
        return target in snapshot.byPath || target.path in snapshot.section(target.root).assets
    }
}
