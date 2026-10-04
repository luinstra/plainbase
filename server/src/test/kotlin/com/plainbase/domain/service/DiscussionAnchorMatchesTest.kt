package com.plainbase.domain.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.discussion.Actor
import com.plainbase.domain.discussion.Anchor
import com.plainbase.domain.discussion.AnchorMatch
import com.plainbase.domain.discussion.AnchorSelection
import com.plainbase.domain.discussion.Author
import com.plainbase.domain.discussion.AuthorKind
import com.plainbase.domain.discussion.CachedMatch
import com.plainbase.domain.discussion.CommentId
import com.plainbase.domain.discussion.DiscussionCodec
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionPageSource
import com.plainbase.domain.discussion.DiscussionRecord
import com.plainbase.domain.discussion.DiscussionRowWriter
import com.plainbase.domain.discussion.DiscussionRows
import com.plainbase.domain.discussion.DiscussionStatus
import com.plainbase.domain.discussion.DiscussionStore
import com.plainbase.domain.discussion.EntriesRead
import com.plainbase.domain.discussion.EntryName
import com.plainbase.domain.discussion.EntryPath
import com.plainbase.domain.discussion.EntryPut
import com.plainbase.domain.discussion.FrontmatterExtras
import com.plainbase.domain.discussion.MatchRange
import com.plainbase.domain.discussion.PageBytes
import com.plainbase.domain.discussion.PageRef
import com.plainbase.domain.discussion.QuoteCapture
import com.plainbase.domain.discussion.StoreWrite
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.SubjectKey
import com.plainbase.domain.root.RootName
import com.plainbase.frameworks.discussion.DbFaults
import com.plainbase.frameworks.discussion.DiscussionDb
import com.plainbase.frameworks.discussion.JdbcDiscussionRows
import com.plainbase.frameworks.filesystem.LocalDiscussionStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.time.Instant

