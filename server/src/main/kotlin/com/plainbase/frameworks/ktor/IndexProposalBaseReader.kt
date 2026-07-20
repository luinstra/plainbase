package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.service.AbsenceClassifier
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.ProposalBaseReader

/**
 * The [ProposalBaseReader] impl (P1a, B4) — the [GuardedReadFacade] neighbor, over the SAME [IndexBuilder]
 * (`current: PageIndex`) + per-root [ContentStore]s the read facade uses. Each call reads the published immutable
 * snapshot, so there is no shared mutable state and no `@Volatile`. `domain/` stays framework-free (the port lives
 * there; this impl frameworks-side).
 *
 * It is the ONE place the store's root-loss classification crosses OUT of `LocalContentStore` into another
 * subsystem: [currentBytes] hands the domain a [ContentRead] - no re-nulling, no translation - which is what lets
 * each of the four `ProposalService` consumers name its own behavior for a downed root at the compiler's insistence.
 *
 * Since C1 that crossing runs through the [AbsenceClassifier], because a `StoreRead` is not yet an answer: this
 * adapter does not DECIDE anything about an absent page, it just hands the store's observation and the target to
 * the one domain rule that may. (An adapter re-deriving 404-vs-503 for itself is precisely the bug C1 exists to
 * remove; it does not get to grow back here.)
 *
 * `occupied` is the FILE-PATH collision (`byPath` ∪ `assets`) — the analog the apply-time `WritePipeline.create`
 * rejects via `createExclusive` → `AlreadyExists`. The canonical-URL/slug collision (`SlugConflict`) is a SECOND
 * apply-time rejection deferred to P1b's enrichment: apply still rejects it correctly; this pre-apply triage flag
 * merely under-reports that one case, never over-reports.
 */
class IndexProposalBaseReader(
    private val indexBuilder: IndexBuilder,
    private val stores: (RootName) -> ContentStore,
    private val absence: AbsenceClassifier,
) : ProposalBaseReader {

    override fun pathOf(root: RootName, pageId: PageId): RootedPath? =
        indexBuilder.current.pageAt(RootedPageId(root, pageId))?.let { RootedPath(it.root, it.path) }

    override fun currentBytes(target: RootedPath): ContentRead = absence.read(stores(target.root), target)

    override fun occupied(target: RootedPath): Boolean {
        val snapshot = indexBuilder.current
        return target in snapshot.byPath || target.path in snapshot.section(target.root).assets
    }
}
