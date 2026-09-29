package com.plainbase.frameworks.discussion

import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.discussion.Actor
import com.plainbase.domain.discussion.Anchor
import com.plainbase.domain.discussion.Author
import com.plainbase.domain.discussion.AuthorKind
import com.plainbase.domain.discussion.CommentId
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionPageSource
import com.plainbase.domain.discussion.DiscussionRowWriter
import com.plainbase.domain.discussion.DiscussionRows
import com.plainbase.domain.discussion.DiscussionStore
import com.plainbase.domain.discussion.EntryName
import com.plainbase.domain.discussion.PageRef
import com.plainbase.domain.discussion.RowUpdate
import com.plainbase.domain.discussion.Stamp
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.SubjectKey
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.ContentWriteMonitor
import com.plainbase.domain.service.DiscussionCommand
import com.plainbase.domain.service.DiscussionFullReads
import com.plainbase.domain.service.DiscussionIdProvider
import com.plainbase.domain.service.DiscussionReads
import com.plainbase.domain.service.DiscussionReparseExecutor
import com.plainbase.domain.service.DiscussionReparser
import com.plainbase.domain.service.DiscussionSyncState
import com.plainbase.domain.service.DiscussionWriteOutcome
import com.plainbase.domain.service.DiscussionWriter
import com.plainbase.domain.service.RebuildScheduler
import com.plainbase.domain.service.SyncedDiscussionIndex
import com.plainbase.domain.service.write
import com.plainbase.frameworks.filesystem.LocalDiscussionStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Instant

internal class DiscussionWorld(
    readEntryBytes: ((Path, Int) -> ByteArray)? = null,
    pageBytes: ByteArray = "# Discussion page\n\nA stable page body.\n".encodeToByteArray(),
    onEntryNameScanned: (EntryName) -> Unit = {},
) : AutoCloseable {
    val base: Path = Files.createTempDirectory("plainbase-discussion-world")
    val rootPath: Path = Files.createDirectories(base.resolve(ROOT.value))
    val databasePath: Path = base.resolve("data/discussions.db")
    val db = DiscussionDb(databasePath)
    val rows = JdbcDiscussionRows(db)
    val rowObserver = DiscussionRowObserver()
    private val observedRows = ObservedDiscussionRows(rows, rowObserver)
    val sync = DiscussionSyncState(listOf(ROOT))
    val availability = RootAvailability(TEST_CLOCK)
    val store = LocalDiscussionStore(
        mapOf(ROOT to rootPath), readEntryBytes = readEntryBytes, onEntryNameScanned = onEntryNameScanned,
    )
    val fullReads = DiscussionFullReads(store)
    val index = SyncedDiscussionIndex(observedRows, store, fullReads, sync)
    val alarm = ManualAlarm()
    val reads = DiscussionReads(observedRows, store, fullReads, sync, availability)
    val pageBytes = pageBytes.copyOf()
    val page = PageRef(PAGE_ID, TreePath.require("guide/discussion.md"))
    val pageAnchor = Anchor.Page(CitationFactory().contentHash(pageBytes), null)
    val otherPage = PageRef(OTHER_PAGE_ID, TreePath.require("reference/other-discussion.md"))
    val otherPageAnchor = Anchor.Page(CitationFactory().contentHash(pageBytes), null)
    private val monitor = ContentWriteMonitor()
    private val ids = WorldIds()
    val actor = Author(
        Actor(SubjectKey("discussion-world", "writer"), "World writer"),
        AuthorKind.HUMAN,
    )

    fun writer(history: HistoryProvider = NoOpHistoryProvider) =
        DiscussionWriter(
            monitor,
            store,
            DiscussionPageSource { _, _ -> ContentRead.Bytes(pageBytes.copyOf()) },
            { history },
            index,
            ids,
            TEST_CLOCK,
        )

    fun startDiscussion(
        body: String = "First comment from the real writer.\n",
        history: HistoryProvider = NoOpHistoryProvider,
    ): DiscussionId = startDiscussionOn(page, pageAnchor, body, history)

    fun startDiscussionOn(
        discussionPage: PageRef,
        discussionAnchor: Anchor.Page,
        body: String,
        history: HistoryProvider = NoOpHistoryProvider,
    ): DiscussionId {
        val result = writer(history).write(DiscussionCommand.Start(ROOT, actor, discussionPage, discussionAnchor, body))
        return (result as? DiscussionWriteOutcome.Done)?.id
            ?: error("discussion start did not complete: $result")
    }

    fun addComment(
        id: DiscussionId,
        body: String,
        history: HistoryProvider = NoOpHistoryProvider,
    ): DiscussionWriteOutcome = writer(history).write(DiscussionCommand.AddComment(ROOT, actor, id, body))

    fun reparser(store: DiscussionStore = this.store): DiscussionReparser = DiscussionReparser(
        setOf(ROOT),
        observedRows,
        store,
        if (store === this.store) fullReads else DiscussionFullReads(store),
    )

    fun executor(
        store: DiscussionStore = this.store,
        recoveryBaseMillis: Long = 50,
        beforeClear: (RootName) -> Unit = {},
        afterClear: (RootName) -> Unit = {},
    ): DiscussionReparseExecutor = DiscussionReparseExecutor(
        reparser(store),
        sync,
        availability,
        alarm,
        checkDelayMillis = 50,
        recoveryBaseMillis = recoveryBaseMillis,
        beforeClear = beforeClear,
        afterClear = afterClear,
    )

    override fun close() {
        db.close()
        base.toFile().deleteRecursively()
    }

    private class WorldIds : DiscussionIdProvider {
        private val discussions = AtomicInteger(1)
        private val comments = AtomicInteger(101)

        override fun nextDiscussion(): DiscussionId = discussionId(discussions.getAndIncrement())

        override fun nextComment(): CommentId = CommentId.require(idValue(comments.getAndIncrement()))
    }

    companion object {
        val ROOT: RootName = RootName.require("docs")
        val PAGE_ID: PageId = PageId.require("01900000-0000-4000-8000-000000000001")
        val OTHER_PAGE_ID: PageId = PageId.require("01900000-0000-4000-8000-000000000002")
        private val TEST_CLOCK = object : Clock {
            override fun now(): Instant = Instant.parse("2026-09-26T10:00:00Z")
        }

        fun discussionId(number: Int): DiscussionId = DiscussionId.require(idValue(number))

        fun commentId(number: Int): CommentId = CommentId.require(idValue(number))

        private fun idValue(number: Int): String =
            "01900000-0000-7000-8000-${number.toString(16).padStart(12, '0')}"
    }
}

