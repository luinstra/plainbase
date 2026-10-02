package com.plainbase.domain.service

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.StoreRead
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.discussion.Actor
import com.plainbase.domain.discussion.Anchor
import com.plainbase.domain.discussion.AnchorMatch
import com.plainbase.domain.discussion.AnchorSelection
import com.plainbase.domain.discussion.Author
import com.plainbase.domain.discussion.AuthorKind
import com.plainbase.domain.discussion.Decoded
import com.plainbase.domain.discussion.DiscussionCodec
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionPersistenceFailure
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
import com.plainbase.domain.discussion.PageRef
import com.plainbase.domain.discussion.QuoteCapture
import com.plainbase.domain.discussion.StoreWrite
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.principal.SubjectKey
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.frameworks.discussion.DiscussionDb
import com.plainbase.frameworks.discussion.JdbcDiscussionRows
import com.plainbase.frameworks.discussion.withHeldReads
import com.plainbase.frameworks.filesystem.LocalDiscussionStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.time.Instant

class AnchorPrecomputeTest : FunSpec({
    test("cancelled marker permit clears running work without scheduling a retry") {
        withPrecomputeWorld({ writePage(it, "guide.md", "# Guide\n\ninterrupted quote.\n") }) { world ->
            val page = world.snapshot.pages.single()
            val id = world.addQuote(180, page, "interrupted quote")
            val contentRead = CountDownLatch(1)
            val content = object : ContentStore by world.content {
                override fun readClassified(path: TreePath): StoreRead = world.content.readClassified(path).also {
                    contentRead.countDown()
                }
            }
            val alarm = TestAlarm()
            val precompute = world.precompute(alarm, content = content)
            precompute.reindexed(ROOT, page)
            val failure = AtomicReference<Throwable?>()
            val interrupted = AtomicBoolean()

            withHeldReads(8, { block -> world.fullReads.withNarrowed(block) }) {
                val worker = thread(isDaemon = true) {
                    try {
                        alarm.runNext()
                    } catch (caught: Throwable) {
                        failure.set(caught)
                    } finally {
                        interrupted.set(Thread.currentThread().isInterrupted)
                        Thread.interrupted()
                    }
                }
                try {
                    contentRead.await(5, TimeUnit.SECONDS) shouldBe true
                    worker.interrupt()
                    worker.join(5_000)
                    worker.isAlive shouldBe false
                } finally {
                    worker.interrupt()
                    worker.join(5_000)
                }
            }

            failure.get().shouldBeInstanceOf<InterruptedException>()
            interrupted.get() shouldBe true
            world.rows.cached(ROOT, id).shouldBeNull()
            world.sync.isUnsynced(ROOT) shouldBe false
            alarm.delays shouldBe listOf(0L)

            precompute.reindexed(ROOT, page)
            alarm.delays shouldBe listOf(0L, 0L)
            alarm.runNext()
            world.rows.cached(ROOT, id)?.pageHash shouldBe page.contentHash
        }
    }

    test("a precompute stores the hash of the marker it matched") {
        withPrecomputeWorld({ writePage(it, "guide.md", "# Guide\n\nfirst distinct quote.\n") }) { world ->
            val page = world.snapshot.pages.single()
            val id = world.addQuote(101, page, "first distinct quote")
            val marker = world.discussions.read(ROOT, id) as EntriesRead.Present
            val oldMarker = marker.entries.single { it.name == EntryName.Marker }
            val decoded = DiscussionCodec.decodeDiscussion(oldMarker.take()) as Decoded.Ok<*>
            val record = decoded.value as DiscussionRecord
            val replacement = DiscussionCodec.encodeDiscussion(
                record.copy(extras = FrontmatterExtras(null, listOf("note: marker revision B"))),
            )
            world.discussions.replace(ROOT, EntryPath(id, EntryName.Marker), oldMarker.version, replacement)
            world.synced.publish(ROOT, id, markerChanged = true) { world.discussions.read(ROOT, id) }
            val latestMarker = (world.discussions.read(ROOT, id) as EntriesRead.Present)
                .entries.single { it.name == EntryName.Marker }

            val alarm = TestAlarm()
            val scheduled = world.precompute(alarm)
            scheduled.published(world.snapshot, emptySet())
            alarm.runNext()

            val row = requireNotNull(world.rows.row(ROOT, id))
            val cached = requireNotNull(world.rows.cached(ROOT, id))
            cached.anchorHash shouldBe latestMarker.version.token
            cached.anchorHash shouldBe row.anchorHash
            cached.pageHash shouldBe page.contentHash
            val matches = AnchorMatches(world.rows, world.discussions, world.fullReads, world.sync)
            matches.forPage(ROOT, page.id, page.contentHash, listOf(row)) {
                error("the precomputed cache entry should serve the match")
            }.single().match shouldBe AnchorMatch.Exact(MatchRange(9, 29))
        }
    }

    test("a superseded snapshot is pre-computed on the next signal") {
        withPrecomputeWorld(
            seed = { writePage(it, "guide.md", "# Guide\n\ninitial quote value.\n") },
            wrapContent = ::BlockingContentStore,
        ) { world ->
            val page = world.snapshot.pages.single()
            val id = world.addQuote(102, page, "initial quote value")
            val alarm = TestAlarm()
            val precompute = world.precompute(alarm)
            val signal = DiscussionPublicationSignal()
            signal.attach(precompute)
            val blocking = world.content as BlockingContentStore
            blocking.gateNextRead()
            signal.published(world.snapshot, emptySet())
            val worker = Thread { alarm.runNext() }.apply {
                isDaemon = true
                start()
            }
            blocking.entered.await(5, TimeUnit.SECONDS) shouldBe true
            val newBytes = "# Guide\n\nnew superseding quote value.\n".encodeToByteArray()
            Files.write(world.root.resolve(page.path.value), newBytes)
            blocking.release.countDown()
            worker.join(5_000)
            worker.isAlive shouldBe false
            world.rows.cached(ROOT, id).shouldBeNull()

            val currentPage = world.harness.builder.reindex(RootedPath(ROOT, page.path)).pages.single()
            signal.reindexed(ROOT, currentPage)
            alarm.runNext()

            world.rows.cached(ROOT, id)?.pageHash shouldBe CitationFactory().contentHash(newBytes)
        }
    }

    test("a failed pre-compute page is retried with backoff") {
        withPrecomputeWorld({ writePage(it, "guide.md", "# Guide\n\nretry marker quote.\n") }) { world ->
            val page = world.snapshot.pages.single()
            val id = world.addQuote(103, page, "retry marker quote")
            var failOnce = true
            val failingDiscussions = object : DiscussionStore by world.discussions {
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead {
                    if (failOnce && only == setOf(EntryName.Marker)) {
                        failOnce = false
                        return EntriesRead.Failed("transient marker read")
                    }
                    return world.discussions.read(root, id, only)
                }
            }
            val alarm = TestAlarm()
            val precompute = world.precompute(alarm, failingDiscussions)

            precompute.published(world.snapshot, emptySet())
            alarm.runNext()

            world.rows.cached(ROOT, id).shouldBeNull()
            alarm.delays shouldBe listOf(0L, 1_000L)
            alarm.runNext()
            alarm.runNext()
            world.rows.cached(ROOT, id)?.pageHash shouldBe page.contentHash
        }
    }

    test("absent incomplete and unreadable markers do not block later rows on the page") {
        withPrecomputeWorld({ writePage(it, "guide.md", "# Guide\n\nmarker status quote.\n") }) { world ->
            val page = world.snapshot.pages.single()
            val absent = world.addQuote(140, page, "marker status quote")
            val incomplete = world.addQuote(141, page, "marker status quote")
            val unreadable = world.addQuote(142, page, "marker status quote")
            val good = world.addQuote(143, page, "marker status quote")
            val store = object : DiscussionStore by world.discussions {
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead =
                    if (only == setOf(EntryName.Marker)) {
                        when (id) {
                            absent -> EntriesRead.Absent
                            incomplete -> EntriesRead.Present(emptyList(), 0)
                            unreadable -> EntriesRead.TooMany(301, null)
                            else -> world.discussions.read(root, id, only)
                        }
                    } else {
                        world.discussions.read(root, id, only)
                    }
            }
            val alarm = TestAlarm()
            val precompute = world.precompute(alarm, store)

            precompute.reindexed(ROOT, page)
            alarm.runNext()

            world.rows.cached(ROOT, absent).shouldBeNull()
            world.rows.cached(ROOT, incomplete).shouldBeNull()
            world.rows.cached(ROOT, unreadable).shouldBeNull()
            world.rows.cached(ROOT, good)?.pageHash shouldBe page.contentHash
            alarm.delays shouldBe listOf(0L)
        }
    }

    test("a discussions db match cache write unsyncs its root") {
        withPrecomputeWorld({ writePage(it, "guide.md", "# Guide\n\ndatabase cache quote.\n") }) { world ->
            val page = world.snapshot.pages.single()
            val id = world.addQuote(144, page, "database cache quote")
            val rows = object : DiscussionRows by world.rows {
                override fun <T> writing(block: DiscussionRowWriter.() -> T): T =
                    throw DiscussionPersistenceFailure(SQLException("fixture anchor cache failure"))
            }
            val alarm = TestAlarm()
            val precompute = world.precompute(alarm, rows = rows)

            precompute.reindexed(ROOT, page)
            alarm.runNext()

            world.sync.isUnsynced(ROOT) shouldBe true
            world.rows.cached(ROOT, id).shouldBeNull()
            alarm.delays shouldBe listOf(0L)
        }
    }

    test("a failed retry alarm leaves the page eligible again") {
        withPrecomputeWorld({ writePage(it, "guide.md", "# Guide\n\nretry alarm quote.\n") }) { world ->
            val page = world.snapshot.pages.single()
            val id = world.addQuote(126, page, "retry alarm quote")
            var failRead = true
            val store = object : DiscussionStore by world.discussions {
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead =
                    if (failRead && only == setOf(EntryName.Marker)) {
                        failRead = false
                        EntriesRead.Failed("temporary marker read fault")
                    } else {
                        world.discussions.read(root, id, only)
                    }
            }
            val alarm = TestAlarm(failFirstDelayed = true)
            val precompute = world.precompute(alarm, store)

            precompute.published(world.snapshot, emptySet())
            alarm.runNext()
            alarm.runNext()

            world.rows.cached(ROOT, id)?.pageHash shouldBe page.contentHash
        }
    }

    test("a root unavailable during pre-compute is not retried") {
        withPrecomputeWorld({ writePage(it, "guide.md", "# Guide\n\nroot loss quote.\n") }) { world ->
            val page = world.snapshot.pages.single()
            val id = world.addQuote(113, page, "root loss quote")
            val rootDown = object : DiscussionStore by world.discussions {
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead {
                    if (only == setOf(EntryName.Marker)) throw RootUnavailable(root, UnavailableCause.VANISHED)
                    return world.discussions.read(root, id, only)
                }
            }
            val alarm = TestAlarm()
            val precompute = world.precompute(alarm, rootDown)

            precompute.published(world.snapshot, emptySet())
            alarm.runNext()

            alarm.delays shouldBe listOf(0L)
            world.rows.cached(ROOT, id).shouldBeNull()
        }
    }

    test("a precompute stores matches with the blocking row writer") {
        withPrecomputeWorld({ writePage(it, "guide.md", "# Guide\n\nblocking writer quote.\n") }) { world ->
            val page = world.snapshot.pages.single()
            val id = world.addQuote(114, page, "blocking writer quote")
            var writingCalls = 0
            var tryWritingCalls = 0
            val guardedRows = object : DiscussionRows by world.rows {
                override fun <T> writing(block: DiscussionRowWriter.() -> T): T {
                    writingCalls++
                    return world.rows.writing(block)
                }

                override fun <T> tryWriting(block: DiscussionRowWriter.() -> T): T? {
                    tryWritingCalls++
                    return null
                }
            }
            val alarm = TestAlarm()
            val precompute = world.precompute(alarm, rows = guardedRows)

            precompute.published(world.snapshot, emptySet())
            alarm.runNext()

            writingCalls shouldBe 1
            tryWritingCalls shouldBe 0
            world.rows.cached(ROOT, id)?.pageHash shouldBe page.contentHash
        }
    }

    test("a page change precomputes every quote discussion on it") {
        withPrecomputeWorld({ writePage(it, "guide.md", "# Guide\n\nfirst quote value.\nsecond quote value.\n") }) { world ->
            val page = world.snapshot.pages.single()
            val first = world.addQuote(104, page, "first quote value")
            val second = world.addQuote(105, page, "second quote value")
            val alarm = TestAlarm()
            val precompute = world.precompute(alarm)
            val signal = DiscussionPublicationSignal().apply { attach(precompute) }

            signal.published(world.snapshot, emptySet())
            alarm.runNext()

            world.rows.cached(ROOT, first)?.pageHash shouldBe page.contentHash
            world.rows.cached(ROOT, second)?.pageHash shouldBe page.contentHash
        }
    }

    test("a publication revisits an unchanged page") {
        withPrecomputeWorld({ writePage(it, "guide.md", "# Guide\n\nfirst full pass quote.\nsecond full pass quote.\n") }) { world ->
            val page = world.snapshot.pages.single()
            val alarm = TestAlarm()
            val precompute = world.precompute(alarm)
            val signal = DiscussionPublicationSignal().apply { attach(precompute) }

            signal.published(world.snapshot, emptySet())
            alarm.runNext()
            val first = world.addQuote(108, page, "first full pass quote")
            val second = world.addQuote(109, page, "second full pass quote")
            signal.published(world.snapshot, emptySet())

            alarm.delays shouldBe listOf(0L, 0L)
            alarm.runNext()

            world.rows.cached(ROOT, first)?.pageHash shouldBe page.contentHash
            world.rows.cached(ROOT, second)?.pageHash shouldBe page.contentHash
        }
    }

    test("a precompute drains reindexed pages before its publication full pass") {
        withPrecomputeWorld({ root ->
            writePage(root, "first.md", "# First\n\nfirst drain quote.\n")
            writePage(root, "second.md", "# Second\n\nsecond drain quote.\n")
        }) { world ->
            val firstPage = world.snapshot.pages.single { it.path.value == "first.md" }
            val secondPage = world.snapshot.pages.single { it.path.value == "second.md" }
            val reads = mutableListOf<String>()
            val countedContent = object : ContentStore by world.content {
                override fun readClassified(path: TreePath): StoreRead =
                    world.content.readClassified(path).also { reads += path.value }
            }
            val alarm = TestAlarm()
            val precompute = world.precompute(alarm, content = countedContent)

            precompute.published(world.snapshot, emptySet())
            alarm.runNext()
            alarm.runNext()
            val first = world.addQuote(110, firstPage, "first drain quote")
            val second = world.addQuote(111, secondPage, "second drain quote")

            precompute.reindexed(ROOT, secondPage)
            precompute.published(world.snapshot, emptySet())
            alarm.runNext()
            alarm.runNext()

            reads shouldBe listOf("second.md", "first.md")
            world.rows.cached(ROOT, first)?.pageHash shouldBe firstPage.contentHash
            world.rows.cached(ROOT, second)?.pageHash shouldBe secondPage.contentHash
        }
    }

    test("a page reindexed during a full pass runs before the remaining full pass pages") {
        withPrecomputeWorld({ root ->
            writePage(root, "alpha.md", "# Alpha\n\nalpha quote.\n")
            writePage(root, "beta.md", "# Beta\n\nbeta quote.\n")
            writePage(root, "gamma.md", "# Gamma\n\ngamma quote.\n")
        }) { world ->
            val alpha = world.snapshot.pages.single { it.path.value == "alpha.md" }
            val beta = world.snapshot.pages.single { it.path.value == "beta.md" }
            val gamma = world.snapshot.pages.single { it.path.value == "gamma.md" }
            world.addQuote(120, alpha, "alpha quote")
            world.addQuote(121, beta, "beta quote")
            world.addQuote(122, gamma, "gamma quote")
            val reads = mutableListOf<String>()
            val content = object : ContentStore by world.content {
                override fun readClassified(path: TreePath): StoreRead =
                    world.content.readClassified(path).also { reads += path.value }
            }
            val alarm = TestAlarm()
            val precompute = world.precompute(alarm, content = content)

            precompute.published(world.snapshot, emptySet())
            alarm.runNext()
            precompute.reindexed(ROOT, gamma)
            alarm.runNext()

            reads shouldBe listOf("alpha.md", "gamma.md")
        }
    }

    test("a failing page backs off while other pages and the next full pass run immediately") {
        withPrecomputeWorld({ root ->
            writePage(root, "alpha.md", "# Alpha\n\nalpha retry quote.\n")
            writePage(root, "beta.md", "# Beta\n\nbeta retry quote.\nbeta second pass quote.\n")
        }) { world ->
            val alpha = world.snapshot.pages.single { it.path.value == "alpha.md" }
            val beta = world.snapshot.pages.single { it.path.value == "beta.md" }
            val failedId = world.addQuote(123, alpha, "alpha retry quote")
            world.addQuote(124, beta, "beta retry quote")
            val failingDiscussions = object : DiscussionStore by world.discussions {
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead =
                    if (id == failedId && only == setOf(EntryName.Marker)) {
                        EntriesRead.Failed("persistent marker failure")
                    } else {
                        world.discussions.read(root, id, only)
                    }
            }
            val reads = mutableListOf<String>()
            val content = object : ContentStore by world.content {
                override fun readClassified(path: TreePath): StoreRead =
                    world.content.readClassified(path).also { reads += path.value }
            }
            val alarm = TestAlarm()
            val precompute = world.precompute(alarm, failingDiscussions, content)

            precompute.published(world.snapshot, emptySet())
            alarm.runNext()
            alarm.runNext()

            reads shouldBe listOf("alpha.md", "beta.md")
            alarm.delays.drop(1).toSet() shouldBe setOf(1_000L, 0L)

            world.addQuote(125, beta, "beta second pass quote")
            precompute.published(world.snapshot, emptySet())
            alarm.runAll(delayMillis = 0)

            reads.count { it == "beta.md" } shouldBe 2
        }
    }

    test("a page deferred by an unsynced root is retried after recovery") {
        withPrecomputeWorld({ writePage(it, "guide.md", "# Guide\n\nunsynced quote.\n") }) { world ->
            val page = world.snapshot.pages.single()
            val id = world.addQuote(126, page, "unsynced quote")
            val reads = AtomicInteger()
            val content = object : ContentStore by world.content {
                override fun readClassified(path: TreePath): StoreRead =
                    world.content.readClassified(path).also { reads.incrementAndGet() }
            }
            val alarm = TestAlarm()
            val precompute = world.precompute(alarm, content = content)
            world.sync.enter(ROOT, "fixture unsynced")

            precompute.published(world.snapshot, emptySet())
            alarm.runNext()

            reads.get() shouldBe 0
            world.rows.cached(ROOT, id).shouldBeNull()

            val unsynced = world.sync.current(ROOT) as RootSync.Unsynced
            world.rows.writing { world.sync.clearIf(ROOT, unsynced.generation) } shouldBe true
            precompute.rootSynced(ROOT)
            alarm.runNext()

            reads.get() shouldBe 1
            world.rows.cached(ROOT, id)?.pageHash shouldBe page.contentHash
        }
    }

    test("recovery uses the current page snapshot after an unsynced full pass") {
        withPrecomputeWorld({ writePage(it, "guide.md", "# Guide\n\nrecovery quote.\n") }) { world ->
            val oldPage = world.snapshot.pages.single()
            val id = world.addQuote(145, oldPage, "recovery quote")
            val alarm = TestAlarm()
            val precompute = world.precompute(alarm)
            world.sync.enter(ROOT, "temporary index failure")
            precompute.published(world.snapshot, emptySet())
            alarm.runNext()

            Files.writeString(world.root.resolve(oldPage.path.value), "# Revised guide\n\nrecovery quote.\n")
            val currentPage = world.harness.builder.reindex(RootedPath(ROOT, oldPage.path)).pages.single()
            val unsynced = world.sync.current(ROOT) as RootSync.Unsynced
            world.rows.writing { world.sync.clearIf(ROOT, unsynced.generation) } shouldBe true
            precompute.rootSynced(ROOT)
            alarm.runNext()

            world.rows.cached(ROOT, id)?.pageHash shouldBe currentPage.contentHash
        }
    }

    test("a full pass skips an unavailable scope root") {
        withPrecomputeWorld({ writePage(it, "guide.md", "# Guide\n\nunavailable root quote.\n") }) { world ->
            val page = world.snapshot.pages.single()
            val id = world.addQuote(112, page, "unavailable root quote")
            val alarm = TestAlarm()
            val contentReads = AtomicInteger()
            val countedContent = object : ContentStore by world.content {
                override fun readClassified(path: TreePath): StoreRead =
                    world.content.readClassified(path).also { contentReads.incrementAndGet() }
            }
            val precompute = world.precompute(alarm, content = countedContent)
            world.harness.availability.markUnavailable(ROOT, UnavailableCause.VANISHED)

            precompute.published(world.snapshot, emptySet())
            alarm.runNext()

            world.rows.cached(ROOT, id).shouldBeNull()
            contentReads.get() shouldBe 0
        }
    }

    test("the precompute never skips a large page") {
        withPrecomputeWorld({ root ->
            writePage(root, "large.md", "# Large\n\n${"x".repeat(1_100_000)} needle-target\n")
        }) { world ->
            val page = world.snapshot.pages.single()
            val id = world.addQuote(106, page, "needle-target")
            val alarm = TestAlarm()
            val precompute = world.precompute(alarm)

            precompute.published(world.snapshot, emptySet())
            alarm.runNext()

            world.rows.cached(ROOT, id)?.pageHash shouldBe page.contentHash
        }
    }

    test("the precompute only touches discussions attached to the page") {
        withPrecomputeWorld({ root ->
            writePage(root, "first.md", "# First\n\nfirst page content.\n")
            writePage(root, "second.md", "# Second\n\nsecond page quote.\n")
        }) { world ->
            val firstPage = world.snapshot.pages.single { it.path.value == "first.md" }
            val secondPage = world.snapshot.pages.single { it.path.value == "second.md" }
            val id = world.addQuote(107, secondPage, "second page quote", PageRef(secondPage.id, firstPage.path))
            val alarm = TestAlarm()
            val contentReads = AtomicInteger()
            val countedContent = object : ContentStore by world.content {
                override fun readClassified(path: TreePath): StoreRead =
                    world.content.readClassified(path).also { contentReads.incrementAndGet() }
            }
            val precompute = world.precompute(alarm, content = countedContent)

            precompute.reindexed(ROOT, firstPage)
            alarm.runNext()
            world.rows.cached(ROOT, id).shouldBeNull()
            contentReads.get() shouldBe 0

            precompute.reindexed(ROOT, secondPage)
            alarm.runNext()
            world.rows.cached(ROOT, id)?.pageHash shouldBe secondPage.contentHash
            contentReads.get() shouldBe 1
        }
    }
})

