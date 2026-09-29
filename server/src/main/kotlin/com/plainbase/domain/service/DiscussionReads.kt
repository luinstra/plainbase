package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.discussion.Anchor
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
import com.plainbase.domain.discussion.EntryListing
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
    val starterKind: String? = null,
    val starterLabel: String? = null,
    val quotePreview: String? = null,
)

data class PagedDiscussionSummaries(val discussions: List<DiscussionSummary>, val next: DiscussionId?)

sealed interface DetailPage {
    data object Absent : DetailPage
    data class Content(val read: DiscussionRead, val nextComment: CommentId?, val summary: DiscussionSummary) : DetailPage
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
        accepts: (DiscussionSummary) -> Boolean = { true },
    ): PagedDiscussionSummaries {
        require(limit in 1..ROOT_LIMIT)
        sync.current(root)
        requireAvailable(root)
        if (!sync.isUnsynced(root)) {
            rootRows(root, after, limit, accepts)?.let { return it }
        }
        return rootFiles(root, after, limit, accepts)
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
            val indexed = indexedDetailRow(root, id)
            indexed?.let(::indexedDetailProjection)?.let { return it }
            fullReads.withPermit { readWindow(root, id, afterComment, limit, indexed) }
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

    private fun indexedDetailRow(root: RootName, id: DiscussionId): DiscussionRow? = if (sync.isUnsynced(root)) {
        null
    } else {
        try {
            rows.row(root, id)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        } catch (failure: Exception) {
            sync.enter(root, failure.message ?: "discussion detail row query failed")
            null
        }
    }

    private fun indexedDetailProjection(row: DiscussionRow): DetailPage.Content? {
        val read = when (row.state) {
            "unreadable", "failed" -> DiscussionRead.Unreadable(UnreadableReason.BAD_VALUE, row.reason ?: "discussion", row.pageId)
            else -> return null
        }
        return DetailPage.Content(read, null, summary(row))
    }

    private fun readWindow(root: RootName, id: DiscussionId, after: CommentId?, limit: Int, indexed: DiscussionRow?): DetailPage {
        repeat(2) {
            when (val before = listing(root, id)) {
                EntryListing.Absent -> return DetailPage.Absent
                is EntryListing.Failed -> throw DiscussionReadFailed(root, before.cause)
                is EntryListing.Symlinked -> return metadataUnreadable(
                    id, UnreadableReason.SYMLINK, before.entry, indexed, indexed?.commentCount ?: 0,
                )
                is EntryListing.TooMany -> return metadataUnreadable(
                    id, UnreadableReason.TOO_MANY_COMMENTS, "${id.value}/", indexed, before.count,
                )
                is EntryListing.Present -> readPresentWindow(root, id, after, limit, indexed, before)?.let { return it }
            }
        }
        throw DiscussionReadFailed(root, "discussion entries changed during detail read")
    }

    private fun metadataUnreadable(
        id: DiscussionId,
        reason: UnreadableReason,
        entry: String,
        indexed: DiscussionRow?,
        commentCount: Int,
    ): DetailPage.Content {
        val read = DiscussionRead.Unreadable(reason, entry, indexed?.pageId)
        val projected = fileSummary(id, read, commentCount, null).copy(pagePath = indexed?.pagePath)
        return DetailPage.Content(read, null, projected)
    }

    private fun readPresentWindow(
        root: RootName,
        id: DiscussionId,
        after: CommentId?,
        limit: Int,
        indexed: DiscussionRow?,
        before: EntryListing.Present,
    ): DetailPage.Content? {
        val names = before.comments.filter { after == null || it.id.value > after.value }
        val window = names.take(limit)
        val selected = setOf<EntryName>(EntryName.Marker) + window
        val streamed = if (indexed?.state == "ok") {
            val entries = store.read(root, id, selected)
            if (!hasSelected(entries, selected, before.markerPresent)) {
                null to null
            } else {
                DiscussionAssembly.assemble(id, entries, retain = { it in window }) to null
            }
        } else {
            streamEntries(root, id, before, window)
        }
        val raw = streamed.first
        if (before != listing(root, id) || raw == null) return null
        if (raw is DiscussionRead.Failed) throw DiscussionReadFailed(root, raw.cause)
        val next = names.getOrNull(limit)?.let { window.lastOrNull()?.id }
        val selectedLatest = (raw as? DiscussionRead.Ok)?.files?.comments?.flatMap { stored ->
            val comment = stored.value
            listOfNotNull(
                comment.created.toEpochMilliseconds(), comment.editedAt?.toEpochMilliseconds(),
                comment.retraction?.at?.toEpochMilliseconds(),
            )
        }?.maxOrNull()
        val latest = listOfNotNull(streamed.second, indexed?.updated, selectedLatest).maxOrNull()
        val projected = fileSummary(id, raw, before.commentCount, latest).let { fresh ->
            if (raw is DiscussionRead.Unreadable) {
                fresh.copy(pageId = fresh.pageId ?: indexed?.pageId, pagePath = indexed?.pagePath)
            } else {
                fresh
            }
        }
        return DetailPage.Content(raw, next, projected)
    }

    private fun listing(root: RootName, id: DiscussionId): EntryListing = store.listEntries(root, id)

    private fun hasSelected(read: EntriesRead, selected: Set<EntryName>, markerPresent: Boolean): Boolean =
        read is EntriesRead.Present && read.entries.mapTo(mutableSetOf()) { it.name } ==
        selected.filterTo(mutableSetOf()) { it != EntryName.Marker || markerPresent }

    private fun streamEntries(
        root: RootName,
        id: DiscussionId,
        listing: EntryListing.Present,
        window: List<EntryName.Comment>,
    ): Pair<DiscussionRead?, Long?> {
        val markerRead = store.readKnownEntry(root, id, EntryName.Marker)
        if (!hasSelected(markerRead, setOf(EntryName.Marker), listing.markerPresent)) return null to null
        val chosen = window.toSet()
        var latest: Long? = null
        val accumulator = DiscussionAssembly.Accumulator(
            id,
            retain = { it in chosen },
            onComment = { stored ->
                val comment = stored.value
                latest = listOfNotNull(
                    latest, comment.created.toEpochMilliseconds(), comment.editedAt?.toEpochMilliseconds(),
                    comment.retraction?.at?.toEpochMilliseconds(),
                ).maxOrNull()
            },
        )
        (markerRead as EntriesRead.Present).entries.forEach(accumulator::accept)
        for (name in listing.comments) {
            val read = store.readKnownEntry(root, id, name)
            if (!hasSelected(read, setOf(name), false)) return null to null
            (read as EntriesRead.Present).entries.forEach(accumulator::accept)
        }
        return accumulator.finish() to latest
    }

    private fun fileSummary(id: DiscussionId, read: DiscussionRead, count: Int, latest: Long?): DiscussionSummary = when (read) {
        is DiscussionRead.Ok -> {
            val marker = read.files.marker.value
            val updated = listOfNotNull(
                marker.created.toEpochMilliseconds(), marker.statusChange?.at?.toEpochMilliseconds(),
                marker.reattachment?.at?.toEpochMilliseconds(), latest,
            ).maxOrNull()
            DiscussionSummary(
                id, "ok", null, marker.page.pageId, marker.page.path, marker.status.wire,
                if (marker.anchor is Anchor.Page) "page" else "quote", read.files.marker.version.token,
                IdentityDigest.of(marker.startedBy.actor.subject), marker.created.toEpochMilliseconds(), updated, count,
                marker.startedBy.kind.wire, RowDerivation.clipUtf8(marker.startedBy.actor.label),
                (marker.reattachment?.anchor ?: marker.anchor).let {
                    (it as? Anchor.Quote)?.capture?.quote?.let(RowDerivation::clipUtf8)
                },
            )
        }
        is DiscussionRead.Incomplete -> DiscussionSummary(
            id, "incomplete", null, null, null, null, null, null, null,
            null, null, count,
        )
        is DiscussionRead.Unreadable -> DiscussionSummary(
            id, "unreadable", "${read.reason.wire}:${read.entry}", read.pageId,
            null, null, null, null, null, null, null, count,
        )
        else -> throw IllegalStateException("detail summary requires a present discussion")
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

    private fun rootFiles(
        root: RootName,
        after: DiscussionId?,
        limit: Int,
        accepts: (DiscussionSummary) -> Boolean,
    ): PagedDiscussionSummaries {
        val cap = 4 * limit + 1
        val selected = PriorityQueue<DiscussionId>(cap, compareByDescending { it.value })
        var count = 0
        visit(root) { id, _ ->
            if (after != null && id.value <= after.value) return@visit
            count++
            if (selected.size < cap) {
                selected += id
            } else if (id.value < selected.peek().value) {
                selected.remove()
                selected += id
            }
        }
        val ids = selected.toList().sortedBy { it.value }
        val output = ArrayList<DiscussionSummary>(limit)
        var inspected = 0
        while (inspected < minOf(4 * limit, ids.size) && output.size < limit) {
            val summary = fullSummary(root, ids[inspected])
            inspected++
            if (summary != null && accepts(summary)) output += summary
        }
        val next = if (inspected < count) ids[inspected - 1] else null
        return PagedDiscussionSummaries(output, next)
    }

    private fun rootRows(
        root: RootName,
        after: DiscussionId?,
        limit: Int,
        accepts: (DiscussionSummary) -> Boolean,
    ): PagedDiscussionSummaries? {
        val output = ArrayList<DiscussionSummary>(limit)
        var cursor = after
        var inspected = 0
        val budget = 4 * limit
        while (inspected < budget && output.size < limit) {
            val batchLimit = minOf(limit - output.size, budget - inspected)
            val batch = queryRows(root) { rows.rowsAfter(root, cursor, batchLimit) } ?: return null
            if (batch.isEmpty()) return PagedDiscussionSummaries(output, null)
            for (row in batch) {
                inspected++
                cursor = row.id
                val candidate = summary(row)
                if (accepts(candidate)) output += candidate
            }
            if (batch.size < batchLimit) return PagedDiscussionSummaries(output, null)
        }
        val next = if (cursor != null && (queryRows(root) { rows.ids(root, cursor, 1) } ?: return null).isNotEmpty()) {
            cursor
        } else {
            null
        }
        return PagedDiscussionSummaries(output, next)
    }

    private fun <T> queryRows(root: RootName, query: () -> T): T? = try {
        query()
    } catch (failure: RootUnavailable) {
        throw failure
    } catch (failure: DiscussionReadFailed) {
        throw failure
    } catch (failure: AbsenceUnverified) {
        throw failure
    } catch (failure: InterruptedException) {
        Thread.currentThread().interrupt()
        throw failure
    } catch (failure: Exception) {
        sync.enter(root, failure.message ?: "discussion root rows query failed")
        null
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
        starterKind = row.starterKind,
        starterLabel = row.starterLabel,
        quotePreview = row.quotePreview,
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
        starterKind = starterKind,
        starterLabel = starterLabel,
        quotePreview = quotePreview,
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
        starterKind = starterKind,
        starterLabel = starterLabel,
        quotePreview = quotePreview,
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