class DiscussionAnchorMatchesTest : FunSpec({
    test("a computed match carries actual bytes hash rather than the stale index hash") {
        withAnchorWorld { rows, store, fullReads, sync ->
            val id = discussionId(1)
            val original = "A quote in the page".encodeToByteArray()
            val indexedHash = CitationFactory().contentHash(original)
            writeMarker(store, quoteMarker(id, indexedHash, original, "quote"))
            SyncedDiscussionIndex(rows, store, fullReads, sync).publish(ROOT, id, markerChanged = false) { store.read(ROOT, id) }
            val current = PageBytes.of("Added before. A quote in the page".encodeToByteArray(), emptyList())
            val matches = AnchorMatches(rows, store, fullReads, sync)
            val result = matches.forPage(ROOT, PAGE, indexedHash, listOf(requireNotNull(rows.row(ROOT, id)))) { current }

            result.single().match.shouldBeInstanceOf<AnchorMatch.Moved>()
            result.single().pageHash shouldBe current.hash
            result.single().pageHash shouldNotBe indexedHash
            val cached = matches.forPage(ROOT, PAGE, current.hash, listOf(requireNotNull(rows.row(ROOT, id)))) {
                error("cached current result must not reread the page")
            }
            cached.single().pageHash shouldBe current.hash
            cached.single().match shouldBe result.single().match
        }
    }

    test("a cache hit reuses its match without reading page bytes") {
        withAnchorWorld { rows, store, fullReads, sync ->
            val id = discussionId(1)
            val raw = "A quote in the page".encodeToByteArray()
            val hash = CitationFactory().contentHash(raw)
            val marker = quoteMarker(id, hash, raw, "quote")
            writeMarker(store, marker)
            val indexed = SyncedDiscussionIndex(rows, store, fullReads, sync)
            indexed.publish(ROOT, id, markerChanged = false) { store.read(ROOT, id) }
            val row = requireNotNull(rows.row(ROOT, id))
            val expected = AnchorMatch.Exact(MatchRange(2, 7))
            rows.writing { storeMatch(ROOT, id, requireNotNull(row.anchorHash), hash, expected) }
            val matches = AnchorMatches(rows, store, fullReads, sync)
            var pageReads = 0

            val result = matches.forPage(ROOT, PAGE, hash, listOf(row)) {
                pageReads++
                error("a cache hit must not read page bytes")
            }

            result.single().match shouldBe expected
            result.single().pageHash shouldBe hash
            pageReads shouldBe 0
            matches.hits shouldBe 1
            matches.computations shouldBe 0
        }
    }

    test("a cache read failure enters unsynced and still serves the file backed match") {
        withAnchorWorldAndDbPath { rows, store, fullReads, sync, dbPath ->
            val id = discussionId(12)
            val raw = "A quote in the page".encodeToByteArray()
            val hash = CitationFactory().contentHash(raw)
            writeMarker(store, quoteMarker(id, hash, raw, "quote"))
            SyncedDiscussionIndex(rows, store, fullReads, sync).publish(ROOT, id, markerChanged = false) {
                store.read(ROOT, id)
            }
            val row = requireNotNull(rows.row(ROOT, id))
            DbFaults(dbPath).use { faults ->
                faults.failAnchorMatchReads()
                val matches = AnchorMatches(rows, store, fullReads, sync)

            val result = matches.forPage(ROOT, PAGE, hash, listOf(row)) { PageBytes.of(raw, emptyList()) }

                result.single().match shouldBe AnchorMatch.Exact(MatchRange(2, 7))
                sync.isUnsynced(ROOT) shouldBe true
            }
        }
    }

    test("an already unsynced root skips anchor cache reads") {
        withAnchorWorld { baseRows, store, fullReads, sync ->
            val id = discussionId(13)
            val raw = "A quote in the page".encodeToByteArray()
            val hash = CitationFactory().contentHash(raw)
            writeMarker(store, quoteMarker(id, hash, raw, "quote"))
            SyncedDiscussionIndex(baseRows, store, fullReads, sync).publish(ROOT, id, markerChanged = false) {
                store.read(ROOT, id)
            }
            val row = requireNotNull(baseRows.row(ROOT, id))
            var cacheReads = 0
            val rows = object : DiscussionRows by baseRows {
                override fun cached(root: RootName, id: DiscussionId): CachedMatch? {
                    cacheReads++
                    error("unsynced roots must not read the anchor cache")
                }
            }
            sync.enter(ROOT, "fixture unsynced")

            val result = AnchorMatches(rows, store, fullReads, sync)
                .forPage(ROOT, PAGE, hash, listOf(row)) { PageBytes.of(raw, emptyList()) }

            result.single().match shouldBe AnchorMatch.Exact(MatchRange(2, 7))
            cacheReads shouldBe 0
        }
    }

    test("a new comment keeps the cached match") {
        withAnchorWorld { rows, store, fullReads, sync ->
            val id = discussionId(10)
            val raw = "A quote in the page".encodeToByteArray()
            val hash = CitationFactory().contentHash(raw)
            val marker = quoteMarker(id, hash, raw, "quote")
            writeMarker(store, marker)
            val indexed = SyncedDiscussionIndex(rows, store, fullReads, sync)
            indexed.publish(ROOT, id, markerChanged = false) { store.read(ROOT, id) }
            val matches = AnchorMatches(rows, store, fullReads, sync)
            val initial = requireNotNull(rows.row(ROOT, id))
            matches.forPage(ROOT, PAGE, hash, listOf(initial)) { PageBytes.of(raw, emptyList()) }

            writer(store, indexed, raw).write(DiscussionCommand.AddComment(ROOT, DISCUSSION_AUTHOR, id, "second comment"))
                .shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
            val updated = requireNotNull(rows.row(ROOT, id))
            updated.commentCount shouldBe 1
            rows.cached(ROOT, id)?.anchorHash shouldBe updated.anchorHash
            rows.cached(ROOT, id)?.pageHash shouldBe hash
            matches.forPage(ROOT, PAGE, hash, listOf(updated)) { PageBytes.of(raw, emptyList()) }
                .single().match shouldBe AnchorMatch.Exact(MatchRange(2, 7))

            matches.hits shouldBe 1
            matches.computations shouldBe 1
        }
    }

    test("a reattach on an unchanged page recomputes") {
        withAnchorWorld { rows, store, fullReads, sync ->
            val id = discussionId(11)
            val raw = "A quote in the page".encodeToByteArray()
            val hash = CitationFactory().contentHash(raw)
            val marker = quoteMarker(id, hash, raw, "quote")
            writeMarker(store, marker)
            val indexed = SyncedDiscussionIndex(rows, store, fullReads, sync)
            indexed.publish(ROOT, id, markerChanged = false) { store.read(ROOT, id) }
            val matches = AnchorMatches(rows, store, fullReads, sync)
            val initial = requireNotNull(rows.row(ROOT, id))
            matches.forPage(ROOT, PAGE, hash, listOf(initial)) { PageBytes.of(raw, emptyList()) }
            val priorCache = requireNotNull(rows.cached(ROOT, id))
            val quote = marker.anchor as Anchor.Quote

            writer(store, indexed, raw).write(DiscussionCommand.Reattach(ROOT, DISCUSSION_AUTHOR, id, quote))
                .shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
            val updated = requireNotNull(rows.row(ROOT, id))
            updated.anchorHash shouldNotBe priorCache.anchorHash
            rows.cached(ROOT, id).shouldBeNull()
            rows.writing { storeMatch(ROOT, id, priorCache.anchorHash, hash, priorCache.match) }

            matches.forPage(ROOT, PAGE, hash, listOf(updated)) { PageBytes.of(raw, emptyList()) }

            matches.computations shouldBe 2
            rows.cached(ROOT, id)?.anchorHash shouldBe updated.anchorHash
            rows.cached(ROOT, id)?.pageHash shouldBe hash
        }
    }

    test("a page edit invalidates") {
        withAnchorWorld { rows, store, fullReads, sync ->
            val id = discussionId(12)
            val raw = "A quote in the page".encodeToByteArray()
            val hash = CitationFactory().contentHash(raw)
            writeMarker(store, quoteMarker(id, hash, raw, "quote"))
            SyncedDiscussionIndex(rows, store, fullReads, sync).publish(ROOT, id, markerChanged = false) {
                store.read(ROOT, id)
            }
            val row = requireNotNull(rows.row(ROOT, id))
            val matches = AnchorMatches(rows, store, fullReads, sync)
            matches.forPage(ROOT, PAGE, hash, listOf(row)) { PageBytes.of(raw, emptyList()) }
            val edited = "A revised quote in the page".encodeToByteArray()
            val editedHash = CitationFactory().contentHash(edited)

            val recomputed = matches.forPage(ROOT, PAGE, editedHash, listOf(row)) { PageBytes.of(edited, emptyList()) }

            recomputed.single().match shouldBe AnchorMatch.Moved(
                MatchRange(edited.decodeToString().indexOf("quote"), edited.decodeToString().indexOf("quote") + 5),
            )
            matches.computations shouldBe 2
            rows.cached(ROOT, id)?.pageHash shouldBe editedHash
        }
    }

    test("a cache miss matches once and stores the marker hash") {
        withAnchorWorld { rows, store, fullReads, sync ->
            val id = discussionId(2)
            val raw = "A quote in the page".encodeToByteArray()
            val hash = CitationFactory().contentHash(raw)
            val initialMarker = quoteMarker(id, hash, raw, "quote")
            writeMarker(store, initialMarker)
            val indexed = SyncedDiscussionIndex(rows, store, fullReads, sync)
            indexed.publish(ROOT, id, markerChanged = false) { store.read(ROOT, id) }
            val row = requireNotNull(rows.row(ROOT, id))
            val oldVersion = (store.read(ROOT, id) as EntriesRead.Present).entries.single { it.name == EntryName.Marker }.version
            val changedBytes = DiscussionCodec.encodeDiscussion(
                initialMarker.copy(extras = FrontmatterExtras(null, listOf("note: changed"))),
            )
            val write = store.replace(ROOT, EntryPath(id, EntryName.Marker), oldVersion, changedBytes)
            write.shouldBeInstanceOf<StoreWrite.Written>()
            val changedVersion = (store.read(ROOT, id) as EntriesRead.Present).entries.single { it.name == EntryName.Marker }.version
            val matches = AnchorMatches(rows, store, fullReads, sync)
            var pageReads = 0

            val result = matches.forPage(ROOT, PAGE, hash, listOf(row)) {
                pageReads++
                PageBytes.of(raw, emptyList())
            }

            result.single().match shouldBe AnchorMatch.Exact(MatchRange(2, 7))
            pageReads shouldBe 1
            matches.computations shouldBe 1
            rows.cached(ROOT, id)?.anchorHash shouldBe changedVersion.token
            (rows.cached(ROOT, id)?.anchorHash == row.anchorHash) shouldBe false
            rows.cached(ROOT, id)?.pageHash shouldBe hash
        }
    }

    test("a cache store failure still serves the computed match") {
        withAnchorWorld { baseRows, store, fullReads, sync ->
            val id = discussionId(3)
            val raw = "A quote in the page".encodeToByteArray()
            val hash = CitationFactory().contentHash(raw)
            writeMarker(store, quoteMarker(id, hash, raw, "quote"))
            SyncedDiscussionIndex(baseRows, store, fullReads, sync).publish(ROOT, id, markerChanged = false) {
                store.read(ROOT, id)
            }
            val row = requireNotNull(baseRows.row(ROOT, id))
            val rows = object : DiscussionRows by baseRows {
                override fun <T> tryWriting(block: DiscussionRowWriter.() -> T): T? = throw IllegalStateException("cache write failed")
            }
            val matches = AnchorMatches(rows, store, fullReads, sync)
            val logger = LoggerFactory.getLogger(AnchorMatches::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)

            val result = try {
                matches.forPage(ROOT, PAGE, hash, listOf(row)) { PageBytes.of(raw, emptyList()) }
            } finally {
                logger.detachAppender(appender)
                appender.stop()
            }

            result.single().match shouldBe AnchorMatch.Exact(MatchRange(2, 7))
            sync.isUnsynced(ROOT) shouldBe true
            appender.list.any {
                it.level == Level.WARN && "discussion match cache write failed" in it.formattedMessage
            } shouldBe true
        }
    }

    test("a non scope root is refused by the anchor matcher") {
        withAnchorWorld { rows, store, fullReads, sync ->
            val failure = io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
                AnchorMatches(rows, store, fullReads, sync).forPage(
                    RootName.require("archive"),
                    PAGE,
                    "hash",
                    emptyList(),
                ) { error("a non-scope root should be rejected before reading page bytes") }
            }

            failure.message shouldBe "not a discussion root: archive"
        }
    }

    test("a failed marker read in a match throws") {
        withAnchorWorld { rows, baseStore, _, sync ->
            val id = discussionId(4)
            val raw = "A quote in the page".encodeToByteArray()
            val hash = CitationFactory().contentHash(raw)
            writeMarker(baseStore, quoteMarker(id, hash, raw, "quote"))
            SyncedDiscussionIndex(rows, baseStore, DiscussionFullReads(baseStore), sync).publish(ROOT, id, markerChanged = false) {
                baseStore.read(ROOT, id)
            }
            val row = requireNotNull(rows.row(ROOT, id))
            val failingStore = object : DiscussionStore by baseStore {
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead =
                    EntriesRead.Failed("marker failed")
            }
            val matches = AnchorMatches(rows, failingStore, DiscussionFullReads(failingStore), sync)

            val failure = io.kotest.assertions.throwables.shouldThrow<DiscussionReadFailed> {
                matches.forPage(ROOT, PAGE, hash, listOf(row)) { PageBytes.of(raw, emptyList()) }
            }

            failure.message shouldBe "discussion read failed for root docs: anchor marker unavailable for ${id.value}"
        }
    }

    test("absent incomplete and unreadable marker reads are omitted") {
        withAnchorWorld { rows, baseStore, _, sync ->
            val id = discussionId(14)
            val raw = "A quote in the page".encodeToByteArray()
            val hash = CitationFactory().contentHash(raw)
            writeMarker(baseStore, quoteMarker(id, hash, raw, "quote"))
            SyncedDiscussionIndex(rows, baseStore, DiscussionFullReads(baseStore), sync).publish(ROOT, id, markerChanged = false) {
                baseStore.read(ROOT, id)
            }
            val row = requireNotNull(rows.row(ROOT, id))
            val reads = listOf(
                EntriesRead.Absent,
                EntriesRead.Present(emptyList(), 0),
                EntriesRead.Symlinked("comment.md"),
            )

            reads.forEach { read ->
                val store = object : DiscussionStore by baseStore {
                    override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead = read
                }
                val matches = AnchorMatches(rows, store, DiscussionFullReads(store), sync)

            matches.forPage(ROOT, PAGE, hash, listOf(row)) { PageBytes.of(raw, emptyList()) }
                    .shouldBeEmpty()
            }
        }
    }
})