private class PrecomputeWorld(
    val root: Path,
    val harness: IndexHarness,
    val snapshot: PageIndex,
    val content: ContentStore,
    val discussions: DiscussionStore,
    val rows: JdbcDiscussionRows,
    val fullReads: DiscussionFullReads,
    val sync: DiscussionSyncState,
) {
    val synced = SyncedDiscussionIndex(rows, discussions, fullReads, sync)

    fun addQuote(
        value: Int,
        page: IndexedPage,
        quote: String,
        attachedPage: PageRef = PageRef(page.id, page.path),
    ): DiscussionId {
        val id = discussionId(value)
        val raw = Files.readAllBytes(root.resolve(page.path.value))
        val text = raw.decodeToString()
        val start = text.indexOf(quote)
        check(start >= 0)
        val end = start + quote.length
        val hash = CitationFactory().contentHash(raw)
        val marker = DiscussionRecord(
            id = id,
            page = attachedPage,
            status = DiscussionStatus.OPEN,
            created = Instant.parse("2026-09-26T10:00:00.000Z"),
            startedBy = STARTER,
            statusChange = null,
            anchor = Anchor.Quote(
                hash,
                null,
                QuoteCapture.at(raw, start, end, emptyList(), AnchorSelection.NARROWED),
            ),
            reattachment = null,
            extras = FrontmatterExtras.NONE,
        )
        storeWrite(discussions.createFiles(ROOT, id, listOf(EntryPut(EntryName.Marker, DiscussionCodec.encodeDiscussion(marker)))))
        synced.publish(ROOT, id, markerChanged = false) { discussions.read(ROOT, id) }
        return id
    }

    fun precompute(
        alarm: TestAlarm,
        store: DiscussionStore = discussions,
        content: ContentStore = this.content,
        rows: DiscussionRows = this.rows,
    ): AnchorPrecompute = AnchorPrecompute(
        rows = rows,
        discussions = store,
        contents = { content },
        fullReads = if (store === discussions) fullReads else DiscussionFullReads(store),
        absence = harness.absence,
        sync = sync,
        availability = harness.availability,
        current = { harness.builder.current },
        alarm = alarm,
    )
}

