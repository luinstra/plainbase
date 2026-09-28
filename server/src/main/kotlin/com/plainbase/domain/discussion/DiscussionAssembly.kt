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
        val ordered = rawEntries.sortedBy { it.name.fileName }
        val versions = ordered.associate { it.name to it.version }
        val markerPresent = ordered.any { it.name == EntryName.Marker }
        val comments = mutableListOf<Stored<CommentRecord>>()
        var pageId: PageId? = null
        var firstUnreadable: DiscussionRead.Unreadable? = null
        var firstMismatch: String? = null
        var markerRecord: DiscussionRecord? = null
        for (entry in ordered) {
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
                    if (firstUnreadable == null) {
                        firstUnreadable = DiscussionRead.Unreadable(decoded.reason, entry.name.fileName, pageId, decoded.detail)
                    }
                }
                is Decoded.Ok<*> -> {
                    when (val name = entry.name) {
                        EntryName.Marker -> {
                            markerRecord = decoded.value as DiscussionRecord
                            pageId = markerRecord.page.pageId
                            if (markerRecord.id != id && firstMismatch == null) firstMismatch = name.fileName
                        }
                        is EntryName.Comment -> {
                            val comment = decoded.value as CommentRecord
                            val stored = Stored(name, entry.version, comment)
                            onComment(stored)
                            if (retain(name)) comments += stored
                            if ((comment.id != name.id || comment.discussionId != id) && firstMismatch == null) {
                                firstMismatch = name.fileName
                            }
                        }
                    }
                }
            }
        }
        if (!markerPresent) {
            val names = ordered.mapNotNull { entry -> (entry.name as? EntryName.Comment)?.fileName }
            return DiscussionRead.Incomplete(names)
        }
        firstUnreadable?.let { failure ->
            return failure.copy(pageId = pageId ?: failure.pageId)
        }
        if (firstMismatch != null) {
            return DiscussionRead.Unreadable(UnreadableReason.BAD_VALUE, firstMismatch, pageId)
        }
        val markerName = EntryName.Marker
        val marker = markerRecord
            ?: return DiscussionRead.Unreadable(UnreadableReason.BAD_VALUE, markerName.fileName, pageId)
        val markerStored = Stored(markerName, versions.getValue(markerName), marker)
        return DiscussionRead.Ok(DiscussionFiles(markerStored, comments.sortedBy { it.name.fileName }))
    }
}
