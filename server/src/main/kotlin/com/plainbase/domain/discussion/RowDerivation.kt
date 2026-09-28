package com.plainbase.domain.discussion

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import kotlin.time.Instant

data class DerivedDiscussionRow(val update: RowUpdate, val commentCount: Int)

object RowDerivation {
    private const val MAX_PAGE_PATH_BYTES = 4_096

    fun derive(id: DiscussionId, read: EntriesRead): DerivedDiscussionRow {
        val commentCount = when (read) {
            is EntriesRead.Present -> read.commentCount
            is EntriesRead.TooMany -> read.count
            else -> 0
        }
        val comments = CommentFold()
        val assembled = DiscussionAssembly.assemble(id, read, retain = { false }, onComment = comments::accept)
        val update = when (assembled) {
            DiscussionRead.Absent -> RowUpdate.Delete
            is DiscussionRead.Failed -> RowUpdate.Unknown(assembled.cause)
            is DiscussionRead.Incomplete -> data(
                state = "incomplete",
                commentCount = assembled.commentNames.size,
            )
            is DiscussionRead.Unreadable -> data(
                state = "unreadable",
                reason = "${assembled.reason.wire}:${assembled.entry}",
                pageId = assembled.pageId,
                commentCount = commentCount,
            )
            is DiscussionRead.Ok -> deriveOk(assembled.files, comments, commentCount)
        }
        return DerivedDiscussionRow(update, commentCount)
    }

    private fun deriveOk(
        files: DiscussionFiles,
        comments: CommentFold,
        commentCount: Int,
    ): RowUpdate.Upsert {
        val marker = files.marker.value
        val updated = buildList {
            add(marker.created)
            marker.statusChange?.at?.let(::add)
            marker.reattachment?.at?.let(::add)
            comments.updated?.let(::add)
        }.maxOfOrNull(Instant::toEpochMilliseconds)
        val pagePath = marker.page.path.takeIf { it.value.encodeToByteArray().size <= MAX_PAGE_PATH_BYTES }
        val entries = buildList {
            add(entry(files.marker.name, files.marker.version, IdentityDigest.of(marker.startedBy.actor.subject)))
            addAll(comments.entries)
        }
        return RowUpdate.Upsert(
            DiscussionRowData(
                state = "ok",
                pageId = marker.page.pageId,
                pagePath = pagePath,
                status = marker.status.wire,
                anchorKind = if (marker.anchor is Anchor.Page) "page" else "quote",
                anchorHash = files.marker.version.token,
                starterKey = IdentityDigest.of(marker.startedBy.actor.subject),
                created = marker.created.toEpochMilliseconds(),
                updated = updated,
                commentCount = commentCount,
            ),
            entries,
        )
    }

    private fun entry(name: EntryName, version: EntryVersion, authorKey: String): EntryRowData {
        val digest = version.token.removePrefix("sha256:").substringBefore(':')
        return EntryRowData(name, digest, version.token, authorKey)
    }

    private fun data(
        state: String,
        reason: String? = null,
        pageId: PageId? = null,
        pagePath: TreePath? = null,
        status: String? = null,
        anchorKind: String? = null,
        anchorHash: String? = null,
        starterKey: String? = null,
        created: Long? = null,
        updated: Long? = null,
        commentCount: Int = 0,
    ) = RowUpdate.Upsert(
        DiscussionRowData(state, reason, pageId, pagePath, status, anchorKind, anchorHash, starterKey, created, updated, commentCount),
        emptyList(),
    )

    private class CommentFold {
        val entries = ArrayList<EntryRowData>()
        var updated: Instant? = null
            private set

        fun accept(stored: Stored<CommentRecord>) {
            val comment = stored.value
            listOfNotNull(comment.created, comment.editedAt, comment.retraction?.at).forEach { timestamp ->
                if (updated == null || timestamp > requireNotNull(updated)) updated = timestamp
            }
            entries += entry(stored.name, stored.version, IdentityDigest.of(comment.author.actor.subject))
        }
    }
}
