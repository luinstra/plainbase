package com.plainbase.domain.service

import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.discussion.Anchor
import com.plainbase.domain.discussion.Author
import com.plainbase.domain.discussion.CommentId
import com.plainbase.domain.discussion.CommentRecord
import com.plainbase.domain.discussion.Decoded
import com.plainbase.domain.discussion.DiscussionAssembly
import com.plainbase.domain.discussion.DiscussionCodec
import com.plainbase.domain.discussion.DiscussionFiles
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionIndex
import com.plainbase.domain.discussion.DiscussionPageSource
import com.plainbase.domain.discussion.DiscussionRead
import com.plainbase.domain.discussion.DiscussionRecord
import com.plainbase.domain.discussion.DiscussionStatus
import com.plainbase.domain.discussion.DiscussionStore
import com.plainbase.domain.discussion.EntriesRead
import com.plainbase.domain.discussion.EntryName
import com.plainbase.domain.discussion.EntryPath
import com.plainbase.domain.discussion.EntryPut
import com.plainbase.domain.discussion.FrontmatterExtras
import com.plainbase.domain.discussion.MAX_COMMENT_BYTES
import com.plainbase.domain.discussion.MAX_COMMENT_ENTRIES
import com.plainbase.domain.discussion.MAX_MARKER_BYTES
import com.plainbase.domain.discussion.PageRef
import com.plainbase.domain.discussion.RawEntry
import com.plainbase.domain.discussion.Reattachment
import com.plainbase.domain.discussion.Retraction
import com.plainbase.domain.discussion.StatusChange
import com.plainbase.domain.discussion.StoreWrite
import com.plainbase.domain.discussion.Tombstone
import com.plainbase.domain.history.CommitIdentity
import com.plainbase.domain.history.CommitOutcome
import com.plainbase.domain.history.HistoryChange
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.principal.SubjectKey
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.UnavailableCause
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock
import kotlin.time.Instant

sealed interface DiscussionCommand {
    val root: RootName
    val actor: Author

    data class Start(
        override val root: RootName,
        override val actor: Author,
        val page: PageRef,
        val anchor: Anchor,
        val body: String,
    ) : DiscussionCommand

    data class AddComment(
        override val root: RootName,
        override val actor: Author,
        val id: DiscussionId,
        val body: String,
    ) : DiscussionCommand

    data class EditComment(
        override val root: RootName,
        override val actor: Author,
        val id: DiscussionId,
        val commentId: CommentId,
        val body: String,
        val reliedOn: ReliedOn = ReliedOn(),
    ) : DiscussionCommand

    data class RetractComment(
        override val root: RootName,
        override val actor: Author,
        val id: DiscussionId,
        val commentId: CommentId,
        val reliedOn: ReliedOn = ReliedOn(),
    ) : DiscussionCommand

    data class SetStatus(
        override val root: RootName,
        override val actor: Author,
        val id: DiscussionId,
        val target: DiscussionStatus,
        val reliedOn: ReliedOn = ReliedOn(),
    ) : DiscussionCommand

    data class Reattach(
        override val root: RootName,
        override val actor: Author,
        val id: DiscussionId,
        val anchor: Anchor.Quote,
        val reliedOn: ReliedOn = ReliedOn(),
    ) : DiscussionCommand

    data class PurgeComment(
        override val root: RootName,
        override val actor: Author,
        val id: DiscussionId,
        val commentId: CommentId,
    ) : DiscussionCommand
}

data class ReliedOn(val author: SubjectKey? = null, val starter: SubjectKey? = null)

data class DiscussionRefusal(val status: Int, val code: String)

sealed interface DiscussionWriteOutcome {
    data class Done(val id: DiscussionId, val commentId: CommentId?, val commit: String?) : DiscussionWriteOutcome
    data class Refused(val refusal: DiscussionRefusal) : DiscussionWriteOutcome
}

