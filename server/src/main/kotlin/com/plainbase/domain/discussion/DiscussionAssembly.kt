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
    fun assemble(id: DiscussionId, read: EntriesRead): DiscussionRead = when (read) {
        EntriesRead.Absent -> DiscussionRead.Absent
        is EntriesRead.Failed -> DiscussionRead.Failed(read.cause)
        is EntriesRead.Symlinked -> DiscussionRead.Unreadable(UnreadableReason.SYMLINK, read.entry, null)
        is EntriesRead.TooMany -> tooMany(id, read)
        is EntriesRead.Present -> present(id, read.entries)
    }

    private fun tooMany(id: DiscussionId, read: EntriesRead.TooMany): DiscussionRead {
        val pageId = read.marker?.take()?.let { markerBytes ->
            val decoded = DiscussionCodec.decodeDiscussion(markerBytes)
            val record = (decoded as? Decoded.Ok<*>)?.value as? DiscussionRecord
            record?.page?.pageId ?: DiscussionCodec.peekPageId(markerBytes)
        }
        return DiscussionRead.Unreadable(UnreadableReason.TOO_MANY_COMMENTS, "${id.value}/", pageId)
    }

    private fun present(id: DiscussionId, rawEntries: List<RawEntry>): DiscussionRead {
        val ordered = rawEntries.sortedBy { it.name.fileName }
        if (ordered.none { it.name == EntryName.Marker }) {
            ordered.forEach { it.take() }
            val names = ordered.mapNotNull { entry -> (entry.name as? EntryName.Comment)?.fileName }
            return DiscussionRead.Incomplete(names)
        }

        val records = mutableMapOf<EntryName, Any>()
        val versions = ordered.associate { it.name to it.version }
        var pageId: PageId? = null
        var firstUnreadable: DiscussionRead.Unreadable? = null
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
                    records[entry.name] = requireNotNull(decoded.value)
                    if (entry.name == EntryName.Marker) pageId = (decoded.value as DiscussionRecord).page.pageId
                }
            }
        }
        firstUnreadable?.let { failure ->
            return failure.copy(pageId = pageId ?: failure.pageId)
        }

        val markerName = EntryName.Marker
        val markerRecord = records[markerName] as? DiscussionRecord
            ?: return DiscussionRead.Unreadable(UnreadableReason.BAD_VALUE, markerName.fileName, pageId)
        val markerStored = Stored(markerName, versions.getValue(markerName), markerRecord)
        for (entry in ordered) {
            when (val name = entry.name) {
                EntryName.Marker -> if (markerRecord.id != id) {
                    return DiscussionRead.Unreadable(UnreadableReason.BAD_VALUE, name.fileName, markerRecord.page.pageId)
                }
                is EntryName.Comment -> {
                    val comment = records[name] as CommentRecord
                    if (comment.id != name.id || comment.discussionId != id) {
                        return DiscussionRead.Unreadable(UnreadableReason.BAD_VALUE, name.fileName, markerRecord.page.pageId)
                    }
                }
            }
        }
        val comments = ordered.mapNotNull { entry ->
            val name = entry.name as? EntryName.Comment ?: return@mapNotNull null
            val comment = records[name] as CommentRecord
            Stored(name, versions.getValue(name), comment)
        }.sortedBy { it.name.fileName }
        return DiscussionRead.Ok(DiscussionFiles(markerStored, comments))
    }
}
