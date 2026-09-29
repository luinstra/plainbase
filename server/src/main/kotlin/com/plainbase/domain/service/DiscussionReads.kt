package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.discussion.CollectionVisit
import com.plainbase.domain.discussion.CommentId
import com.plainbase.domain.discussion.DiscussionAssembly
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionRead
import com.plainbase.domain.discussion.DiscussionRow
import com.plainbase.domain.discussion.DiscussionRowData
import com.plainbase.domain.discussion.DiscussionRows
import com.plainbase.domain.discussion.DiscussionStore
import com.plainbase.domain.discussion.EntriesRead
import com.plainbase.domain.discussion.EntryName
import com.plainbase.domain.discussion.IdentityDigest
import com.plainbase.domain.discussion.PageAttachment
import com.plainbase.domain.discussion.RowDerivation
import com.plainbase.domain.discussion.RowUpdate
import com.plainbase.domain.discussion.UnreadableReason
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import java.util.PriorityQueue

sealed interface DiscussionFacts {
    data object Missing : DiscussionFacts
    data object Unknown : DiscussionFacts

    data class Known(
        val state: String,
        val pageId: PageId?,
        val status: String?,
        val starterKey: String?,
        val authorKey: String?,
    ) : DiscussionFacts
}

sealed interface DiscussionClaim {
    data class Present(val facts: DiscussionFacts) : DiscussionClaim
    data object Absent : DiscussionClaim
    data object Unknown : DiscussionClaim
}

data class DiscussionSummary(
    val id: DiscussionId,
    val state: String,
    val reason: String?,
    val pageId: PageId?,
    val pagePath: TreePath?,
    val status: String?,
    val anchorKind: String?,
    val anchorHash: String?,
    val starterKey: String?,
    val created: Long?,
    val updated: Long?,
    val commentCount: Int,
)

data class PagedDiscussionSummaries(val discussions: List<DiscussionSummary>, val next: DiscussionId?)

sealed interface DetailPage {
    data object Absent : DetailPage
    data class Content(val read: DiscussionRead, val nextComment: CommentId?) : DetailPage
}

