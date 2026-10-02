package com.plainbase.frameworks.discussion

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.discussion.CollectionVisit
import com.plainbase.domain.discussion.DiscussionCodec
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionRecord
import com.plainbase.domain.discussion.DiscussionRowData
import com.plainbase.domain.discussion.DiscussionRowWriter
import com.plainbase.domain.discussion.DiscussionRows
import com.plainbase.domain.discussion.DiscussionStatus
import com.plainbase.domain.discussion.DiscussionStore
import com.plainbase.domain.discussion.EntriesRead
import com.plainbase.domain.discussion.EntryName
import com.plainbase.domain.discussion.EntryPut
import com.plainbase.domain.discussion.FrontmatterExtras
import com.plainbase.domain.discussion.RowUpdate
import com.plainbase.domain.discussion.Stamp
import com.plainbase.domain.history.CommitIdentity
import com.plainbase.domain.history.CommitOutcome
import com.plainbase.domain.history.HistoryChange
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.BreakCause
import com.plainbase.domain.root.ObservationEpoch
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootConvergence
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.domain.service.DiscussionFacts
import com.plainbase.domain.service.DiscussionFullReads
import com.plainbase.domain.service.DiscussionReparseExecutor
import com.plainbase.domain.service.DiscussionReparser
import com.plainbase.domain.service.DiscussionSyncState
import com.plainbase.domain.service.DiscussionWriteOutcome
import com.plainbase.domain.service.RebuildScheduler.Alarm
import com.plainbase.domain.service.ReparseOutcome
import com.plainbase.domain.service.ReparseRootResult
import com.plainbase.domain.service.RootSync
import com.plainbase.domain.service.RootUnavailable
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.filesystem.LocalDiscussionStore
import com.plainbase.frameworks.filesystem.rootLivenessProbe
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.scheduling.ExecutorAlarm
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightRetirementRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.time.Clock