private val ROOT = RootName.require("docs")
private val PAGE = PageId.require("01900000-0000-4000-8000-000000000001")
private val DISCUSSION_AUTHOR = Author(Actor(SubjectKey("issuer", "starter"), "Starter"), AuthorKind.HUMAN)

private fun withAnchorWorld(block: (JdbcDiscussionRows, DiscussionStore, DiscussionFullReads, DiscussionSyncState) -> Unit) =
    withAnchorWorldAndDbPath { rows, store, fullReads, sync, _ -> block(rows, store, fullReads, sync) }

private fun withAnchorWorldAndDbPath(
    block: (JdbcDiscussionRows, DiscussionStore, DiscussionFullReads, DiscussionSyncState, Path) -> Unit,
) {
    val rootPath = Files.createTempDirectory("pb-anchor-discussions")
    val dbPath = Files.createTempDirectory("pb-anchor-db").resolve("discussions.db")
    val store = LocalDiscussionStore(mapOf(ROOT to rootPath))
    DiscussionDb(dbPath).use { db ->
        try {
            val rows = JdbcDiscussionRows(db)
            val fullReads = DiscussionFullReads(store)
            block(rows, store, fullReads, DiscussionSyncState(setOf(ROOT)), dbPath)
        } finally {
            rootPath.toFile().deleteRecursively()
            dbPath.parent.toFile().deleteRecursively()
        }
    }
}