@Suppress("TooGenericExceptionCaught", "SwallowedException", "ThrowsCount")
class DiscussionReads(
    private val rows: DiscussionRows,
    private val store: DiscussionStore,
    private val fullReads: DiscussionFullReads,
    private val sync: DiscussionSyncState,
    private val availability: RootAvailability,
) {
    fun facts(root: RootName, id: DiscussionId, comment: CommentId? = null): DiscussionFacts = when (val claim = claim(root, id, comment)) {
        is DiscussionClaim.Present -> claim.facts
        DiscussionClaim.Absent -> DiscussionFacts.Missing
        DiscussionClaim.Unknown -> DiscussionFacts.Unknown
    }

    fun claim(root: RootName, id: DiscussionId, comment: CommentId? = null): DiscussionClaim {
        sync.current(root)
        if (!sync.isUnsynced(root)) {
            try {
                val row = rows.row(root, id) ?: return if (available(root)) DiscussionClaim.Absent else DiscussionClaim.Unknown
                if (!available(root)) return DiscussionClaim.Present(DiscussionFacts.Unknown)
                if (row.state == "failed") return DiscussionClaim.Present(DiscussionFacts.Unknown)
                val author = comment?.let { rows.entry(root, id, EntryName.Comment(it))?.authorKey }
                return DiscussionClaim.Present(DiscussionFacts.Known(row.state, row.pageId, row.status, row.starterKey, author))
            } catch (failure: RootUnavailable) {
                return DiscussionClaim.Unknown
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw failure
            } catch (failure: Exception) {
                sync.enter(root, failure.message ?: "discussion facts query failed")
            }
        }
        if (!available(root)) return DiscussionClaim.Unknown
        return try {
            val only = buildSet {
                add(EntryName.Marker)
                comment?.let { add(EntryName.Comment(it)) }
            }
            val assembled = fullReads.withNarrowed {
                val read = store.read(root, id, only)
                if (read is EntriesRead.Symlinked || read is EntriesRead.Failed) return@withNarrowed null
                DiscussionAssembly.assemble(id, read, retain = { it.id == comment })
            }
            when (assembled) {
                null -> DiscussionClaim.Unknown
                DiscussionRead.Absent -> DiscussionClaim.Absent
                is DiscussionRead.Ok -> DiscussionClaim.Present(
                    DiscussionFacts.Known(
                    state = "ok",
                    pageId = assembled.files.marker.value.page.pageId,
                    status = assembled.files.marker.value.status.wire,
                    starterKey = IdentityDigest.of(assembled.files.marker.value.startedBy.actor.subject),
                    authorKey = comment?.let { name ->
                        assembled.files.comments.firstOrNull { it.name == EntryName.Comment(name) }
                            ?.value?.author?.actor?.subject?.let(IdentityDigest::of)
                    },
                ),
                )
                is DiscussionRead.Failed -> DiscussionClaim.Unknown
                is DiscussionRead.Unreadable -> if (assembled.reason == UnreadableReason.SYMLINK) {
                    DiscussionClaim.Unknown
                } else {
                    DiscussionClaim.Present(DiscussionFacts.Known("unreadable", assembled.pageId, null, null, null))
                }
                is DiscussionRead.Incomplete -> DiscussionClaim.Present(DiscussionFacts.Known("incomplete", null, null, null, null))
            }
        } catch (_: RootUnavailable) {
            DiscussionClaim.Unknown
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        } catch (_: Exception) {
            DiscussionClaim.Unknown
        }
    }

    fun pageDiscussions(
        root: RootName,
        page: IndexedPage,
        snapshot: PageIndex,
        after: DiscussionId? = null,
        limit: Int = PAGE_LIMIT,
    ): PagedDiscussionSummaries {
        require(limit in 1..PAGE_LIMIT)
        sync.current(root)
        requireAvailable(root)
        if (!sync.isUnsynced(root)) {
            try {
                val paged = PageAttachment.rows(rows, root, page, snapshot, after, limit)
                return PagedDiscussionSummaries(paged.rows.map(::summary), paged.next)
            } catch (failure: RootUnavailable) {
                throw failure
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw failure
            } catch (failure: Exception) {
                sync.enter(root, failure.message ?: "discussion page rows query failed")
            }
        }
        return pageFiles(root, page, snapshot, after, limit)
    }

    fun rootDiscussions(
        root: RootName,
        after: DiscussionId? = null,
        limit: Int = ROOT_LIMIT,
    ): PagedDiscussionSummaries {
        require(limit in 1..ROOT_LIMIT)
        sync.current(root)
        requireAvailable(root)
        if (!sync.isUnsynced(root)) {
            try {
                val selected = rows.rowsAfter(root, after, limit)
                val next = if (selected.size == limit) selected.last().id else null
                return PagedDiscussionSummaries(selected.map(::summary), next)
            } catch (failure: RootUnavailable) {
                throw failure
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw failure
            } catch (failure: Exception) {
                sync.enter(root, failure.message ?: "discussion root rows query failed")
            }
        }
        return rootFiles(root, after, limit)
    }

    fun detail(
        root: RootName,
        id: DiscussionId,
        afterComment: CommentId? = null,
        limit: Int = DETAIL_LIMIT,
    ): DetailPage {
        require(limit in 1..DETAIL_LIMIT)
        sync.current(root)
        requireAvailable(root)
        return try {
            fullReads.withFullRead(root, id) { entries ->
                val names = (entries as? EntriesRead.Present)?.entries
                    ?.mapNotNull { it.name as? EntryName.Comment }
                    ?.map { it.id }
                    ?.filter { afterComment == null || it.value > afterComment.value }
                    ?.sortedBy { it.value }
                    .orEmpty()
                val selectedNames = names.take(limit)
                val window = selectedNames.toSet()
                val next = if (names.size > limit) selectedNames.lastOrNull() else null
                val read = DiscussionAssembly.assemble(
                    id,
                    entries,
                    retain = { it.id in window },
                )
                when (read) {
                    DiscussionRead.Absent -> DetailPage.Absent
                    is DiscussionRead.Failed -> throw DiscussionReadFailed(root, read.cause)
                    else -> DetailPage.Content(read, next)
                }
            }
        } catch (failure: RootUnavailable) {
            throw failure
        } catch (failure: DiscussionReadFailed) {
            throw failure
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        } catch (failure: Exception) {
            throw DiscussionReadFailed(root, failure.message ?: "detail read failed")
        }
    }

    private fun pageFiles(
        root: RootName,
        page: IndexedPage,
        snapshot: PageIndex,
        after: DiscussionId?,
        limit: Int,
    ): PagedDiscussionSummaries {
        val selected = PriorityQueue<DiscussionSummary>(limit + 1, compareByDescending { it.id.value })
        var survivors = 0
        visit(root) { id, _ ->
            if (after != null && id.value <= after.value) return@visit
            val update = fullReads.withNarrowed {
                val marker = try {
                    store.read(root, id, setOf(EntryName.Marker))
                } catch (failure: RootUnavailable) {
                    throw failure
                } catch (failure: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw failure
                } catch (failure: Exception) {
                    throw DiscussionReadFailed(root, failure.message ?: "marker read failed")
                }
                when (marker) {
                    is EntriesRead.Failed -> throw DiscussionReadFailed(root, marker.cause)
                    is EntriesRead.Symlinked -> throw DiscussionReadFailed(root, "symlink")
                    EntriesRead.Absent -> null
                    else -> RowDerivation.derive(id, marker).update
                }
            }
            if (update == null) return@visit
            if (update.state == "incomplete") return@visit
            val row = update.toDiscussionRow(root, id)
            if (row == null || (row.pageId == null && row.pagePath == null)) return@visit
            if (belongsToPage(row, root, page, snapshot)) {
                val candidate = summary(row)
                survivors++
                if (selected.size < limit) {
                    selected += candidate
                } else if (id.value < selected.peek().id.value) {
                    selected.remove()
                    selected += candidate
                }
            }
        }
        val candidates = selected.toList().sortedBy { it.id.value }
        val summaries = candidates.mapNotNull { candidate ->
            val full = fullSummary(root, candidate.id)
            when {
                full == null -> null
                full.state == "failed" -> full.copy(
                    pageId = candidate.pageId,
                    pagePath = candidate.pagePath,
                    status = candidate.status,
                    anchorKind = candidate.anchorKind,
                    anchorHash = candidate.anchorHash,
                    starterKey = candidate.starterKey,
                )
                full.pageId == page.id ||
                    (full.pagePath == page.path && full.pageId?.let { snapshot.pageAt(RootedPageId(root, it)) } == null) -> full
                else -> null
            }
        }
        val next = if (survivors > limit) candidates.lastOrNull()?.id else null
        return PagedDiscussionSummaries(summaries, next)
    }

    private fun rootFiles(root: RootName, after: DiscussionId?, limit: Int): PagedDiscussionSummaries {
        val selected = PriorityQueue<DiscussionId>(limit + 1, compareByDescending { it.value })
        var count = 0
        visit(root) { id, _ ->
            if (after != null && id.value <= after.value) return@visit
            count++
            if (selected.size < limit) {
                selected += id
            } else if (id.value < selected.peek().value) {
                selected.remove()
                selected += id
            }
        }
        val ids = selected.toList().sortedBy { it.value }
        val summaries = ids.mapNotNull { id -> fullSummary(root, id) }
        val next = if (count > limit) ids.lastOrNull() else null
        return PagedDiscussionSummaries(summaries, next)
    }

    private fun fullSummary(root: RootName, id: DiscussionId): DiscussionSummary? = try {
        fullReads.withFullRead(root, id) { read ->
            val derived = RowDerivation.derive(id, read)
            when (val update = derived.update) {
                RowUpdate.Delete -> null
                is RowUpdate.Unknown -> failedSummary(id, update.cause)
                is RowUpdate.Failed -> failedSummary(id, update.reason)
                is RowUpdate.Upsert -> update.row.toSummary(id)
            }
        }
    } catch (failure: RootUnavailable) {
        throw failure
    } catch (failure: InterruptedException) {
        Thread.currentThread().interrupt()
        throw failure
    } catch (failure: Exception) {
        failedSummary(id, failure.message ?: "discussion read failed")
    }

    private fun visit(root: RootName, visitor: (DiscussionId, Boolean) -> Unit) {
        val result = try {
            store.visit(root, visitor)
        } catch (failure: RootUnavailable) {
            throw failure
        } catch (failure: DiscussionReadFailed) {
            throw failure
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        } catch (failure: Exception) {
            throw DiscussionReadFailed(root, failure.message ?: "collection visit failed")
        }
        when (result) {
            is CollectionVisit.Visited,
            CollectionVisit.Absent,
            -> Unit
            CollectionVisit.Symlinked -> throw DiscussionReadFailed(root, "symlink")
            is CollectionVisit.Failed -> throw DiscussionReadFailed(root, result.cause)
        }
    }

    private fun available(root: RootName): Boolean = availability.current().isAvailable(root)

    private fun requireAvailable(root: RootName) {
        val unavailable = availability.current().unavailable[root] ?: return
        throw RootUnavailable(root, unavailable.cause)
    }

    private fun summary(row: DiscussionRow): DiscussionSummary = DiscussionSummary(
        id = row.id,
        state = row.state,
        reason = row.reason,
        pageId = row.pageId,
        pagePath = row.pagePath,
        status = row.status,
        anchorKind = row.anchorKind,
        anchorHash = row.anchorHash,
        starterKey = row.starterKey,
        created = row.created,
        updated = row.updated,
        commentCount = row.commentCount,
    )

    private fun RowUpdate.toDiscussionRow(root: RootName, id: DiscussionId): DiscussionRow? = when (this) {
        RowUpdate.Delete -> null
        is RowUpdate.Unknown -> throw DiscussionReadFailed(root, cause)
        is RowUpdate.Failed -> throw DiscussionReadFailed(root, reason)
        is RowUpdate.Upsert -> row.toSummary(id).toRow(root)
    }

    private fun DiscussionSummary.toRow(root: RootName) = DiscussionRow(
        root = root,
        id = id,
        state = state,
        reason = reason,
        stamp = null,
        pageId = pageId,
        pagePath = pagePath,
        status = status,
        anchorKind = anchorKind,
        anchorHash = anchorHash,
        starterKey = starterKey,
        created = created,
        updated = updated,
        commentCount = commentCount,
    )

    private fun DiscussionRowData.toSummary(id: DiscussionId) = DiscussionSummary(
        id = id,
        state = state,
        reason = reason,
        pageId = pageId,
        pagePath = pagePath,
        status = status,
        anchorKind = anchorKind,
        anchorHash = anchorHash,
        starterKey = starterKey,
        created = created,
        updated = updated,
        commentCount = commentCount,
    )

    private fun belongsToPage(
        row: DiscussionRow,
        root: RootName,
        page: IndexedPage,
        snapshot: PageIndex,
    ): Boolean = row.pageId == page.id ||
        (row.pagePath == page.path && row.pageId?.let { snapshot.pageAt(RootedPageId(root, it)) == null } != false)

    companion object {
        const val PAGE_LIMIT = 200
        const val ROOT_LIMIT = 50
        const val DETAIL_LIMIT = 50
    }
}

private fun failedSummary(id: DiscussionId, reason: String): DiscussionSummary = DiscussionSummary(
    id = id,
    state = "failed",
    reason = reason,
    pageId = null,
    pagePath = null,
    status = null,
    anchorKind = null,
    anchorHash = null,
    starterKey = null,
    created = null,
    updated = null,
    commentCount = 0,
)