/** Applies discussion commands to authoritative files, commits accepted changes, then republishes disk state. */
class DiscussionWriter(
    private val monitor: ContentWriteMonitor,
    private val store: DiscussionStore,
    private val pages: DiscussionPageSource,
    private val histories: (RootName) -> HistoryProvider,
    private val index: DiscussionIndex,
    private val ids: DiscussionIdProvider,
    private val clock: Clock,
    private val hasher: (ByteArray) -> String = CitationFactory()::contentHash,
) {
    fun write(command: DiscussionCommand): DiscussionWriteOutcome = monitor.withLock { writeLocked(command) }

    private fun writeLocked(command: DiscussionCommand): DiscussionWriteOutcome = when (command) {
        is DiscussionCommand.Start -> start(command)
        is DiscussionCommand.AddComment -> addComment(command)
        is DiscussionCommand.EditComment -> editComment(command)
        is DiscussionCommand.RetractComment -> retractComment(command)
        is DiscussionCommand.SetStatus -> setStatus(command)
        is DiscussionCommand.Reattach -> reattach(command)
        is DiscussionCommand.PurgeComment -> purgeComment(command)
    }

    private fun start(command: DiscussionCommand.Start): DiscussionWriteOutcome {
        val id = ids.nextDiscussion()
        val commentId = ids.nextComment()
        val commentName = EntryName.Comment(commentId)
        val now = now()
        val pageRead = pages.read(command.root, command.page)
        val pageBytes = (pageRead as? ContentRead.Bytes)?.bytes ?: return pageFailure(command.root, pageRead)
        if (hasher(pageBytes) != command.anchor.contentHash) return refused(409, "page_changed")
        if (index.pageDiscussionCount(command.root, command.page.pageId) >= MAX_PAGE_DISCUSSIONS) {
            return refused(409, "page_discussion_limit")
        }
        val comment = CommentRecord(commentId, id, command.actor, now, null, null, command.body, FrontmatterExtras.NONE)
        val marker = DiscussionRecord(
            id,
            command.page,
            DiscussionStatus.OPEN,
            now,
            command.actor,
            null,
            command.anchor,
            null,
            FrontmatterExtras.NONE,
        )
        val commentBytes = encodeCommentRecord(comment)
        val markerBytes = encodeDiscussionRecord(marker)
        if (commentBytes.size > MAX_COMMENT_BYTES || markerBytes.size > MAX_MARKER_BYTES) {
            return refused(422, "discussion_too_large")
        }
        val mutation = Mutation(
            id = id,
            commentId = commentId,
            actor = command.actor,
            action = "start",
            markerChanged = true,
            changes = listOf(
                HistoryChange.Put(entryPath(id, commentName), commentBytes),
                HistoryChange.Put(entryPath(id, EntryName.Marker), markerBytes),
            ),
            undo = UndoPlan.Create(listOf(EntryName.Marker, commentName)),
        )
        return persist(
            command.root,
            mutation,
            store.createFiles(
                command.root,
                id,
                listOf(EntryPut(commentName, commentBytes), EntryPut(EntryName.Marker, markerBytes)),
            ),
        )
    }

    private fun addComment(command: DiscussionCommand.AddComment): DiscussionWriteOutcome {
        val id = command.id
        val loaded = load(command.root, id, setOf(EntryName.Marker))
        val files = loaded.filesOrNull() ?: return checkNotNull(loaded.refusal)
        val marker = files.marker.value
        if (marker.status == DiscussionStatus.RESOLVED) return refused(409, "discussion_resolved")
        if (loaded.commentCount >= MAX_COMMENT_ENTRIES) return refused(409, "discussion_full")
        val commentId = ids.nextComment()
        val name = EntryName.Comment(commentId)
        val bytes = encodeCommentRecord(
            CommentRecord(commentId, id, command.actor, now(), null, null, command.body, FrontmatterExtras.NONE),
        )
        if (bytes.size > MAX_COMMENT_BYTES) return refused(422, "discussion_too_large")
        val mutation = Mutation(
            id,
            commentId,
            command.actor,
            "comment",
            markerChanged = false,
            changes = listOf(HistoryChange.Put(entryPath(id, name), bytes)),
            undo = UndoPlan.Create(listOf(name)),
        )
        return persist(command.root, mutation, store.createFiles(command.root, id, listOf(EntryPut(name, bytes))))
    }

    private fun editComment(command: DiscussionCommand.EditComment): DiscussionWriteOutcome {
        val id = command.id
        val name = EntryName.Comment(command.commentId)
        val loaded = load(command.root, id, setOf(EntryName.Marker, name), undoName = name)
        val files = loaded.filesOrNull() ?: return checkNotNull(loaded.refusal)
        val marker = files.marker.value
        val comment = files.comments.firstOrNull { it.name == name }?.value ?: return refused(404, "comment_not_found")
        checkRelied(command.reliedOn, marker, comment, checkAuthor = true)?.let { return it }
        if (comment.retraction != null) return refused(409, "comment_retracted")
        val priorBytes = loaded.priorBytes ?: error("the narrowed read did not retain the target comment's bytes")
        val target = files.comments.first { it.name == name }
        val bytes = encodeCommentRecord(comment.copy(editedAt = now(), body = command.body))
        if (bytes.size > MAX_COMMENT_BYTES) return refused(422, "discussion_too_large")
        val mutation = Mutation(
            id,
            command.commentId,
            command.actor,
            "edit",
            markerChanged = false,
            changes = listOf(HistoryChange.Put(entryPath(id, name), bytes)),
            undo = UndoPlan.Replace(mapOf(name to priorBytes)),
        )
        return persist(command.root, mutation, store.replace(command.root, EntryPath(id, name), target.version, bytes))
    }

    private fun retractComment(command: DiscussionCommand.RetractComment): DiscussionWriteOutcome {
        val id = command.id
        val name = EntryName.Comment(command.commentId)
        val loaded = load(command.root, id, setOf(EntryName.Marker, name), undoName = name)
        val files = loaded.filesOrNull() ?: return checkNotNull(loaded.refusal)
        val marker = files.marker.value
        val comment = files.comments.firstOrNull { it.name == name }?.value ?: return refused(404, "comment_not_found")
        checkRelied(command.reliedOn, marker, comment, checkAuthor = true)?.let { return it }
        if (comment.retraction != null) return refused(409, "comment_retracted")
        val priorBytes = loaded.priorBytes ?: error("the narrowed read did not retain the target comment's bytes")
        val target = files.comments.first { it.name == name }
        val actor = command.actor.actor
        val bytes = encodeCommentRecord(
            comment.copy(retraction = Retraction(actor, now()), body = "retracted by ${actor.label}\n"),
        )
        if (bytes.size > MAX_COMMENT_BYTES) return refused(422, "discussion_too_large")
        val mutation = Mutation(
            id,
            command.commentId,
            command.actor,
            "retract",
            markerChanged = false,
            changes = listOf(HistoryChange.Put(entryPath(id, name), bytes)),
            undo = UndoPlan.Replace(mapOf(name to priorBytes)),
        )
        return persist(command.root, mutation, store.replace(command.root, EntryPath(id, name), target.version, bytes))
    }

    private fun setStatus(command: DiscussionCommand.SetStatus): DiscussionWriteOutcome {
        val id = command.id
        val loaded = load(command.root, id, setOf(EntryName.Marker), undoName = EntryName.Marker)
        val files = loaded.filesOrNull() ?: return checkNotNull(loaded.refusal)
        val marker = files.marker
        checkRelied(command.reliedOn, marker.value, comment = null, checkAuthor = false)?.let { return it }
        if (command.target == DiscussionStatus.RESOLVED && marker.value.status == DiscussionStatus.RESOLVED) {
            return refused(409, "already_resolved")
        }
        if (command.target == DiscussionStatus.OPEN && marker.value.status == DiscussionStatus.OPEN) {
            return refused(409, "already_open")
        }
        val priorBytes = loaded.priorBytes ?: error("the narrowed read did not retain the target marker's bytes")
        val bytes = encodeDiscussionRecord(
            marker.value.copy(status = command.target, statusChange = StatusChange(command.actor.actor, now())),
        )
        if (bytes.size > MAX_MARKER_BYTES) return refused(422, "discussion_too_large")
        val mutation = Mutation(
            id,
            null,
            command.actor,
            if (command.target == DiscussionStatus.RESOLVED) "resolve" else "reopen",
            markerChanged = true,
            changes = listOf(HistoryChange.Put(entryPath(id, EntryName.Marker), bytes)),
            undo = UndoPlan.Replace(mapOf(EntryName.Marker to priorBytes)),
        )
        return persist(
            command.root,
            mutation,
            store.replace(command.root, EntryPath(id, EntryName.Marker), marker.version, bytes),
        )
    }

    private fun reattach(command: DiscussionCommand.Reattach): DiscussionWriteOutcome {
        val id = command.id
        val loaded = load(command.root, id, setOf(EntryName.Marker), undoName = EntryName.Marker)
        val files = loaded.filesOrNull() ?: return checkNotNull(loaded.refusal)
        val marker = files.marker
        checkRelied(command.reliedOn, marker.value, comment = null, checkAuthor = false)?.let { return it }
        if (marker.value.status == DiscussionStatus.RESOLVED) return refused(409, "discussion_resolved")
        if (marker.value.anchor is Anchor.Page) return refused(422, "reattach_page_level")
        val pageRead = pages.read(command.root, marker.value.page)
        val pageBytes = (pageRead as? ContentRead.Bytes)?.bytes ?: return pageFailure(command.root, pageRead)
        if (hasher(pageBytes) != command.anchor.contentHash) return refused(409, "page_changed")
        val priorBytes = loaded.priorBytes ?: error("the narrowed read did not retain the target marker's bytes")
        val bytes = encodeDiscussionRecord(
            marker.value.copy(reattachment = Reattachment(command.actor.actor, now(), command.anchor)),
        )
        if (bytes.size > MAX_MARKER_BYTES) return refused(422, "discussion_too_large")
        val mutation = Mutation(
            id,
            null,
            command.actor,
            "reattach",
            markerChanged = true,
            changes = listOf(HistoryChange.Put(entryPath(id, EntryName.Marker), bytes)),
            undo = UndoPlan.Replace(mapOf(EntryName.Marker to priorBytes)),
        )
        return persist(
            command.root,
            mutation,
            store.replace(command.root, EntryPath(id, EntryName.Marker), marker.version, bytes),
        )
    }

    private fun purgeComment(command: DiscussionCommand.PurgeComment): DiscussionWriteOutcome {
        val id = command.id
        val name = EntryName.Comment(command.commentId)
        val read = store.read(command.root, id, only = setOf(EntryName.Marker, name))
        val markerAndTarget = when (read) {
            EntriesRead.Absent -> return refused(404, "discussion_not_found")
            is EntriesRead.Failed -> {
                logger.warn {
                    "discussion read failed for root ${command.root.value}, discussion ${id.value}: ${read.cause}"
                }
                return refused(503, "content_unreadable")
            }
            is EntriesRead.Symlinked -> return refused(409, "discussion_unreadable")
            is EntriesRead.TooMany -> return refused(409, "discussion_unreadable")
            is EntriesRead.Present -> read
        }
        val markerRaw = markerAndTarget.entries.firstOrNull { it.name == EntryName.Marker }
            ?: return refused(404, "discussion_not_found")
        markerRaw.take()
        val target = markerAndTarget.entries.firstOrNull { it.name == name } ?: return refused(404, "comment_not_found")
        target.take()
        val mutation = Mutation(
            id,
            command.commentId,
            command.actor,
            "purge",
            markerChanged = false,
            changes = listOf(HistoryChange.Delete(entryPath(id, name))),
            undo = UndoPlan.Purge(EntryPath(id, name)),
        )
        return persist(command.root, mutation, store.purge(command.root, EntryPath(id, name), target.version))
    }

    private fun load(
        root: RootName,
        id: DiscussionId,
        only: Set<EntryName>,
        undoName: EntryName? = null,
    ): LoadedRead {
        val read = store.read(root, id, only)
        if (read !is EntriesRead.Present) return loadedRead(root, id, DiscussionAssembly.assemble(id, read), 0, null)
        val undoEntry = undoName?.let { name -> read.entries.firstOrNull { it.name == name } }
        val priorBytes = undoEntry?.take()
        val adjusted = if (undoEntry == null || priorBytes == null) {
            read
        } else {
            EntriesRead.Present(
                read.entries.map { entry ->
                    if (entry === undoEntry) {
                        RawEntry(entry.name, priorBytes.copyOf(), entry.version, entry.complete, entry.failureDetail)
                    } else {
                        entry
                    }
                },
                read.commentCount,
            )
        }
        return loadedRead(root, id, DiscussionAssembly.assemble(id, adjusted), read.commentCount, priorBytes)
    }

    private fun loadedRead(root: RootName, id: DiscussionId, read: DiscussionRead, commentCount: Int, priorBytes: ByteArray?): LoadedRead =
        when (read) {
            DiscussionRead.Absent, is DiscussionRead.Incomplete -> LoadedRead.Refused(refused(404, "discussion_not_found"))
            is DiscussionRead.Unreadable -> LoadedRead.Refused(refused(409, "discussion_unreadable"))
            is DiscussionRead.Failed -> {
                logger.warn { "discussion read failed for root ${root.value}, discussion ${id.value}: ${read.cause}" }
                LoadedRead.Refused(refused(503, "content_unreadable"))
            }
            is DiscussionRead.Ok -> LoadedRead.Ready(read.files, commentCount, priorBytes)
        }

    private fun LoadedRead.filesOrNull(): DiscussionFiles? = (this as? LoadedRead.Ready)?.files

    private fun checkRelied(
        reliedOn: ReliedOn,
        marker: DiscussionRecord,
        comment: CommentRecord?,
        checkAuthor: Boolean,
    ): DiscussionWriteOutcome.Refused? {
        if (checkAuthor && reliedOn.author != null && reliedOn.author != comment?.author?.actor?.subject) {
            return refused(409, "discussion_changed")
        }
        if (reliedOn.starter != null && reliedOn.starter != marker.startedBy.actor.subject) {
            return refused(409, "discussion_changed")
        }
        return null
    }

    private fun pageFailure(root: RootName, read: ContentRead): DiscussionWriteOutcome = when (read) {
        ContentRead.ConfirmedAbsent -> refused(404, "page_not_found")
        ContentRead.AbsenceUnknown -> refused(503, "absence_unverified")
        ContentRead.RootDown -> throw RootUnavailable(root, UnavailableCause.VANISHED)
        is ContentRead.Bytes -> error("page bytes were read twice")
    }

    private fun persist(root: RootName, mutation: Mutation, write: StoreWrite): DiscussionWriteOutcome = when (write) {
        is StoreWrite.Written -> commit(root, mutation, write)
        StoreWrite.Exists -> throw IllegalStateException("discussion id already exists: ${mutation.id.value}")
        StoreWrite.Missing, is StoreWrite.Mismatch -> refused(409, "stale_discussion")
        is StoreWrite.Refused -> refused(422, "discussion_path_refused")
        is StoreWrite.Failed -> {
            val result = refused(503, "content_unreadable")
            if (write.residual) {
                logger.error { "store residual for root ${root.value}, discussion ${mutation.id.value}: ${write.cause}" }
                publishCurrent(root, mutation, result)
            } else {
                logger.warn {
                    "discussion store write failed for root ${root.value}, discussion ${mutation.id.value}: ${write.cause}"
                }
                result
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun commit(root: RootName, mutation: Mutation, stored: StoreWrite.Written): DiscussionWriteOutcome {
        val identity = CommitIdentity(
            mutation.actor.actor.label,
            syntheticEmail(mutation.actor.actor.subject.issuer, mutation.actor.actor.subject.id),
        )
        var historyProvider: HistoryProvider? = null
        var historyLookupFailure: Exception? = null
        try {
            historyProvider = histories(root)
            historyLookupFailure = null
        } catch (failure: Exception) {
            historyProvider = null
            historyLookupFailure = failure
        }
        val commitOutcome = if (historyLookupFailure != null) {
            CommitOutcome.NotCommitted(historyLookupFailure)
        } else {
            try {
                checkNotNull(historyProvider).commitChanges(
                    mutation.changes,
                    "discussion: ${mutation.action} ${mutation.id.value}",
                    identity,
                    identity,
                )
            } catch (failure: Exception) {
                CommitOutcome.Unknown(failure)
            }
        }
        val wasInterrupted = Thread.interrupted()
        try {
            val outcome = try {
                when (commitOutcome) {
                    is CommitOutcome.Committed -> {
                        stored.tombstone?.let { tombstone -> discardAfterCommit(root, mutation.id, tombstone) }
                        DiscussionWriteOutcome.Done(mutation.id, mutation.commentId, commitOutcome.sha)
                    }
                    is CommitOutcome.Unknown -> {
                        logger.warn(commitOutcome.cause) { "commit outcome for discussion ${mutation.id.value} is unknown" }
                        refused(503, "discussion_commit_uncertain")
                    }
                    is CommitOutcome.NotCommitted -> {
                        val failure = undo(root, mutation, stored)
                        if (failure != null) {
                            logger.error(failure) { "undo of ${mutation.id.value} failed; files left uncommitted" }
                        } else {
                            logger.warn(commitOutcome.cause) {
                                "discussion commit failed for root ${root.value}, discussion ${mutation.id.value}; changes were undone"
                            }
                        }
                        refused(503, "discussion_commit_failed")
                    }
                }
            } catch (failure: RootUnavailable) {
                index.publishFailed(root, failure)
                throw failure
            }
            val markerChanged = mutation.markerChanged || commitOutcome is CommitOutcome.NotCommitted
            return publishCurrent(root, mutation.copy(markerChanged = markerChanged), outcome)
        } finally {
            if (wasInterrupted) Thread.currentThread().interrupt()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun discardAfterCommit(root: RootName, id: DiscussionId, tombstone: Tombstone) {
        try {
            val result = store.discard(root, tombstone)
            if (result !is StoreWrite.Written) {
                logger.warn {
                    "purged discussion comment ${tombstone.entry.name.fileName} remains as a tombstone for ${id.value}: $result"
                }
            }
        } catch (failure: RootUnavailable) {
            throw failure
        } catch (failure: Exception) {
            logger.warn(failure) { "purged discussion comment remains as a tombstone for ${id.value}" }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun undo(root: RootName, mutation: Mutation, stored: StoreWrite.Written): Exception? = try {
        var failedWrite: StoreWrite? = null
        val success = when (val plan = mutation.undo) {
            is UndoPlan.Create -> plan.names.all { name ->
                val version = stored.versions[name] ?: return@all false
                val purge = store.purge(root, EntryPath(mutation.id, name), version)
                val tombstone = (purge as? StoreWrite.Written)?.tombstone ?: run {
                    failedWrite = purge
                    return@all false
                }
                val discarded = store.discard(root, tombstone)
                if (discarded !is StoreWrite.Written) failedWrite = discarded
                discarded is StoreWrite.Written
            }
            is UndoPlan.Replace -> plan.bytes.all { (name, bytes) ->
                val version = stored.versions[name] ?: return@all false
                val replaced = store.replace(root, EntryPath(mutation.id, name), version, bytes)
                if (replaced !is StoreWrite.Written) failedWrite = replaced
                replaced is StoreWrite.Written
            }
            is UndoPlan.Purge -> {
                val tombstone = stored.tombstone ?: return IllegalStateException("purge undo has no tombstone")
                val restored = store.restore(root, tombstone)
                if (restored !is StoreWrite.Written) failedWrite = restored
                restored is StoreWrite.Written
            }
        }
        if (success) null else IllegalStateException("store undo did not complete: $failedWrite")
    } catch (failure: RootUnavailable) {
        throw failure
    } catch (failure: Exception) {
        failure
    }

    @Suppress("TooGenericExceptionCaught")
    private fun publishCurrent(
        root: RootName,
        mutation: Mutation,
        result: DiscussionWriteOutcome,
    ): DiscussionWriteOutcome {
        var rootLoss: RootUnavailable? = null
        try {
            val read = DiscussionAssembly.assemble(mutation.id, store.read(root, mutation.id))
            if (read is DiscussionRead.Failed) {
                logger.warn { "discussion read failed for root ${root.value}, discussion ${mutation.id.value}: ${read.cause}" }
            }
            try {
                index.publish(root, mutation.id, read, mutation.markerChanged)
            } catch (failure: Exception) {
                index.publishFailed(root, failure)
            }
            return result
        } catch (failure: RootUnavailable) {
            rootLoss = failure
            throw failure
        } finally {
            rootLoss?.let { index.publishFailed(root, it) }
        }
    }

    private fun now(): Instant = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds())

    private fun encodeDiscussionRecord(record: DiscussionRecord): ByteArray {
        val bytes = DiscussionCodec.encodeDiscussion(record)
        if (bytes.size <= MAX_MARKER_BYTES) {
            val decoded = DiscussionCodec.decodeDiscussion(bytes)
            require(decoded is Decoded.Ok && decoded.value == record) {
                "encoded discussion record cannot be read back"
            }
        }
        return bytes
    }

    private fun encodeCommentRecord(record: CommentRecord): ByteArray {
        val bytes = DiscussionCodec.encodeComment(record)
        if (bytes.size <= MAX_COMMENT_BYTES) {
            val decoded = DiscussionCodec.decodeComment(bytes)
            require(decoded is Decoded.Ok && decoded.value == record) {
                "encoded comment record cannot be read back"
            }
        }
        return bytes
    }

    private fun entryPath(id: DiscussionId, name: EntryName): TreePath =
        TreePath.require(".plainbase/discussions/${id.value}/${name.fileName}")

    private fun refused(status: Int, code: String): DiscussionWriteOutcome.Refused =
        DiscussionWriteOutcome.Refused(DiscussionRefusal(status, code))

    private sealed interface LoadedRead {
        val commentCount: Int
        val priorBytes: ByteArray?
        val refusal: DiscussionWriteOutcome.Refused?

        data class Ready(
            val files: DiscussionFiles,
            override val commentCount: Int,
            override val priorBytes: ByteArray?,
        ) : LoadedRead {
            override val refusal: DiscussionWriteOutcome.Refused? = null
        }

        data class Refused(
            override val refusal: DiscussionWriteOutcome.Refused,
        ) : LoadedRead {
            override val commentCount: Int = 0
            override val priorBytes: ByteArray? = null
        }
    }

    private data class Mutation(
        val id: DiscussionId,
        val commentId: CommentId?,
        val actor: Author,
        val action: String,
        val markerChanged: Boolean,
        val changes: List<HistoryChange>,
        val undo: UndoPlan,
    )

    private sealed interface UndoPlan {
        data class Create(val names: List<EntryName>) : UndoPlan
        data class Replace(val bytes: Map<EntryName, ByteArray>) : UndoPlan
        data class Purge(val entry: EntryPath) : UndoPlan
    }

    companion object {
        private const val MAX_PAGE_DISCUSSIONS = 200
        private val logger = KotlinLogging.logger {}
    }
}