private fun quoteMarker(id: DiscussionId, hash: String, raw: ByteArray, text: String): DiscussionRecord {
    val start = raw.decodeToString().indexOf(text)
    val end = start + text.length
    return DiscussionRecord(
        id,
        PageRef(PAGE, TreePath.require("guide.md")),
        DiscussionStatus.OPEN,
        Instant.parse("2026-09-26T10:00:00.000Z"),
        DISCUSSION_AUTHOR,
        null,
        Anchor.Quote(hash, null, QuoteCapture.at(raw, start, end, emptyList(), AnchorSelection.NARROWED)),
        null,
        FrontmatterExtras.NONE,
    )
}

private fun writeMarker(store: DiscussionStore, marker: DiscussionRecord) {
    val result = store.createFiles(ROOT, marker.id, listOf(EntryPut(EntryName.Marker, DiscussionCodec.encodeDiscussion(marker))))
    result.shouldBeInstanceOf<StoreWrite.Written>()
}

private fun writer(store: DiscussionStore, index: SyncedDiscussionIndex, raw: ByteArray): DiscussionWriter =
    DiscussionWriter(
        monitor = ContentWriteMonitor(),
        store = store,
        pages = DiscussionPageSource { _, _ -> ContentRead.Bytes(raw.copyOf()) },
        histories = { NoOpHistoryProvider },
        index = index,
        ids = object : DiscussionIdProvider {
            override fun nextDiscussion(): DiscussionId = error("this test only writes to an existing discussion")
            override fun nextComment(): CommentId = CommentId.require("01900000-0000-7000-8000-000000000101")
        },
        clock = object : Clock {
            override fun now(): Instant = Instant.parse("2026-09-26T10:00:00Z")
        },
    )

private fun discussionId(value: Int): DiscussionId =
    DiscussionId.require("01900000-0000-7000-8000-${value.toString(16).padStart(12, '0')}")
