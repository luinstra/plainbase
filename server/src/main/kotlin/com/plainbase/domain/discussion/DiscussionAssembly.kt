package com.plainbase.domain.discussion

import com.plainbase.domain.page.PageId

sealed interface DiscussionRead {
    data object Absent : DiscussionRead
    data class Incomplete(val commentNames: List<String>) : DiscussionRead
    data class Unreadable(
        val reason: UnreadableReason,
        val entry: String,
        val pageId: PageId?,
        val detail: String? = null,
    ) : DiscussionRead
    data class Failed(val cause: String) : DiscussionRead
    data class Ok(val files: DiscussionFiles) : DiscussionRead
}

data class DiscussionFiles(val marker: Stored<DiscussionRecord>, val comments: List<Stored<CommentRecord>>)

data class Stored<T>(val name: EntryName, val version: EntryVersion, val value: T)

object DiscussionAssembly {
    fun assemble(
        id: DiscussionId,
        read: EntriesRead,
        retain: (EntryName.Comment) -> Boolean = { true },
        onComment: (Stored<CommentRecord>) -> Unit = {},
    ): DiscussionRead = when (read) {
        EntriesRead.Absent -> DiscussionRead.Absent
        is EntriesRead.Failed -> DiscussionRead.Failed(read.cause)
        is EntriesRead.Symlinked -> DiscussionRead.Unreadable(UnreadableReason.SYMLINK, read.entry, null)
        is EntriesRead.TooMany -> tooMany(id, read)
        is EntriesRead.Present -> present(id, read.entries, retain, onComment)
    }

    private fun tooMany(id: DiscussionId, read: EntriesRead.TooMany): DiscussionRead {
        val pageId = read.marker?.take()?.let { markerBytes ->
            val decoded = DiscussionCodec.decodeDiscussion(markerBytes)
            val record = (decoded as? Decoded.Ok<*>)?.value as? DiscussionRecord
            record?.page?.pageId ?: DiscussionCodec.peekPageId(markerBytes)
        }
        return DiscussionRead.Unreadable(UnreadableReason.TOO_MANY_COMMENTS, "${id.value}/", pageId)
    }

    private fun present(
        id: DiscussionId,
        rawEntries: List<RawEntry>,
        retain: (EntryName.Comment) -> Boolean,
        onComment: (Stored<CommentRecord>) -> Unit,
    ): DiscussionRead {
        val accumulator = Accumulator(id, retain, onComment)
        rawEntries.sortedBy { it.name.fileName }.forEach(accumulator::accept)
        return accumulator.finish()
    }

    class Accumulator(
        private val id: DiscussionId,
        private val retain: (EntryName.Comment) -> Boolean = { true },
        private val onComment: (Stored<CommentRecord>) -> Unit = {},
    ) {
        private val comments = mutableListOf<Stored<CommentRecord>>()
        private val commentNames = mutableListOf<String>()
        private var pageId: PageId? = null
        private var firstUnreadable: DiscussionRead.Unreadable? = null
        private var firstMismatch: String? = null
        private var marker: Stored<DiscussionRecord>? = null
        private var markerPresent = false

        fun accept(entry: RawEntry) {
            if (entry.name == EntryName.Marker) markerPresent = true
            if (entry.name is EntryName.Comment) commentNames += entry.name.fileName
            val bytes = entry.take()
            val decoded: Decoded<*> = when {
                !entry.complete -> Decoded.Unreadable(
                    UnreadableReason.BAD_VALUE,
                    entry.failureDetail ?: "entry exceeds its size cap",
                )
                entry.name == EntryName.Marker -> DiscussionCodec.decodeDiscussion(bytes)
                else -> DiscussionCodec.decodeComment(bytes)
            }
            when (decoded) {
                is Decoded.Unreadable -> {
                    if (entry.name == EntryName.Marker) pageId = DiscussionCodec.peekPageId(bytes)
                    if (firstUnreadable == null || entry.name.fileName < checkNotNull(firstUnreadable).entry) {
                        firstUnreadable = DiscussionRead.Unreadable(decoded.reason, entry.name.fileName, pageId, decoded.detail)
                    }
                }
                is Decoded.Ok<*> -> {
                    when (val name = entry.name) {
                        EntryName.Marker -> {
                            val record = decoded.value as DiscussionRecord
                            marker = Stored(name, entry.version, record)
                            pageId = record.page.pageId
                            if (record.id != id) mismatch(name.fileName)
                        }
                        is EntryName.Comment -> {
                            val comment = decoded.value as CommentRecord
                            val stored = Stored(name, entry.version, comment)
                            onComment(stored)
                            if (retain(name)) comments += stored
                            if (comment.id != name.id || comment.discussionId != id) mismatch(name.fileName)
                        }
                    }
                }
            }
        }

        private fun mismatch(name: String) {
            if (firstMismatch == null || name < checkNotNull(firstMismatch)) firstMismatch = name
        }

        fun finish(): DiscussionRead {
            if (!markerPresent) return DiscussionRead.Incomplete(commentNames.sorted())
            firstUnreadable?.let { failure -> return failure.copy(pageId = pageId ?: failure.pageId) }
            firstMismatch?.let { mismatch ->
                return DiscussionRead.Unreadable(UnreadableReason.BAD_VALUE, mismatch, pageId)
            }
            val markerStored = marker
                ?: return DiscussionRead.Unreadable(UnreadableReason.BAD_VALUE, EntryName.Marker.fileName, pageId)
            return DiscussionRead.Ok(DiscussionFiles(markerStored, comments.sortedBy { it.name.fileName }))
        }
    }
}