private class TestAlarm(private var failFirstDelayed: Boolean = false) : RebuildScheduler.Alarm {
    private val actions = ArrayDeque<Pair<Long, () -> Unit>>()
    val delays = mutableListOf<Long>()

    override fun after(delayMillis: Long, action: () -> Unit) {
        if (delayMillis > 0 && failFirstDelayed) {
            failFirstDelayed = false
            throw IllegalStateException("fixture retry alarm failure")
        }
        delays += delayMillis
        actions.addLast(delayMillis to action)
    }

    fun runNext() {
        val task = actions.withIndex().minBy { it.value.first }
        actions.removeAt(task.index).second()
    }

    fun runAll(delayMillis: Long) {
        while (true) {
            val index = actions.indexOfFirst { it.first == delayMillis }
            if (index < 0) return
            actions.removeAt(index).second()
        }
    }
}

private class BlockingContentStore(private val delegate: ContentStore) : ContentStore by delegate {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)

    @Volatile
    private var blockedRead = false

    fun gateNextRead() {
        blockedRead = true
    }

    override fun readClassified(path: TreePath): StoreRead {
        if (blockedRead) {
            blockedRead = false
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        return delegate.readClassified(path)
    }
}

private fun withPrecomputeWorld(
    seed: (Path) -> Unit,
    wrapContent: (ContentStore) -> ContentStore = { it },
    block: (PrecomputeWorld) -> Unit,
) {
    withTempTree(seed) { root ->
        val data = Files.createTempDirectory("plainbase-precompute-data")
        val discussionRoot = Files.createTempDirectory("plainbase-precompute-discussions")
        val databasePath = data.resolve("discussions.db")
        val content = wrapContent(com.plainbase.frameworks.filesystem.LocalContentStore(root))
        val harness = IndexHarness(root, contentStore = content)
        try {
            val snapshot = harness.builder.rebuild()
            val discussions = LocalDiscussionStore(mapOf(ROOT to discussionRoot))
            DiscussionDb(databasePath).use { db ->
                val rows = JdbcDiscussionRows(db)
                val fullReads = DiscussionFullReads(discussions)
                val sync = DiscussionSyncState(setOf(ROOT))
                block(PrecomputeWorld(root, harness, snapshot, content, discussions, rows, fullReads, sync))
            }
        } finally {
            harness.close()
            discussionRoot.toFile().deleteRecursively()
            data.toFile().deleteRecursively()
        }
    }
}

private val ROOT = RootName.require("docs")
private val STARTER = Author(Actor(SubjectKey("issuer", "precompute-starter"), "Precompute"), AuthorKind.HUMAN)

private fun discussionId(value: Int): DiscussionId =
    DiscussionId.require("01900000-0000-7000-8000-${value.toString(16).padStart(12, '0')}")

private fun storeWrite(write: StoreWrite) {
    write.shouldBeInstanceOf<StoreWrite.Written>()
}