internal data class ObservedRowApply(
    val root: RootName,
    val id: DiscussionId,
    val update: RowUpdate,
    val stamp: Stamp?,
    val dropMatch: Boolean,
)

internal class DiscussionRowObserver {
    private val events = CopyOnWriteArrayList<ObservedRowApply>()

    val applies: List<ObservedRowApply>
        get() = events.toList()

    fun clear() = events.clear()

    fun applied(root: RootName, id: DiscussionId, update: RowUpdate, stamp: Stamp?, dropMatch: Boolean) {
        events += ObservedRowApply(root, id, update, stamp, dropMatch)
    }
}

private class ObservedDiscussionRows(
    private val delegate: DiscussionRows,
    private val observer: DiscussionRowObserver,
) : DiscussionRows by delegate {
    override fun <T> writing(block: DiscussionRowWriter.() -> T): T = delegate.writing {
        val writer = this
        block(object : DiscussionRowWriter by writer {
            override fun apply(root: RootName, id: DiscussionId, update: RowUpdate, stamp: Stamp?, dropMatch: Boolean) {
                writer.apply(root, id, update, stamp, dropMatch)
                observer.applied(root, id, update, stamp, dropMatch)
            }
        })
    }

    override fun <T> tryWriting(block: DiscussionRowWriter.() -> T): T? = delegate.tryWriting {
        val writer = this
        block(object : DiscussionRowWriter by writer {
            override fun apply(root: RootName, id: DiscussionId, update: RowUpdate, stamp: Stamp?, dropMatch: Boolean) {
                writer.apply(root, id, update, stamp, dropMatch)
                observer.applied(root, id, update, stamp, dropMatch)
            }
        })
    }
}

internal class ManualAlarm : RebuildScheduler.Alarm {
    private data class Scheduled(val delayMillis: Long, val action: () -> Unit)

    private val scheduled = mutableListOf<Scheduled>()
    private val recordedDelays = mutableListOf<Long>()

    val delays: List<Long>
        @Synchronized get() = recordedDelays.toList()

    val pendingImmediateCount: Int
        @Synchronized get() = scheduled.count { it.delayMillis == 0L }

    @Synchronized
    override fun after(delayMillis: Long, action: () -> Unit) {
        scheduled += Scheduled(delayMillis, action)
        recordedDelays += delayMillis
    }

    fun runNext(delayMillis: Long? = null) {
        val task = synchronized(this) {
            val index = if (delayMillis == null) 0 else scheduled.indexOfFirst { it.delayMillis == delayMillis }
            check(index >= 0 && index < scheduled.size) { "no scheduled task for delay $delayMillis" }
            scheduled.removeAt(index)
        }
        task.action()
    }

    fun runAll(delayMillis: Long? = null) {
        while (true) {
            val task = synchronized(this) {
                val index = if (delayMillis == null) 0 else scheduled.indexOfFirst { it.delayMillis == delayMillis }
                if (index < 0 || index >= scheduled.size) null else scheduled.removeAt(index)
            } ?: return
            task.action()
        }
    }
}