class DiscussionReparseInterleavingTest : FunSpec({
    test("closing a blocked reparse cancels it without changing the healthy row") {
        DiscussionWorld().use { world ->
            val id = world.startDiscussion()
            val initial = requireNotNull(world.rows.row(DiscussionWorld.ROOT, id))
            val permitHeld = CountDownLatch(1)
            val releasePermit = CountDownLatch(1)
            val atStamp = CountDownLatch(1)
            val reparseThread = AtomicReference<Thread?>()
            val store = object : DiscussionStore by world.store {
                override fun stamp(root: RootName, id: DiscussionId): Stamp? {
                    reparseThread.set(Thread.currentThread())
                    atStamp.countDown()
                    return world.store.stamp(root, id)
                }
            }
            val fullReads = DiscussionFullReads(store)
            val holder = thread(isDaemon = true) {
                fullReads.withPermit {
                    permitHeld.countDown()
                    releasePermit.await(15, TimeUnit.SECONDS)
                }
            }
            val alarm = ExecutorAlarm("discussion-cancel-test")
            alarm.configureShutdownWaitForTest(50) {}
            val reparser = DiscussionReparser(setOf(DiscussionWorld.ROOT), world.rows, store, fullReads)
            val executor = DiscussionReparseExecutor(reparser, world.sync, world.availability, alarm)
            val closeDone = CountDownLatch(1)
            val closeFailure = AtomicReference<Throwable?>()
            var closer: Thread? = null
            try {
                permitHeld.await(5, TimeUnit.SECONDS) shouldBe true
                executor.discussionChanged(DiscussionWorld.ROOT, id)
                atStamp.await(5, TimeUnit.SECONDS) shouldBe true
                val worker = requireNotNull(reparseThread.get())
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (worker.state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.sleep(1)
                worker.state shouldBe Thread.State.WAITING

                closer = thread(isDaemon = true) {
                    try {
                        executor.close()
                    } catch (failure: Throwable) {
                        closeFailure.set(failure)
                    } finally {
                        closeDone.countDown()
                    }
                }
                closeDone.await(2, TimeUnit.SECONDS) shouldBe true
                closeFailure.get() shouldBe null
                world.rows.row(DiscussionWorld.ROOT, id) shouldBe initial
                world.rowObserver.applies.none { it.id == id && it.update is RowUpdate.Failed } shouldBe true
            } finally {
                releasePermit.countDown()
                holder.join(5_000)
                closer?.join(5_000)
                executor.close()
            }
        }
    }

    test("a re-parse racing a comment write never outlives the write") {
        DiscussionWorld().use { world ->
            val id = world.startDiscussion(body = "initial body\n")
            val targetId = id
            val readEntered = CountDownLatch(1)
            val releaseRead = CountDownLatch(1)
            val fullReadGate = AtomicBoolean(true)
            val store = object : DiscussionStore by world.store {
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead {
                    val result = world.store.read(root, id, only)
                    if (id == targetId && only == null && fullReadGate.compareAndSet(true, false)) {
                        readEntered.countDown()
                        check(releaseRead.await(5, TimeUnit.SECONDS))
                    }
                    return result
                }
            }
            val executor = world.executor(store)
            val reparseFailure = AtomicReference<Throwable?>()
            val reparseDone = CountDownLatch(1)
            val commitEntered = CountDownLatch(1)
            val releaseCommit = CountDownLatch(1)
            val writerDone = CountDownLatch(1)
            val writeOutcome = AtomicReference<DiscussionWriteOutcome?>()
            val writerFailure = AtomicReference<Throwable?>()
            val history = BlockingHistory(commitEntered, releaseCommit)
            executor.discussionChanged(DiscussionWorld.ROOT, id)
            val reparseRunner = thread(isDaemon = true) {
                try {
                    world.alarm.runNext(0)
                } catch (failure: Throwable) {
                    reparseFailure.set(failure)
                } finally {
                    reparseDone.countDown()
                }
            }
            readEntered.await(5, TimeUnit.SECONDS) shouldBe true
            val writerRunner = thread(isDaemon = true) {
                try {
                    writeOutcome.set(world.addComment(id, "second body\n", history))
                } catch (failure: Throwable) {
                    writerFailure.set(failure)
                } finally {
                    writerDone.countDown()
                }
            }
            try {
                commitEntered.await(5, TimeUnit.SECONDS) shouldBe true
                releaseCommit.countDown()

                writerDone.await(100, TimeUnit.MILLISECONDS) shouldBe false
            } finally {
                releaseCommit.countDown()
                releaseRead.countDown()
                reparseRunner.join(5_000)
                writerRunner.join(5_000)
                executor.close()
            }

            reparseDone.await(5, TimeUnit.SECONDS) shouldBe true
            reparseFailure.get() shouldBe null
            writerFailure.get() shouldBe null
            writeOutcome.get().shouldBeInstanceOf<DiscussionWriteOutcome.Done>().id shouldBe id
            world.rows.row(DiscussionWorld.ROOT, id)?.commentCount shouldBe 2
            (world.store.read(DiscussionWorld.ROOT, id) as EntriesRead.Present).commentCount shouldBe 2
        }
    }

    test("one unreadable discussion is isolated as failed") {
        val failedId = AtomicReference<String?>()
        val failReads = AtomicBoolean(false)
        DiscussionWorld(readEntryBytes = { path, _ ->
            if (failReads.get() && path.parent.fileName.toString() == failedId.get()) {
                throw IOException("permission denied")
            }
            Files.readAllBytes(path)
        }).use { world ->
            val id = world.startDiscussion(body = "unreadable body\n")
            val otherId = world.startDiscussionOn(
                world.otherPage,
                world.otherPageAnchor,
                "healthy body on another page\n",
            )
            failedId.set(id.value)
            failReads.set(true)
            val logger = LoggerFactory.getLogger(DiscussionReparser::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            val executor = world.executor()
            try {
                executor.discussionChanged(DiscussionWorld.ROOT, id)
                world.alarm.runNext(0)

                world.reads.facts(DiscussionWorld.ROOT, id) shouldBe DiscussionFacts.Unknown
                val listed = world.reads.rootDiscussions(DiscussionWorld.ROOT).discussions.associateBy { it.id }
                listed.getValue(id).state shouldBe "failed"
                listed.getValue(otherId).state shouldBe "ok"
                world.rows.row(DiscussionWorld.ROOT, id)?.pageId shouldBe DiscussionWorld.PAGE_ID
                world.rows.pageCount(DiscussionWorld.ROOT, DiscussionWorld.PAGE_ID) shouldBe 1
                world.rows.pageCount(DiscussionWorld.ROOT, DiscussionWorld.OTHER_PAGE_ID) shouldBe 1
                world.sync.current(DiscussionWorld.ROOT) shouldBe RootSync.Synced(0)
                val failures = appender.list.filter { it.level == Level.ERROR && id.value in it.formattedMessage }
                failures shouldHaveSize 1
                failures.single().formattedMessage.contains(DiscussionWorld.ROOT.value) shouldBe true
                failures.single().formattedMessage.contains(id.value) shouldBe true
            } finally {
                executor.close()
                logger.detachAppender(appender)
                appender.stop()
            }
        }
    }

    test("a concurrent comment write between failed reads wins before failure publish") {
        DiscussionWorld().use { world ->
            val id = world.startDiscussion(body = "first body\n")
            val targetId = id
            val failedReads = AtomicInteger()
            val injected = AtomicBoolean(false)
            val writeOutcome = AtomicReference<DiscussionWriteOutcome?>()
            val store = object : DiscussionStore by world.store {
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead =
                    if (id == targetId && only == null && failedReads.getAndIncrement() < 2) {
                        EntriesRead.Failed("retry read failed")
                    } else {
                        world.store.read(root, id, only)
                    }
            }
            val rows = object : DiscussionRows by world.rows {
                override fun <T> writing(block: DiscussionRowWriter.() -> T): T {
                    val result = world.rows.writing(block)
                    if (failedReads.get() == 2 && injected.compareAndSet(false, true)) {
                        writeOutcome.set(world.addComment(id, "concurrent comment body\n"))
                    }
                    return result
                }
            }
            val reparser = DiscussionReparser(
                setOf(DiscussionWorld.ROOT),
                rows,
                store,
                DiscussionFullReads(store),
            )

            reparser.settle(DiscussionWorld.ROOT, id).shouldBeInstanceOf<ReparseOutcome.Applied>()

            writeOutcome.get().shouldBeInstanceOf<DiscussionWriteOutcome.Done>().id shouldBe id
            world.rows.row(DiscussionWorld.ROOT, id)?.state shouldBe "ok"
            world.rows.row(DiscussionWorld.ROOT, id)?.commentCount shouldBe 2
        }
    }

    test("strict recovery re-reads every id and never trusts stamps") {
        val target = AtomicReference<String?>()
        val failingReads = AtomicBoolean(false)
        val fullReads = AtomicInteger()
        DiscussionWorld(readEntryBytes = { path, _ ->
            if (path.parent.fileName.toString() == target.get() && failingReads.get()) {
                fullReads.incrementAndGet()
                throw IOException("strict read failed")
            }
            Files.readAllBytes(path)
        }).use { world ->
            val id = world.startDiscussion(body = "strict recovery body\n")
            target.set(id.value)
            ageDiscussionEntries(world.rootPath, id, System.currentTimeMillis() - 10_000)
            world.reparser().reparseOne(DiscussionWorld.ROOT, id)
            val stamp = world.rows.row(DiscussionWorld.ROOT, id)?.stamp?.value
            stamp shouldBe requireNotNull(world.store.stamp(DiscussionWorld.ROOT, id)).value
            requireNotNull(stamp).isEmpty() shouldBe false
            failingReads.set(true)
            val executor = world.executor(recoveryBaseMillis = 50)
            try {
                executor.start()
                world.sync.enter(DiscussionWorld.ROOT, "strict recovery probe")
                world.alarm.runNext(50)

                fullReads.get() shouldBe 2
                world.rows.row(DiscussionWorld.ROOT, id)?.state shouldBe "failed"
                world.reads.facts(DiscussionWorld.ROOT, id) shouldBe DiscussionFacts.Unknown
                world.sync.isUnsynced(DiscussionWorld.ROOT) shouldBe false
            } finally {
                executor.close()
            }
        }
    }

    test("a discussions entry insert failure keeps recovery unsynced") {
        DiscussionWorld().use { world ->
            val id = world.startDiscussion(body = "entry insert recovery\n")
            val executor = world.executor(recoveryBaseMillis = 50)
            executor.start()

            DbFaults(world.databasePath).use { faults ->
                faults.failDiscussionEntryInserts()
                world.addComment(id, "trigger discussion entry insert failure")
                world.sync.isUnsynced(ROOT) shouldBe true
                world.alarm.runNext(50)

                world.sync.isUnsynced(ROOT) shouldBe true
                world.rows.row(ROOT, id)?.state shouldBe "ok"
                world.alarm.delays.last() shouldBe 100
            }
            executor.close()
        }
    }

    test("a comment added during the read invalidates the row stamp before it is deleted") {
        DiscussionWorld().use { world ->
            val id = world.startDiscussion(body = "stable starter\n")
            val directory = world.rootPath.resolve(".plainbase/discussions/${id.value}")
            val targetId = id
            val injected = AtomicBoolean(false)
            val postReadStamp = AtomicBoolean(false)
            val store = object : DiscussionStore by world.store {
                override fun stamp(root: RootName, id: DiscussionId): Stamp? =
                    if (root == ROOT && id == targetId && postReadStamp.compareAndSet(true, false)) {
                        Stamp("read-included-temporary-comment")
                    } else {
                        Stamp("stable-before-and-after")
                    }

                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead {
                    if (root == ROOT && id == targetId && only == null && injected.compareAndSet(false, true)) {
                        world.addComment(id, "temporary comment during the read\n")
                        return world.store.read(root, id, only).also { postReadStamp.set(true) }
                    }
                    return world.store.read(root, id, only)
                }
            }

            world.reparser(store).reparseOne(ROOT, id).shouldBeInstanceOf<ReparseOutcome.Applied>()
            world.rows.row(ROOT, id)?.commentCount shouldBe 2
            val temporaryComment = Files.list(directory).use { paths ->
                paths.filter { it.fileName.toString() != EntryName.Marker.fileName }.findFirst().orElseThrow()
            }
            Files.delete(temporaryComment)
            postReadStamp.set(false)

            world.reparser(store).reparseRoot(ROOT, strict = false)

            world.rows.row(ROOT, id)?.commentCount shouldBe 1
        }
    }

    test("a stamp failure in a rescan settles the id") {
        withReparseFixture { root, db, disk, id ->
            Files.createDirectories(idDirectory(root, id))
            seedRow(db, id, Stamp("old"), "last-page")
            var stampCalls = 0
            val store = object : DiscussionStore by disk {
                override fun stamp(root: RootName, id: DiscussionId): Stamp? {
                    stampCalls++
                    throw IOException("stat failed")
                }

                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead =
                    if (only?.isEmpty() == true) EntriesRead.Present(emptyList(), 0) else EntriesRead.Failed("read failed")
            }
            val reparser = reparser(db, store)

            reparser.reparseRoot(ROOT, strict = false) shouldBe ReparseRootResult.Complete(applied = false)

            stampCalls shouldBe 3
            val row = requireNotNull(JdbcDiscussionRows(db).row(ROOT, id))
            row.state shouldBe "failed"
            row.reason shouldBe "stat failed"
            row.pageId shouldBe PAGE
        }
    }

    test("a root lost during a re-parse changes no row") {
        DiscussionWorld().use { world ->
            val id = world.startDiscussion(body = "root loss body\n")
            val before = world.rows.row(DiscussionWorld.ROOT, id)
            val store = object : DiscussionStore by world.store {
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead =
                    throw RootUnavailable(root, UnavailableCause.VANISHED)
            }
            val executor = world.executor(store)
            try {
                executor.collectionChanged(DiscussionWorld.ROOT)
                world.alarm.runNext(0)

                world.rows.row(DiscussionWorld.ROOT, id) shouldBe before
                world.sync.isUnsynced(DiscussionWorld.ROOT) shouldBe true
            } finally {
                executor.close()
            }
        }
    }

    test("a tree replaced by a blank directory at runtime is root unavailable") {
        val base = Files.createTempDirectory("plainbase-discussion-blank-root")
        val root = Files.createDirectory(base.resolve("root"))
        val moved = base.resolve("moved-root")
        val availability = RootAvailability(Clock.System)
        val id = DiscussionWorld.discussionId(801)
        val store = LocalDiscussionStore(
            roots = mapOf(DiscussionWorld.ROOT to root),
            probeRoot = rootLivenessProbe(root),
            onRootUnavailable = { availability.markUnavailable(DiscussionWorld.ROOT, UnavailableCause.VANISHED) },
        )
        try {
            Files.write(root.resolve("guide.md"), "a populated tree".encodeToByteArray())
            store.createFiles(
                DiscussionWorld.ROOT,
                id,
                listOf(EntryPut(EntryName.Marker, "marker".encodeToByteArray())),
            )
            Files.move(root, moved)
            Files.createDirectory(root)

            shouldThrow<RootUnavailable> { store.visit(DiscussionWorld.ROOT) { _, _ -> error("must not list empty") } }

            availability.current().isAvailable(DiscussionWorld.ROOT) shouldBe false
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    test("discussion probes never break the observation epoch") {
        val base = Files.createTempDirectory("plainbase-discussion-probe-epoch")
        val root = Files.createDirectory(base.resolve("root"))
        val replacement = Files.createDirectory(base.resolve("replacement"))
        val moved = base.resolve("moved-root")
        val driver = DatabaseFactory.createInMemoryDriver()
        val database = DatabaseFactory.createDatabase(driver)
        try {
            Files.write(root.resolve("before.md"), "old tree".encodeToByteArray())
            val retirements = SqlDelightRetirementRepository(database)
            val epoch = ObservationEpoch(retirements, RootConvergence())
            epoch.observing(DiscussionWorld.ROOT)
            val before = retirements.observation(DiscussionWorld.ROOT)
            val content = LocalContentStore(
                root = root,
                rootName = DiscussionWorld.ROOT,
                onIdentityRebind = { epoch.broke(DiscussionWorld.ROOT, BreakCause.IDENTITY_REBIND) },
            )
            val discussionProbe = rootLivenessProbe(root)
            val id = DiscussionWorld.discussionId(802)
            Files.write(replacement.resolve("after.md"), "replacement corpus".encodeToByteArray())
            Files.createDirectories(replacement.resolve(".plainbase/discussions").resolve(id.value))
            val store = LocalDiscussionStore(
                roots = mapOf(DiscussionWorld.ROOT to root),
                probeRoot = { path -> discussionProbe(path) },
            )
            Files.move(root, moved)
            Files.move(replacement, root)
            val seen = mutableListOf<DiscussionId>()

            store.visit(DiscussionWorld.ROOT) { found, _ -> seen += found }.shouldBeInstanceOf<CollectionVisit.Visited>()

            seen shouldBe listOf(id)
            retirements.observation(DiscussionWorld.ROOT) shouldBe before
        } finally {
            driver.close()
            base.toFile().deleteRecursively()
        }
    }

    test("a racy stamp is stored null and re-parsed on the next rescan") {
        DiscussionWorld().use { world ->
            val first = world.startDiscussion("past racy body\n", OffsetHistory(world.rootPath, -1_500))
            val second = world.startDiscussion("future racy body\n", OffsetHistory(world.rootPath, 1_500))
            val firstStamp = requireNotNull(world.store.stamp(DiscussionWorld.ROOT, first))
            val secondStamp = requireNotNull(world.store.stamp(DiscussionWorld.ROOT, second))
            firstStamp.racy shouldBe true
            secondStamp.racy shouldBe true
            world.rows.row(DiscussionWorld.ROOT, first)?.stamp shouldBe null
            world.rows.row(DiscussionWorld.ROOT, second)?.stamp shouldBe null

            val counts = mutableMapOf<DiscussionId, Int>()
            val store = countingStore(world, counts)
            val executor = world.executor(store)
            try {
                executor.collectionChanged(DiscussionWorld.ROOT)
                world.alarm.runNext(0)

                counts shouldBe mapOf(first to 1, second to 1)
                world.rows.row(DiscussionWorld.ROOT, first)?.stamp shouldBe null
                world.rows.row(DiscussionWorld.ROOT, second)?.stamp shouldBe null
            } finally {
                executor.close()
            }
        }
    }

    test("a symlinked discussion stamps stably and reparses once across two rescans") {
        DiscussionWorld().use { world ->
            val id = world.startDiscussion("symlink body\n")
            val idPath = idDirectory(world.rootPath, id)
            Files.setLastModifiedTime(
                idPath.resolve(EntryName.Marker.fileName),
                FileTime.fromMillis(System.currentTimeMillis() - 10_000),
            )
            val entry = Files.newDirectoryStream(idPath).use { stream ->
                stream.first { it.fileName.toString() != EntryName.Marker.fileName }
            }
            val target = Files.createTempFile("pb-discussion-symlink-entry", ".md")
            try {
            Files.delete(entry)
            Files.createSymbolicLink(entry, target)
            Files.setLastModifiedTime(idPath, FileTime.fromMillis(System.currentTimeMillis() - 10_000))
            val stamp = requireNotNull(world.store.stamp(DiscussionWorld.ROOT, id))
                stamp.value.startsWith("sha256:") shouldBe true
                world.rows.row(DiscussionWorld.ROOT, id)?.stamp shouldBe null
                val counts = mutableMapOf<DiscussionId, Int>()
                val executor = world.executor(countingStore(world, counts))
                try {
                    executor.collectionChanged(DiscussionWorld.ROOT)
                    world.alarm.runNext(0)
                    world.rows.row(DiscussionWorld.ROOT, id)?.stamp?.value shouldBe stamp.value
                    world.rows.row(DiscussionWorld.ROOT, id)?.state shouldBe "unreadable"

                    executor.collectionChanged(DiscussionWorld.ROOT)
                    world.alarm.runNext(0)

                    world.store.stamp(DiscussionWorld.ROOT, id) shouldBe stamp
                    counts[id] shouldBe 1
                } finally {
                    executor.close()
                }
            } finally {
                Files.deleteIfExists(entry)
                Files.deleteIfExists(target)
            }
        }
    }

    test("a mid create read writes incomplete then ends ok") {
        DiscussionWorld().use { world ->
            val id = DiscussionWorld.discussionId(701)
            val directory = idDirectory(world.rootPath, id)
            Files.createDirectories(directory)
            val reparser = world.reparser()

            reparser.reparseOne(DiscussionWorld.ROOT, id).shouldBeInstanceOf<ReparseOutcome.Applied>().state shouldBe "incomplete"
            world.rows.row(DiscussionWorld.ROOT, id)?.state shouldBe "incomplete"

            val marker = DiscussionRecord(
                id,
                world.page,
                DiscussionStatus.OPEN,
                kotlin.time.Instant.parse("2026-09-26T10:01:00Z"),
                world.actor,
                null,
                world.pageAnchor,
                null,
                FrontmatterExtras.NONE,
            )
            Files.write(directory.resolve(EntryName.Marker.fileName), DiscussionCodec.encodeDiscussion(marker))
            reparser.reparseOne(DiscussionWorld.ROOT, id).shouldBeInstanceOf<ReparseOutcome.Applied>().state shouldBe "ok"
            world.rows.row(DiscussionWorld.ROOT, id)?.state shouldBe "ok"
        }
    }

    test("a re-parse during a refused commit is corrected by the concurrent write") {
        DiscussionWorld().use { world ->
            val id = world.startDiscussion("committed body\n")
            val commitEntered = CountDownLatch(1)
            val releaseCommit = CountDownLatch(1)
            val outcome = AtomicReference<DiscussionWriteOutcome?>()
            val writerFailure = AtomicReference<Throwable?>()
            val history = RefusedHistory(commitEntered, releaseCommit)
            world.rowObserver.clear()
            val writerRunner = thread(isDaemon = true) {
                try {
                    outcome.set(world.addComment(id, "refused body\n", history))
                } catch (failure: Throwable) {
                    writerFailure.set(failure)
                }
            }
            try {
                commitEntered.await(5, TimeUnit.SECONDS) shouldBe true
                val executor = world.executor()
                try {
                    executor.collectionChanged(DiscussionWorld.ROOT)
                    world.alarm.runNext(0)
                    world.rows.row(DiscussionWorld.ROOT, id)?.commentCount shouldBe 2
                } finally {
                    executor.close()
                }
                releaseCommit.countDown()
                writerRunner.join(5_000)

                writerFailure.get() shouldBe null
                outcome.get().shouldBeInstanceOf<DiscussionWriteOutcome.Refused>().refusal.code shouldBe "discussion_commit_failed"
                world.rows.row(DiscussionWorld.ROOT, id)?.commentCount shouldBe 1
                (world.store.read(DiscussionWorld.ROOT, id) as EntriesRead.Present).commentCount shouldBe 1
                world.rows.entry(DiscussionWorld.ROOT, id, EntryName.Comment(DiscussionWorld.commentId(102))) shouldBe null
                Files.exists(
                    idDirectory(world.rootPath, id).resolve(EntryName.Comment(DiscussionWorld.commentId(102)).fileName),
                ) shouldBe false
                val appliedCounts = world.rowObserver.applies
                    .filter { it.root == DiscussionWorld.ROOT && it.id == id }
                    .mapNotNull { (it.update as? RowUpdate.Upsert)?.row?.commentCount }
                appliedCounts shouldBe listOf(2, 1)
            } finally {
                releaseCommit.countDown()
                writerRunner.join(5_000)
            }
        }
    }

    test("a failed row heals on the next tick") {
        val failingId = AtomicReference<String?>()
        val failReads = AtomicBoolean(false)
        DiscussionWorld(readEntryBytes = { path, _ ->
            if (failReads.get() && path.parent.fileName.toString() == failingId.get()) {
                throw IOException("temporary read failure")
            }
            Files.readAllBytes(path)
        }).use { world ->
            val id = world.startDiscussion("healing body\n")
            failingId.set(id.value)
            failReads.set(true)
            val executor = world.executor()
            try {
                executor.discussionChanged(DiscussionWorld.ROOT, id)
                world.alarm.runNext(0)
                world.rows.row(DiscussionWorld.ROOT, id)?.state shouldBe "failed"
                world.rows.row(DiscussionWorld.ROOT, id)?.stamp shouldBe null

                failReads.set(false)
                executor.collectionChanged(DiscussionWorld.ROOT)
                world.alarm.runNext(0)

                world.rows.row(DiscussionWorld.ROOT, id)?.state shouldBe "ok"
                world.reads.facts(DiscussionWorld.ROOT, id).shouldBeInstanceOf<DiscussionFacts.Known>().state shouldBe "ok"
            } finally {
                executor.close()
            }
        }
    }

    test("a rescan reparses only discussions whose stamp changed") {
        DiscussionWorld().use { world ->
            val changed = world.startDiscussion("changed discussion\n")
            val unchanged = world.startDiscussion("unchanged discussion\n")
            ageDiscussionEntries(world.rootPath, changed, System.currentTimeMillis() - 10_000)
            ageDiscussionEntries(world.rootPath, unchanged, System.currentTimeMillis() - 10_000)
            world.reparser().reparseOne(DiscussionWorld.ROOT, changed)
            world.reparser().reparseOne(DiscussionWorld.ROOT, unchanged)
            val changedStamp = world.rows.row(DiscussionWorld.ROOT, changed)?.stamp?.value
            val unchangedStamp = world.rows.row(DiscussionWorld.ROOT, unchanged)?.stamp?.value
            changedStamp shouldBe world.store.stamp(DiscussionWorld.ROOT, changed)?.value
            unchangedStamp shouldBe world.store.stamp(DiscussionWorld.ROOT, unchanged)?.value
            requireNotNull(changedStamp).isEmpty() shouldBe false
            requireNotNull(unchangedStamp).isEmpty() shouldBe false
            val marker = idDirectory(world.rootPath, changed).resolve(EntryName.Marker.fileName)
            Files.setLastModifiedTime(marker, FileTime.fromMillis(System.currentTimeMillis() - 9_000))
            world.store.stamp(DiscussionWorld.ROOT, changed)?.value shouldNotBe changedStamp

            val counts = mutableMapOf<DiscussionId, Int>()
            val executor = world.executor(countingStore(world, counts))
            try {
                executor.collectionChanged(DiscussionWorld.ROOT)
                world.alarm.runNext(0)

                counts shouldBe mapOf(changed to 1)
            } finally {
                executor.close()
            }
        }
    }

    test("a stamp taken before the read never hides a later change") {
        DiscussionWorld().use { world ->
            val id = world.startDiscussion("pre-read stamp body\n")
            ageDiscussionEntries(world.rootPath, id, System.currentTimeMillis() - 10_000)
            world.reparser().reparseOne(DiscussionWorld.ROOT, id)
            val readCount = AtomicInteger()
            val touched = AtomicBoolean(false)
            val store = object : DiscussionStore by world.store {
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead {
                    val result = world.store.read(root, id, only)
                    if (only == null) {
                        readCount.incrementAndGet()
                        if (touched.compareAndSet(false, true)) {
                            val marker = idDirectory(world.rootPath, id).resolve(EntryName.Marker.fileName)
                            Files.setLastModifiedTime(marker, FileTime.fromMillis(System.currentTimeMillis() - 9_000))
                        }
                    }
                    return result
                }
            }
            val reparser = world.reparser(store)

            reparser.reparseOne(DiscussionWorld.ROOT, id)
            world.rows.row(DiscussionWorld.ROOT, id)?.stamp?.value shouldNotBe world.store.stamp(DiscussionWorld.ROOT, id)?.value
            readCount.get() shouldBe 1
            reparser.reparseRoot(DiscussionWorld.ROOT, strict = false) shouldBe ReparseRootResult.Complete(applied = true)
            readCount.get() shouldBe 2
        }
    }

    test("a directory recreated before the lock is never deleted") {
        DiscussionWorld().use { world ->
            val id = world.startDiscussion("directory recreation body\n")
            val directory = idDirectory(world.rootPath, id)
            directory.toFile().deleteRecursively()
            val recreated = AtomicBoolean(false)
            val store = object : DiscussionStore by world.store {
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead {
                    val result = world.store.read(root, id, only)
                    if (only?.isEmpty() == true && result == EntriesRead.Absent && recreated.compareAndSet(false, true)) {
                        Files.createDirectories(directory)
                    }
                    return result
                }
            }
            val executor = world.executor(store)
            try {
                executor.collectionChanged(DiscussionWorld.ROOT)
                world.alarm.runNext(0)

                Files.isDirectory(directory) shouldBe true
                world.rows.row(DiscussionWorld.ROOT, id)?.state shouldBe "incomplete"
            } finally {
                executor.close()
            }
        }
    }

    test("a symlinked collection enters cause symlink at runtime") {
        withReparseFixture { root, db, disk, _ ->
            val target = Files.createTempDirectory("pb-discussion-symlink-target")
            val collection = root.resolve(".plainbase/discussions")
            Files.createDirectories(collection.parent)
            Files.createSymbolicLink(collection, target)
            val alarm = ManualDiscussionAlarm()
            val sync = DiscussionSyncState(setOf(ROOT))
            val executor = DiscussionReparseExecutor(reparser(db, disk), sync, RootAvailability(Clock.System), alarm)
            try {
                executor.collectionChanged(ROOT)
                alarm.runNext()

                sync.current(ROOT).shouldBeInstanceOf<RootSync.Unsynced>().cause shouldBe "symlink"
            } finally {
                executor.close()
                target.toFile().deleteRecursively()
            }
        }
    }

    test("a re-parse reads under the row lock") {
        withReparseFixture { root, db, disk, id ->
            Files.createDirectories(idDirectory(root, id))
            val store = object : DiscussionStore by disk {
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead {
                    if (only == null) db.writeLock.isHeldByCurrentThread shouldBe true
                    return EntriesRead.Present(emptyList(), 0)
                }
            }

            val result = reparser(db, store).reparseOne(ROOT, id)

            result.shouldBeInstanceOf<ReparseOutcome.Applied>().state shouldBe "incomplete"
        }
    }

    test("a present pass two probe is never a delete") {
        withReparseFixture { _, db, disk, id ->
            seedRow(db, id, Stamp("old"), "last-page")
            var fullReads = 0
            val store = object : DiscussionStore by disk {
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead {
                    if (only == null) fullReads++
                    return EntriesRead.Present(emptyList(), 0)
                }
            }

            reparser(db, store).reparseRoot(ROOT, strict = true) shouldBe ReparseRootResult.Complete(applied = false)

            JdbcDiscussionRows(db).row(ROOT, id)?.state shouldBe "ok"
            fullReads shouldBe 0
        }
    }

    test("root rescans are single flight with one dirty rerun") {
        withReparseFixture { _, db, disk, _ ->
            val visitEntered = CountDownLatch(1)
            val releaseVisit = CountDownLatch(1)
            var visits = 0
            val store = object : DiscussionStore by disk {
                override fun visit(root: RootName, visitor: (DiscussionId, Boolean) -> Unit): CollectionVisit {
                    visits++
                    if (visits == 1) {
                        visitEntered.countDown()
                        check(releaseVisit.await(5, TimeUnit.SECONDS))
                    }
                    return CollectionVisit.Visited(0)
                }
            }
            val alarm = ManualDiscussionAlarm()
            val executor = DiscussionReparseExecutor(
                reparser(db, store),
                DiscussionSyncState(setOf(ROOT)),
                RootAvailability(Clock.System),
                alarm,
            )
            executor.collectionChanged(ROOT)
            val runner = thread { alarm.runNext() }
            try {
                visitEntered.await(5, TimeUnit.SECONDS) shouldBe true
                executor.collectionChanged(ROOT)
                executor.collectionChanged(ROOT)
            } finally {
                releaseVisit.countDown()
                runner.join(5_000)
            }

            alarm.runNext()

            visits shouldBe 2
            executor.close()
        }
    }

    test("events past the id bound leave a bounded alarm queue while the worker is blocked") {
        DiscussionWorld().use { world ->
            val targetId = world.startDiscussion(body = "blocked drain target\n")
            val readEntered = CountDownLatch(1)
            val releaseRead = CountDownLatch(1)
            val blockOnce = AtomicBoolean(true)
            val store = object : DiscussionStore by world.store {
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead {
                    if (id == targetId && only == null && blockOnce.compareAndSet(true, false)) {
                        readEntered.countDown()
                        check(releaseRead.await(5, TimeUnit.SECONDS))
                    }
                    return world.store.read(root, id, only)
                }
            }
            val executor = world.executor(store)
            executor.discussionChanged(ROOT, targetId)
            val worker = thread(isDaemon = true) { world.alarm.runNext(0) }
            try {
                readEntered.await(5, TimeUnit.SECONDS) shouldBe true
                repeat(DiscussionReparseExecutor.MAX_PENDING_ONES * 3) { index ->
                    val value = "01900000-0000-7000-8000-${(index + 10_000).toString(16).padStart(12, '0')}"
                    executor.discussionChanged(ROOT, DiscussionId.require(value))
                }

                world.alarm.pendingImmediateCount shouldBeLessThan 4
            } finally {
                releaseRead.countDown()
                worker.join(5_000)
            }
            worker.isAlive shouldBe false
            executor.close()
        }
    }

    test("pending delayed checks past the bound collapse to one root check") {
        withReparseFixture { _, db, disk, _ ->
            val alarm = ManualDiscussionAlarm()
            val reads = AtomicInteger()
            val store = object : DiscussionStore by disk {
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead {
                    reads.incrementAndGet()
                    return disk.read(root, id, only)
                }
            }
            val executor = DiscussionReparseExecutor(
                reparser(db, store),
                DiscussionSyncState(setOf(ROOT)),
                RootAvailability(Clock.System),
                alarm,
                checkDelayMillis = 50,
            )
            repeat(DiscussionReparseExecutor.MAX_PENDING_ONES + 1) { index ->
                val idValue = "01900000-0000-7000-8000-${(index + 10_000).toString(16).padStart(12, '0')}"
                executor.discussionChanged(ROOT, DiscussionId.require(idValue))
            }

            alarm.delays.count { it == 50L } shouldBe 1
            alarm.runAll()
            reads.get() shouldBeLessThan DiscussionReparseExecutor.MAX_PENDING_ONES
            executor.close()
        }
    }

    test("an id added late waits for its own delayed check") {
        withReparseFixture { _, db, disk, firstId ->
            val lateId = DiscussionWorld.discussionId(902)
            val reads = mutableMapOf<DiscussionId, Int>()
            val store = object : DiscussionStore by disk {
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead {
                    if (only == null) reads[id] = (reads[id] ?: 0) + 1
                    return disk.read(root, id, only)
                }
            }
            val alarm = ManualDiscussionAlarm()
            val executor = DiscussionReparseExecutor(
                reparser(db, store),
                DiscussionSyncState(setOf(ROOT)),
                RootAvailability(Clock.System),
                alarm,
                checkDelayMillis = 200,
            )
            executor.discussionChanged(ROOT, firstId)
            alarm.runNext(delayMillis = 0)
            Thread.sleep(100)
            executor.discussionChanged(ROOT, lateId)
            alarm.runNext(delayMillis = 0)

            alarm.runNext(delayMillis = 200)
            reads[lateId] shouldBe 1
            alarm.runNext()
            reads[lateId] shouldBe 2
            executor.close()
        }
    }

    test("a repeat event moves that id's delayed check") {
        withReparseFixture { _, db, disk, id ->
            var fullReads = 0
            val store = object : DiscussionStore by disk {
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead {
                    if (only == null) fullReads++
                    return disk.read(root, id, only)
                }
            }
            val alarm = ManualDiscussionAlarm()
            val executor = DiscussionReparseExecutor(
                reparser(db, store),
                DiscussionSyncState(setOf(ROOT)),
                RootAvailability(Clock.System),
                alarm,
                checkDelayMillis = 200,
            )
            executor.discussionChanged(ROOT, id)
            alarm.runNext(delayMillis = 0)
            Thread.sleep(100)
            executor.discussionChanged(ROOT, id)
            alarm.runNext(delayMillis = 0)

            alarm.runNext(delayMillis = 200)
            fullReads shouldBe 2
            alarm.runNext()
            fullReads shouldBe 3
            executor.close()
        }
    }

    test("a failed recovery schedule can be armed by a later enter") {
        withReparseFixture { _, db, disk, _ ->
            var failFirst = true
            val scheduled = mutableListOf<() -> Unit>()
            val alarm = Alarm { _, action ->
                if (failFirst) {
                    failFirst = false
                    throw IllegalStateException("fixture scheduling failure")
                }
                scheduled += action
            }
            val sync = DiscussionSyncState(setOf(ROOT))
            val executor = DiscussionReparseExecutor(reparser(db, disk), sync, RootAvailability(Clock.System), alarm)
            executor.start()

            sync.enter(ROOT, "first failure")
            sync.enter(ROOT, "second failure")

            scheduled.size shouldBe 1
            executor.close()
        }
    }

    test("a failed immediate schedule can be retried for one id and a root") {
        DiscussionWorld().use { world ->
            val id = world.startDiscussion(body = "schedule retry\n")
            val oneActions = mutableListOf<Pair<Long, () -> Unit>>()
            var failOneSchedule = true
            val oneAlarm = Alarm { delay, action ->
                if (failOneSchedule) {
                    failOneSchedule = false
                    throw IllegalStateException("first id schedule refused")
                }
                oneActions += delay to action
            }
            val oneExecutor = DiscussionReparseExecutor(
                world.reparser(),
                world.sync,
                world.availability,
                oneAlarm,
                checkDelayMillis = 50,
            )
            oneExecutor.discussionChanged(ROOT, id)
            oneExecutor.discussionChanged(ROOT, id)
            oneActions.count { it.first == 0L } shouldBe 1
            oneExecutor.close()

            val rootActions = mutableListOf<Pair<Long, () -> Unit>>()
            var failRootSchedule = true
            val rootAlarm = Alarm { delay, action ->
                if (failRootSchedule) {
                    failRootSchedule = false
                    throw IllegalStateException("first root schedule refused")
                }
                rootActions += delay to action
            }
            val rootExecutor = DiscussionReparseExecutor(
                world.reparser(),
                world.sync,
                world.availability,
                rootAlarm,
            )
            rootExecutor.collectionChanged(ROOT)
            rootExecutor.collectionChanged(ROOT)
            rootActions.count { it.first == 0L } shouldBe 1
            rootExecutor.close()
        }
    }

    test("a recovery timer that fires during scheduling is re-posted") {
        DiscussionWorld().use { world ->
            val queued = mutableListOf<Pair<Long, () -> Unit>>()
            val alarm = Alarm { delay, action ->
                if (delay == 50L) action() else queued += delay to action
            }
            val executor = DiscussionReparseExecutor(
                world.reparser(),
                world.sync,
                world.availability,
                alarm,
                recoveryBaseMillis = 50,
            )
            executor.start()
            world.sync.enter(ROOT, "recovery schedule race")

            world.sync.isUnsynced(ROOT) shouldBe true
            queued.map { it.first } shouldBe listOf(0L)
            queued.single().second()
            world.sync.isUnsynced(ROOT) shouldBe false
            executor.close()
        }
    }

    test("a recorded failed row does not reset recovery backoff") {
        DiscussionWorld().use { world ->
            val id = world.startDiscussion(body = "recovery backoff marker\n")
            var fullReads = 0
            val store = object : DiscussionStore by world.store {
                override fun visit(root: RootName, visitor: (DiscussionId, Boolean) -> Unit): CollectionVisit =
                    CollectionVisit.Failed("collection still unavailable")

                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead {
                    if (only == null) {
                        fullReads++
                        return EntriesRead.Failed("per id read still unavailable")
                    }
                    return world.store.read(root, id, only)
                }
            }
            val alarm = ManualDiscussionAlarm()
            val executor = DiscussionReparseExecutor(
                world.reparser(store),
                world.sync,
                world.availability,
                alarm,
                checkDelayMillis = 50,
                recoveryBaseMillis = 50,
            )
            executor.start()
            world.sync.enter(ROOT, "initial recovery failure")
            alarm.runNext(50)

            executor.discussionChanged(ROOT, id)
            alarm.runNext(50)
            fullReads shouldBe 2
            alarm.runNext(100)

            alarm.delays.last() shouldBe 200
            executor.close()
        }
    }

    test("folder events schedule delayed checks for each discussion id") {
        withReparseFixture { root, db, disk, id ->
            val otherId = DiscussionWorld.discussionId(903)
            Files.createDirectories(idDirectory(root, id))
            Files.createDirectories(idDirectory(root, otherId))
            var visits = 0
            val fullReads = mutableMapOf<DiscussionId, Int>()
            val store = object : DiscussionStore by disk {
                override fun visit(root: RootName, visitor: (DiscussionId, Boolean) -> Unit): CollectionVisit {
                    visits++
                    return disk.visit(root, visitor)
                }

                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead {
                    if (only == null) fullReads[id] = (fullReads[id] ?: 0) + 1
                    return disk.read(root, id, only)
                }
            }
            val alarm = ManualDiscussionAlarm()
            val executor = DiscussionReparseExecutor(
                reparser(db, store),
                DiscussionSyncState(setOf(ROOT)),
                RootAvailability(Clock.System),
                alarm,
                checkDelayMillis = 50,
            )
            try {
                executor.discussionChanged(ROOT, id)
                alarm.runNext(0)
                Thread.sleep(25)
                executor.discussionChanged(ROOT, id)
                alarm.runNext(0)
                Thread.sleep(25)
                executor.discussionChanged(ROOT, otherId)
                alarm.runNext(0)

                alarm.runAll()

                fullReads[id] shouldBe 3
                fullReads[otherId] shouldBe 2
                visits shouldBe 0
            } finally {
                executor.close()
            }
        }
    }

    test("a comment created and deleted within one directory timestamp tick is corrected by the next rescan") {
        DiscussionWorld().use { world ->
            val id = world.startDiscussion(body = "surviving comment\n")
            val targetId = id
            val directory = idDirectory(world.rootPath, id)
            val commentsBefore = commentFileNames(directory)
            world.addComment(id, "transient comment\n").shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
            val transientName = (commentFileNames(directory) - commentsBefore).single()
            val transientPath = directory.resolve(transientName)
            val transientBytes = Files.readAllBytes(transientPath)
            Files.delete(transientPath)

            val aged = FileTime.fromMillis(System.currentTimeMillis() - 10_000)
            Files.newDirectoryStream(directory).use { entries ->
                entries.forEach { entry -> Files.setLastModifiedTime(entry, aged) }
            }
            Files.setLastModifiedTime(directory, aged)
            world.reparser().reparseOne(ROOT, id).shouldBeInstanceOf<ReparseOutcome.Applied>().state shouldBe "ok"
            world.rows.row(ROOT, id)?.commentCount shouldBe 1
            world.rows.row(ROOT, id)?.stamp shouldBe world.store.stamp(ROOT, id)

            val coarse = FileTime.fromMillis(System.currentTimeMillis() - 500)
            Files.setLastModifiedTime(directory, coarse)
            val beforeReadStamp = requireNotNull(world.store.stamp(ROOT, id))
            beforeReadStamp.racy shouldBe true

            val injectTransientComment = AtomicBoolean(true)
            val store = object : DiscussionStore by world.store {
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead {
                    if (
                        root == ROOT && id == targetId && only == null &&
                        injectTransientComment.compareAndSet(true, false)
                    ) {
                        Files.write(transientPath, transientBytes)
                        Files.setLastModifiedTime(transientPath, aged)
                        Files.setLastModifiedTime(directory, coarse)
                        return try {
                            world.store.read(root, id, only)
                        } finally {
                            Files.deleteIfExists(transientPath)
                            Files.setLastModifiedTime(directory, coarse)
                        }
                    }
                    return world.store.read(root, id, only)
                }
            }
            val reparser = world.reparser(store)

            val applied = reparser.reparseOne(ROOT, id).shouldBeInstanceOf<ReparseOutcome.Applied>()
            applied.state shouldBe "ok"
            applied.stamp shouldBe beforeReadStamp
            world.store.stamp(ROOT, id) shouldBe beforeReadStamp
            world.rows.row(ROOT, id)?.commentCount shouldBe 2
            world.rows.row(ROOT, id)?.stamp shouldBe null

            reparser.reparseRoot(ROOT, strict = false).shouldBeInstanceOf<ReparseRootResult.Complete>()
            world.rows.row(ROOT, id)?.commentCount shouldBe 1
            injectTransientComment.get() shouldBe false
        }
    }

    test("a failed recovery retries with backoff then clears") {
        withReparseFixture { _, db, disk, _ ->
            var visits = 0
            val store = object : DiscussionStore by disk {
                override fun visit(root: RootName, visitor: (DiscussionId, Boolean) -> Unit): CollectionVisit =
                    if (++visits < 3) CollectionVisit.Failed("temporary") else CollectionVisit.Visited(0)
            }
            val alarm = ManualDiscussionAlarm()
            val sync = DiscussionSyncState(setOf(ROOT))
            val executor = DiscussionReparseExecutor(
                reparser(db, store),
                sync,
                RootAvailability(Clock.System),
                alarm,
                recoveryBaseMillis = 50,
            )
            executor.start()
            sync.enter(ROOT, "test failure")

            alarm.runNext()
            alarm.runNext()
            alarm.runNext()

            alarm.delays shouldBe listOf(50, 100, 200)
            sync.isUnsynced(ROOT) shouldBe false
            executor.close()
        }
    }

    test("an enter after a successful clear is never lost") {
        withReparseFixture { _, db, disk, _ ->
            val alarm = ManualDiscussionAlarm()
            val sync = DiscussionSyncState(setOf(ROOT))
            var afterCalls = 0
            val executor = DiscussionReparseExecutor(
                reparser(db, disk),
                sync,
                RootAvailability(Clock.System),
                alarm,
                recoveryBaseMillis = 50,
                afterClear = {
                    if (++afterCalls == 1) sync.enter(ROOT, "arrived after clear")
                },
            )
            executor.start()
            sync.enter(ROOT, "test failure")

            alarm.runNext()
            sync.isUnsynced(ROOT) shouldBe true
            alarm.runNext()

            sync.isUnsynced(ROOT) shouldBe false
            afterCalls shouldBe 2
            executor.close()
        }
    }

    test("after recovery clears then becomes unsynced, the next delay is the base delay") {
        withReparseFixture { _, db, disk, _ ->
            val alarm = ManualDiscussionAlarm()
            val sync = DiscussionSyncState(setOf(ROOT))
            var afterCalls = 0
            val executor = DiscussionReparseExecutor(
                reparser(db, disk),
                sync,
                RootAvailability(Clock.System),
                alarm,
                recoveryBaseMillis = 50,
                afterClear = {
                    if (++afterCalls == 1) sync.enter(ROOT, "comment write remains failing")
                },
            )
            executor.start()
            sync.enter(ROOT, "test failure")

            alarm.runNext()

            sync.isUnsynced(ROOT) shouldBe true
            alarm.delays shouldBe listOf(50, 50)
            executor.close()
        }
    }

    test("afterClear is skipped when a newer generation prevents clear") {
        withReparseFixture { _, db, disk, _ ->
            val alarm = ManualDiscussionAlarm()
            val sync = DiscussionSyncState(setOf(ROOT))
            var afterCalls = 0
            val executor = DiscussionReparseExecutor(
                reparser(db, disk),
                sync,
                RootAvailability(Clock.System),
                alarm,
                recoveryBaseMillis = 50,
                beforeClear = { sync.enter(ROOT, "newer recovery generation") },
                afterClear = { afterCalls++ },
            )
            executor.start()
            sync.enter(ROOT, "initial failure")

            alarm.runNext()

            sync.isUnsynced(ROOT) shouldBe true
            afterCalls shouldBe 0
            executor.close()
        }
    }

    test("a run that finds the root already synced resets the backoff") {
        withReparseFixture { _, db, disk, _ ->
            val rows = JdbcDiscussionRows(db)
            val alarm = ManualDiscussionAlarm()
            val sync = DiscussionSyncState(setOf(ROOT))
            val executor = DiscussionReparseExecutor(reparser(db, disk), sync, RootAvailability(Clock.System), alarm)
            executor.start()
            sync.enter(ROOT, "test failure")
            rows.writing { sync.clearIf(ROOT, 1) } shouldBe true

            alarm.runNext()
            sync.current(ROOT) shouldBe RootSync.Synced(1)
            sync.enter(ROOT, "new failure")

            alarm.delays shouldBe listOf(1_000, 1_000)
            executor.close()
        }
    }

    test("an apply during a root rescan resets the backoff") {
        withReparseFixture { root, db, disk, id ->
            Files.createDirectories(idDirectory(root, id))
            var visits = 0
            val store = object : DiscussionStore by disk {
                override fun visit(root: RootName, visitor: (DiscussionId, Boolean) -> Unit): CollectionVisit =
                    when (++visits) {
                        1, 3 -> CollectionVisit.Failed("temporary")
                        else -> {
                            visitor(id, false)
                            CollectionVisit.Visited(1)
                        }
                    }
            }
            val alarm = ManualDiscussionAlarm()
            val sync = DiscussionSyncState(setOf(ROOT))
            val executor = DiscussionReparseExecutor(
                reparser(db, store),
                sync,
                RootAvailability(Clock.System),
                alarm,
                recoveryBaseMillis = 50,
            )
            executor.start()
            sync.enter(ROOT, "test failure")
            alarm.runNext()

            executor.collectionChanged(ROOT)
            alarm.runAll(delayMillis = 0)

            JdbcDiscussionRows(db).row(ROOT, id)?.state shouldBe "incomplete"
            alarm.runNext()

            alarm.delays.last() shouldBe 100
            executor.close()
        }
    }

    test("a failed row found during the root visit keeps recovery backoff") {
        withReparseFixture { root, db, disk, id ->
            Files.createDirectories(idDirectory(root, id))
            var visits = 0
            val store = object : DiscussionStore by disk {
                override fun visit(root: RootName, visitor: (DiscussionId, Boolean) -> Unit): CollectionVisit =
                    when (++visits) {
                        1, 3 -> CollectionVisit.Failed("temporary")
                        else -> {
                            visitor(id, false)
                            CollectionVisit.Visited(1)
                        }
                    }

                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead =
                    if (id == ID && only == null) EntriesRead.Failed("discussion read failed") else disk.read(root, id, only)
            }
            val alarm = ManualDiscussionAlarm()
            val sync = DiscussionSyncState(setOf(ROOT))
            val executor = DiscussionReparseExecutor(
                reparser(db, store),
                sync,
                RootAvailability(Clock.System),
                alarm,
                recoveryBaseMillis = 50,
            )
            try {
                executor.start()
                sync.enter(ROOT, "test failure")
                alarm.runNext(50)

                executor.collectionChanged(ROOT)
                alarm.runAll(delayMillis = 0)

                JdbcDiscussionRows(db).row(ROOT, id)?.state shouldBe "failed"
                alarm.runNext()

                alarm.delays.last() shouldBe 200
            } finally {
                executor.close()
            }
        }
    }

    test("a failed row found in the saved row pass keeps recovery backoff") {
        withReparseFixture { root, db, disk, id ->
            Files.createDirectories(idDirectory(root, id))
            seedRow(db, id, null, "failed-root-row")
            var visits = 0
            val store = object : DiscussionStore by disk {
                override fun visit(root: RootName, visitor: (DiscussionId, Boolean) -> Unit): CollectionVisit =
                    when (++visits) {
                        1, 3 -> CollectionVisit.Failed("temporary")
                        else -> CollectionVisit.Visited(0)
                    }

                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead =
                    if (id == ID) EntriesRead.Failed("discussion read failed") else disk.read(root, id, only)
            }
            val alarm = ManualDiscussionAlarm()
            val sync = DiscussionSyncState(setOf(ROOT))
            val executor = DiscussionReparseExecutor(
                reparser(db, store),
                sync,
                RootAvailability(Clock.System),
                alarm,
                recoveryBaseMillis = 50,
            )
            try {
                executor.start()
                sync.enter(ROOT, "test failure")
                alarm.runNext(50)

                executor.collectionChanged(ROOT)
                alarm.runAll(delayMillis = 0)

                JdbcDiscussionRows(db).row(ROOT, id)?.state shouldBe "failed"
                alarm.runNext()

                alarm.delays.last() shouldBe 200
            } finally {
                executor.close()
            }
        }
    }

    test("a sticky unavailable root is not polled") {
        withReparseFixture { _, db, disk, _ ->
            val availability = RootAvailability(Clock.System)
            availability.markUnavailable(ROOT, UnavailableCause.VANISHED)
            val alarm = ManualDiscussionAlarm()
            val sync = DiscussionSyncState(setOf(ROOT))
            val executor = DiscussionReparseExecutor(reparser(db, disk), sync, availability, alarm)
            executor.start()
            sync.enter(ROOT, "root lost")

            alarm.delays shouldBe emptyList()
            sync.isUnsynced(ROOT) shouldBe true
            executor.close()
        }
    }
})

private val ROOT = RootName.require("docs")
private val ID = DiscussionId.require("01900000-0000-7000-8000-000000000001")
private val PAGE = PageId.require("01900000-0000-4000-8000-000000000001")

private fun reparser(db: DiscussionDb, store: DiscussionStore) = DiscussionReparser(
    setOf(ROOT),
    JdbcDiscussionRows(db),
    store,
    DiscussionFullReads(store),
)

private fun seedRow(db: DiscussionDb, id: DiscussionId, stamp: Stamp?, pageName: String) {
    val data = DiscussionRowData(
        state = "ok",
        pageId = PAGE,
        pagePath = TreePath.require("guide/$pageName.md"),
        status = "open",
        anchorKind = "quote",
        anchorHash = "anchor",
        starterKey = "sha256:starter",
        created = 1,
        updated = 2,
        commentCount = 1,
    )
    JdbcDiscussionRows(db).writing { apply(ROOT, id, RowUpdate.Upsert(data, emptyList()), stamp, dropMatch = false) }
}

private fun withReparseFixture(block: (Path, DiscussionDb, LocalDiscussionStore, DiscussionId) -> Unit) {
    val root = Files.createTempDirectory("pb-discussion-reparse-root")
    val databaseDirectory = Files.createTempDirectory("pb-discussion-reparse-db")
    val databasePath = databaseDirectory.resolve("discussions.db")
    val store = LocalDiscussionStore(mapOf(ROOT to root))
    DiscussionDb(databasePath).use { db ->
        try {
            block(root, db, store, ID)
        } finally {
            root.toFile().deleteRecursively()
            databaseDirectory.toFile().deleteRecursively()
        }
    }
}

private fun idDirectory(root: Path, id: DiscussionId) =
    root.resolve(".plainbase/discussions/${id.value}")

private fun commentFileNames(directory: Path): Set<String> = Files.newDirectoryStream(directory).use { entries ->
    entries.map { it.fileName.toString() }.filter { it != EntryName.Marker.fileName }.toSet()
}

private fun ageDiscussionEntries(root: Path, id: DiscussionId, modifiedMillis: Long) {
    val directory = idDirectory(root, id)
    Files.newDirectoryStream(directory).use { entries ->
        entries.forEach { entry -> Files.setLastModifiedTime(entry, FileTime.fromMillis(modifiedMillis)) }
    }
    Files.setLastModifiedTime(directory, FileTime.fromMillis(modifiedMillis))
}

private fun countingStore(world: DiscussionWorld, counts: MutableMap<DiscussionId, Int>): DiscussionStore =
    object : DiscussionStore by world.store {
        override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead {
            if (only == null) counts[id] = (counts[id] ?: 0) + 1
            return world.store.read(root, id, only)
        }
    }

private class OffsetHistory(private val root: Path, private val offsetMillis: Long) : HistoryProvider by NoOpHistoryProvider {
    override fun commitChanges(
        changes: List<HistoryChange>,
        message: String,
        author: CommitIdentity,
        committer: CommitIdentity,
    ): CommitOutcome {
        val collection = root.resolve(".plainbase/discussions")
        val modified = FileTime.fromMillis(System.currentTimeMillis() + offsetMillis)
        Files.newDirectoryStream(collection).use { directories ->
            directories.forEach { directory ->
                Files.newDirectoryStream(directory).use { entries ->
                    entries.forEach { entry -> Files.setLastModifiedTime(entry, modified) }
                }
            }
        }
        return CommitOutcome.Committed(null, null)
    }
}

private class BlockingHistory(
    private val entered: CountDownLatch,
    private val release: CountDownLatch,
) : HistoryProvider by NoOpHistoryProvider {
    override fun commitChanges(
        changes: List<HistoryChange>,
        message: String,
        author: CommitIdentity,
        committer: CommitIdentity,
    ): CommitOutcome {
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS))
        return CommitOutcome.Committed(null, null)
    }
}

private class RefusedHistory(
    private val entered: CountDownLatch,
    private val release: CountDownLatch,
) : HistoryProvider by NoOpHistoryProvider {
    override fun commitChanges(
        changes: List<HistoryChange>,
        message: String,
        author: CommitIdentity,
        committer: CommitIdentity,
    ): CommitOutcome {
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS))
        return CommitOutcome.NotCommitted(IOException("fixture refused the commit"))
    }
}

private class ManualDiscussionAlarm : Alarm {
    private data class Scheduled(val delayMillis: Long, val dueNanos: Long, val action: () -> Unit)

    private val scheduled = mutableListOf<Scheduled>()
    val delays = mutableListOf<Long>()

    @Synchronized
    override fun after(delayMillis: Long, action: () -> Unit) {
        val due = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMillis.coerceAtLeast(0))
        scheduled += Scheduled(delayMillis, due, action)
        delays += delayMillis
    }

    fun runNext(delayMillis: Long? = null) {
        val task = synchronized(this) {
            val index = if (delayMillis == null) {
                scheduled.indices.minByOrNull { scheduled[it].dueNanos } ?: -1
            } else {
                scheduled.indexOfFirst { it.delayMillis == delayMillis }
            }
            check(index >= 0) { "no scheduled task for delay $delayMillis" }
            scheduled.removeAt(index)
        }
        TimeUnit.NANOSECONDS.sleep((task.dueNanos - System.nanoTime()).coerceAtLeast(0))
        task.action()
    }

    fun runAll(delayMillis: Long? = null) {
        while (true) {
            val task = synchronized(this) {
                val index = if (delayMillis == null) {
                    scheduled.indices.minByOrNull { scheduled[it].dueNanos } ?: -1
                } else {
                    scheduled.indexOfFirst { it.delayMillis == delayMillis }
                }
                if (index < 0 || scheduled.isEmpty()) null else scheduled.removeAt(index)
            } ?: return
            TimeUnit.NANOSECONDS.sleep((task.dueNanos - System.nanoTime()).coerceAtLeast(0))
            task.action()
        }
    }
}
