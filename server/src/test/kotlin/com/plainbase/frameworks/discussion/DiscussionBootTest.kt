package com.plainbase.frameworks.discussion

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.discussion.Actor
import com.plainbase.domain.discussion.Anchor
import com.plainbase.domain.discussion.Author
import com.plainbase.domain.discussion.AuthorKind
import com.plainbase.domain.discussion.BootTombstone
import com.plainbase.domain.discussion.CachedMatch
import com.plainbase.domain.discussion.CollectionVisit
import com.plainbase.domain.discussion.CommentId
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionPageSource
import com.plainbase.domain.discussion.DiscussionRow
import com.plainbase.domain.discussion.DiscussionRowData
import com.plainbase.domain.discussion.DiscussionStore
import com.plainbase.domain.discussion.EntriesRead
import com.plainbase.domain.discussion.EntryName
import com.plainbase.domain.discussion.EntryPath
import com.plainbase.domain.discussion.EntryRow
import com.plainbase.domain.discussion.PageRef
import com.plainbase.domain.discussion.RowUpdate
import com.plainbase.domain.discussion.Stamp
import com.plainbase.domain.discussion.StoreWrite
import com.plainbase.domain.history.CommitIdentity
import com.plainbase.domain.history.CommitOutcome
import com.plainbase.domain.history.HistoryChange
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.SubjectKey
import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.ContentWriteMonitor
import com.plainbase.domain.service.DiscussionCommand
import com.plainbase.domain.service.DiscussionFacts
import com.plainbase.domain.service.DiscussionFullReads
import com.plainbase.domain.service.DiscussionIdProvider
import com.plainbase.domain.service.DiscussionReadFailed
import com.plainbase.domain.service.DiscussionReads
import com.plainbase.domain.service.DiscussionReparser
import com.plainbase.domain.service.DiscussionSyncState
import com.plainbase.domain.service.DiscussionWriteOutcome
import com.plainbase.domain.service.DiscussionWriter
import com.plainbase.domain.service.RootSync
import com.plainbase.domain.service.RootUnavailable
import com.plainbase.domain.service.SyncedDiscussionIndex
import com.plainbase.domain.service.write
import com.plainbase.frameworks.filesystem.LocalDiscussionStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.git.providerOver
import com.plainbase.frameworks.git.withGitRepoHome
import com.plainbase.frameworks.runtime.HistoryProviders
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

class DiscussionBootTest : FunSpec({
    test("deleting discussions db loses nothing") {
        withBootWorld { world ->
            val first = world.startDiscussion("first runtime discussion")
            world.addComment(first, "a distinct runtime reply")
            world.startDiscussion("second runtime discussion")
            val incomplete = DiscussionId.require("01900000-0000-7000-8000-000000000059")
            val incompleteComment = EntryName.Comment(CommentId.require("01900000-0000-7000-8000-000000000159"))
            val incompleteFile = discussionFile(world.paths.getValue(ROOT), incomplete, incompleteComment)
            Files.createDirectories(incompleteFile.parent)
            Files.writeString(incompleteFile, "incomplete fixture entry\n")
            Thread.sleep(2_100)
            world.boot().run()
            val before = world.snapshot()
            before.rows.size shouldBe 3
            before.rows.single { it.id == incomplete }.state shouldBe "incomplete"
            before.rows.single { it.id == first }.stamp?.racy shouldBe false

            world.deleteDatabaseAndReopen()
            world.boot().run()

            world.snapshot() shouldBe before
        }
    }

    test("a removed root's rows are dropped by the boot truncate") {
        val removed = RootName.require("archive")
        val id = DiscussionId.require("01900000-0000-7000-8000-000000000058")
        withBootWorld { world ->
            world.rows.writing {
                apply(removed, id, RowUpdate.Upsert(DiscussionRowData(state = "ok"), entries = emptyList()), Stamp("old-root"), false)
            }
            world.rows.row(removed, id).shouldNotBeNull()

            world.boot(editableRoots = emptySet()).run()

            world.rows.row(removed, id) shouldBe null
        }
    }

    test("a complete entry converges when its sibling is incomplete") {
        withBootWorld { world ->
            val id = DiscussionId.require("01900000-0000-7000-8000-000000000041")
            val marker = "complete marker bytes\n".encodeToByteArray()
            writeDiscussion(world.paths.getValue(ROOT), id, marker)
            val comment = EntryName.Comment(CommentId.require("01900000-0000-7000-8000-000000000141"))
            Files.write(discussionFile(world.paths.getValue(ROOT), id, comment), ByteArray(comment.cap + 1) { 65 })
            val history = RecordingHistory(head = emptyMap())

            world.boot(histories = mapOf(ROOT to history)).run()

            history.attempts.shouldContainExactly(
                HistoryAttempt(
                    listOf(HistoryChange.Put(discussionPath(id, EntryName.Marker), marker)),
                    "discussion: reconcile ${id.value}",
                    CommitOutcome.Committed("recorded", null),
                ),
            )
        }
    }

    test("a boot step failure unsyncs only that root") {
        val second = RootName.require("notes")
        withBootWorld(listOf(ROOT, second)) { world ->
            val failingStore = object : DiscussionStore by world.store {
                override fun sweepBootResidue(root: RootName, now: Instant, minAge: Duration): List<BootTombstone> {
                    if (root == ROOT) throw IOException("residue scan failed")
                    return emptyList()
                }
            }

            world.boot(store = failingStore).run()

            world.sync.current(ROOT).shouldBeInstanceOf<RootSync.Unsynced>().cause shouldBe "residue scan failed"
            world.sync.current(second) shouldBe RootSync.Synced(0)
        }
    }

    test("an interrupted reparse stops boot without marking the root unsynced") {
        val second = RootName.require("notes")
        withBootWorld(listOf(ROOT, second)) { world ->
            writeDiscussion(world.paths.getValue(ROOT), BOOT_ID, "marker bytes\n".encodeToByteArray())
            var interruptedAtStamp = false
            var laterRootSweeps = 0
            val interruptingStore = object : DiscussionStore by world.store {
                override fun stamp(root: RootName, id: DiscussionId): Stamp? = world.store.stamp(root, id).also {
                    if (root == ROOT) {
                        interruptedAtStamp = true
                        Thread.currentThread().interrupt()
                    }
                }

                override fun sweepBootResidue(root: RootName, now: Instant, minAge: Duration): List<BootTombstone> {
                    if (root == second) laterRootSweeps++
                    return world.store.sweepBootResidue(root, now, minAge)
                }
            }
            try {
                shouldThrow<InterruptedException> { world.boot(store = interruptingStore).run() }
                interruptedAtStamp shouldBe true
                Thread.currentThread().isInterrupted shouldBe true
                laterRootSweeps shouldBe 0
                world.sync.current(ROOT) shouldBe RootSync.Synced(0)
            } finally {
                Thread.interrupted()
            }
        }
    }

    test("a symlinked collection is unsynced across delete and restart") {
        withBootWorld { world ->
            val root = world.paths.getValue(ROOT)
            val collection = root.resolve(".plainbase/discussions")
            val target = Files.createTempDirectory("pb-discussion-boot-symlink-target")
            Files.createDirectories(collection.parent)
            Files.createSymbolicLink(collection, target)
            try {
                repeat(2) { restart ->
                    world.boot().run()
                    world.sync.current(ROOT).shouldBeInstanceOf<RootSync.Unsynced>().cause shouldBe "symlink"
                    shouldThrow<DiscussionReadFailed> { world.reads().rootDiscussions(ROOT) }
                    world.reads().facts(ROOT, BOOT_ID) shouldBe DiscussionFacts.Unknown
                    if (restart == 0) world.deleteDatabaseAndReopen()
                }
            } finally {
                Files.deleteIfExists(collection)
                target.toFile().deleteRecursively()
            }
        }
    }

    test("an unreadable discussion at boot is failed and the root is synced") {
        withBootWorld { world ->
            val id = DiscussionId.require("01900000-0000-7000-8000-000000000042")
            writeDiscussion(world.paths.getValue(ROOT), id, "a valid marker whose read fails".encodeToByteArray())
            val unreadableStore = LocalDiscussionStore(
                mapOf(ROOT to world.paths.getValue(ROOT)),
                readEntryBytes = { path, _ ->
                    if (path.fileName.toString() == EntryName.Marker.fileName) throw IOException("fixture marker read failed")
                    Files.readAllBytes(path)
                },
            )

            world.boot(store = unreadableStore).run()

            world.rows.row(ROOT, id)?.state shouldBe "failed"
            world.sync.current(ROOT) shouldBe RootSync.Synced(0)
            world.reads().facts(ROOT, id) shouldBe DiscussionFacts.Unknown
        }
    }

    test("a failing commit at boot skips only that discussion") {
        withBootWorld { world ->
            val failed = DiscussionId.require("01900000-0000-7000-8000-000000000043")
            val unknown = DiscussionId.require("01900000-0000-7000-8000-000000000044")
            val committed = DiscussionId.require("01900000-0000-7000-8000-000000000045")
            writeDiscussion(world.paths.getValue(ROOT), failed, "fail this commit\n".encodeToByteArray())
            writeDiscussion(world.paths.getValue(ROOT), unknown, "unknown commit outcome\n".encodeToByteArray())
            writeDiscussion(world.paths.getValue(ROOT), committed, "commit this discussion\n".encodeToByteArray())
            val history = RecordingHistory(head = emptyMap()) { changes, _ ->
                when {
                    changes.single().path.value.contains(failed.value) -> CommitOutcome.NotCommitted(IOException("refused"))
                    changes.single().path.value.contains(unknown.value) -> CommitOutcome.Unknown(IOException("uncertain"))
                    else -> CommitOutcome.Committed("recorded", null)
                }
            }

            withBootLogCapture { appender ->
                world.boot(histories = mapOf(ROOT to history)).run()

                history.attempts.size shouldBe 3
                history.attempts.filter { it.outcome is CommitOutcome.Committed }
                    .flatMap { it.changes.map(HistoryChange::path) }
                    .shouldContainExactly(listOf(discussionPath(committed, EntryName.Marker)))
                val skippedPaths = history.attempts.filter { it.outcome !is CommitOutcome.Committed }
                    .flatMap { it.changes.map(HistoryChange::path) }
                    .map { it.value }
                skippedPaths shouldContain discussionPath(failed, EntryName.Marker).value
                skippedPaths shouldContain discussionPath(unknown, EntryName.Marker).value
                val warnings = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
                warnings.any { failed.value in it } shouldBe true
                warnings.any { unknown.value in it } shouldBe true
                world.sync.current(ROOT) shouldBe RootSync.Synced(0)
            }
        }
    }

    test("a database that cannot open refuses to serve") {
        val temp = Files.createTempDirectory("plainbase-discussion-db-open")
        try {
            val openFailure = IOException("database open denied")
            shouldThrow<IOException> { DiscussionDb(temp.resolve("discussions.db"), { throw openFailure }) }
        } finally {
            temp.toFile().deleteRecursively()
        }

        withBootWorld { world ->
            val id = world.startDiscussion("must survive a failed boot transaction")
            DbFaults(world.databasePath).use { faults ->
                faults.failWrites()

                shouldThrow<Exception> { world.boot().run() }

                world.rows.row(ROOT, id).shouldNotBeNull()
            }
        }
    }

    test("boot removes only empty discussion directories") {
        withBootWorld { world ->
            val emptyId = DiscussionId.require("01900000-0000-7000-8000-000000000046")
            val markerlessId = DiscussionId.require("01900000-0000-7000-8000-000000000047")
            val emptyDirectory = Files.createDirectories(discussionDirectory(world.paths.getValue(ROOT), emptyId))
            val markerlessDirectory = Files.createDirectories(discussionDirectory(world.paths.getValue(ROOT), markerlessId))
            val markerlessFile = markerlessDirectory.resolve("operator-note.txt")
            Files.writeString(markerlessFile, "kept markerless data")

            world.boot().run()

            Files.exists(emptyDirectory, LinkOption.NOFOLLOW_LINKS) shouldBe false
            Files.readString(markerlessFile) shouldBe "kept markerless data"
            world.rows.row(ROOT, markerlessId)?.state shouldBe "incomplete"
        }
    }

    test("boot sweeps only aged temps") {
        withBootWorld { world ->
            val id = DiscussionId.require("01900000-0000-7000-8000-000000000048")
            val directory = Files.createDirectories(discussionDirectory(world.paths.getValue(ROOT), id))
            val aged = directory.resolve(".pbtmp.0123456789abcdef.tmp")
            val recent = directory.resolve(".pbtmp.fedcba9876543210.tmp")
            Files.write(aged, byteArrayOf(1))
            Files.write(recent, byteArrayOf(2))
            Files.setLastModifiedTime(aged, FileTime.fromMillis(TEST_CLOCK.now().toEpochMilliseconds() - 48 * 60 * 60 * 1_000))
            Files.setLastModifiedTime(recent, FileTime.fromMillis(TEST_CLOCK.now().toEpochMilliseconds()))

            world.boot().run()

            Files.exists(aged, LinkOption.NOFOLLOW_LINKS) shouldBe false
            Files.exists(recent, LinkOption.NOFOLLOW_LINKS) shouldBe true
        }
    }

    test("an unavailable root at boot is unsynced and never an empty listing") {
        withBootWorld { world ->
            val id = world.startDiscussion("must not look like an empty unavailable root")
            world.availability.markUnavailable(ROOT, UnavailableCause.MISSING_AT_BOOT)

            world.boot().run()

            world.sync.current(ROOT).shouldBeInstanceOf<RootSync.Unsynced>()
            shouldThrow<RootUnavailable> { world.reads().rootDiscussions(ROOT) }
            shouldThrow<RootUnavailable> { world.reads().detail(ROOT, id) }
            world.reads().facts(ROOT, id) shouldBe DiscussionFacts.Unknown
        }
    }

    test("a root found corpus missing after boot never serves an empty listing") {
        withBootWorld { world ->
            val id = world.startDiscussion("the configured root had real discussion files")
            world.boot().run()
            world.rows.row(ROOT, id).shouldNotBeNull()
            world.sync.current(ROOT) shouldBe RootSync.Synced(0)
            world.availability.markUnavailable(ROOT, UnavailableCause.CORPUS_MISSING)

            shouldThrow<RootUnavailable> { world.reads().rootDiscussions(ROOT) }
            shouldThrow<RootUnavailable> { world.reads().detail(ROOT, id) }
            world.reads().facts(ROOT, id) shouldBe DiscussionFacts.Unknown
        }
    }

    test("an uncommitted discussion is committed at boot") {
        withGitRepoHome { content, exec, home ->
            val id = DiscussionId.require("01900000-0000-7000-8000-000000000049")
            val bytes = "offline marker edit\n".encodeToByteArray()
            writeDiscussion(content, id, bytes)
            val provider = providerOver(exec, content, home)
            provider.prepare()
            withBootWorldAt(content, home.resolve("discussions.db")) { world ->
                world.boot(histories = mapOf(ROOT to provider)).run()

                exec.run(listOf("show", "HEAD:${discussionPath(id, EntryName.Marker).value}")).stdout.contentEquals(bytes) shouldBe true
                exec.run(listOf("log", "-1", "--format=%s")).stdoutText.trim() shouldBe "discussion: reconcile ${id.value}"
            }
        }
    }

    test("an ignored discussion file converges") {
        withGitRepoHome { content, exec, home ->
            val id = DiscussionId.require("01900000-0000-7000-8000-000000000050")
            val path = discussionPath(id, EntryName.Marker)
            val ignoreBytes = "${path.value}\n".encodeToByteArray()
            Files.write(content.resolve(".gitignore"), ignoreBytes)
            val provider = providerOver(exec, content, home)
            provider.prepare()
            provider.commit(TreePath.require(".gitignore"), ignoreBytes)
            val bytes = "gitignored offline discussion\n".encodeToByteArray()
            writeDiscussion(content, id, bytes)

            withBootWorldAt(content, home.resolve("discussions.db")) { world ->
                world.boot(histories = mapOf(ROOT to provider)).run()

                exec.run(listOf("show", "HEAD:${path.value}")).stdout.contentEquals(bytes) shouldBe true
            }
        }
    }

    test("a staged-only difference converges") {
        withGitRepoHome { content, exec, home ->
            val provider = providerOver(exec, content, home)
            provider.prepare()
            provider.commit(TreePath.require("baseline.txt"), "baseline\n".encodeToByteArray())
            Files.writeString(content.resolve("staged-only.txt"), "staged but not in HEAD\n")
            exec.run(listOf("add", "staged-only.txt")).ok shouldBe true
            val id = DiscussionId.require("01900000-0000-7000-8000-000000000051")
            val bytes = "discussion put beside staged work\n".encodeToByteArray()
            writeDiscussion(content, id, bytes)
            val discussionEntry = discussionPath(id, EntryName.Marker).value
            exec.run(listOf("add", "--", discussionEntry)).ok shouldBe true

            withBootWorldAt(content, home.resolve("discussions.db")) { world ->
                world.boot(histories = mapOf(ROOT to provider)).run()

                exec.run(listOf("ls-tree", "--name-only", "HEAD", "--", "staged-only.txt")).stdoutText.trim() shouldBe ""
                exec.run(listOf("diff", "--cached", "--name-only")).stdoutText shouldContain "staged-only.txt"
                exec.run(listOf("diff", "--cached", "--name-only", "--", discussionEntry)).stdoutText.trim() shouldBe ""
                exec.run(listOf("show", "HEAD:${discussionPath(id, EntryName.Marker).value}")).stdout.contentEquals(bytes) shouldBe true
            }
        }
    }

    test("a planted clean filter never runs") {
        withGitRepoHome { content, exec, home ->
            val id = DiscussionId.require("01900000-0000-7000-8000-000000000052")
            val path = discussionPath(id, EntryName.Marker)
            val sentinel = content.resolve("clean-filter-ran")
            val script = content.resolve("clean-filter.sh")
            Files.writeString(script, "#!/bin/sh\nprintf ran > '$sentinel'\ncat\n")
            Files.setPosixFilePermissions(
                script,
                setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
                ),
            )
            val attributeBytes = "${path.value} filter=sentinel\n".encodeToByteArray()
            Files.write(content.resolve(".gitattributes"), attributeBytes)
            val provider = providerOver(exec, content, home)
            provider.prepare()
            provider.commit(TreePath.require(".gitattributes"), attributeBytes)
            exec.run(listOf("config", "--local", "filter.sentinel.clean", script.toString())).ok shouldBe true
            exec.run(listOf("check-attr", "filter", "--", path.value)).stdoutText shouldContain "filter: sentinel"
            val bytes = "clean filter must not change this\n".encodeToByteArray()
            writeDiscussion(content, id, bytes)

            withBootWorldAt(content, home.resolve("discussions.db")) { world ->
                world.boot(histories = mapOf(ROOT to provider)).run()

                Files.exists(sentinel, LinkOption.NOFOLLOW_LINKS) shouldBe false
                exec.run(listOf("show", "HEAD:${path.value}")).stdout.contentEquals(bytes) shouldBe true
            }
        }
    }

    test("a tracked file missing from the worktree is never committed as a deletion") {
        withGitRepoHome { content, exec, home ->
            val id = DiscussionId.require("01900000-0000-7000-8000-000000000053")
            val path = discussionPath(id, EntryName.Marker)
            val bytes = "tracked before worktree loss\n".encodeToByteArray()
            val sibling = EntryName.Comment(CommentId.require("01900000-0000-7000-8000-000000000153"))
            val siblingBytes = "tracked sibling comment\n".encodeToByteArray()
            writeDiscussion(content, id, bytes)
            Files.write(discussionFile(content, id, sibling), siblingBytes)
            val provider = providerOver(exec, content, home)
            provider.prepare()
            provider.commit(path, bytes)
            provider.commit(discussionPath(id, sibling), siblingBytes)
            Files.delete(discussionFile(content, id, EntryName.Marker))

            withBootWorldAt(content, home.resolve("discussions.db")) { world ->
                world.boot(histories = mapOf(ROOT to provider)).run()

                Files.exists(discussionFile(content, id, EntryName.Marker), LinkOption.NOFOLLOW_LINKS) shouldBe false
                Files.exists(discussionFile(content, id, sibling), LinkOption.NOFOLLOW_LINKS) shouldBe true
                exec.run(listOf("show", "HEAD:${path.value}")).stdout.contentEquals(bytes) shouldBe true
                exec.run(listOf("log", "--format=%s", "--", path.value)).stdoutText.count { it == '\n' } shouldBe 1
            }
        }
    }

    test("a noop history root is not swept") {
        withBootWorld { world ->
            val id = DiscussionId.require("01900000-0000-7000-8000-000000000054")
            writeDiscussion(world.paths.getValue(ROOT), id, "no history root marker\n".encodeToByteArray())
            val history = RecordingHistory(enabled = false, head = null)

            world.boot(histories = mapOf(ROOT to history)).run()

            history.headCalls shouldBe 0
            history.attempts shouldBe emptyList()
        }
    }

    test("a tombstone head does not track is kept") {
        withGitRepoHome { content, exec, home ->
            val id = DiscussionId.require("01900000-0000-7000-8000-000000000055")
            val bytes = "never committed target\n".encodeToByteArray()
            writeDiscussion(content, id, bytes)
            val provider = providerOver(exec, content, home)
            provider.prepare()
            provider.commit(TreePath.require("baseline.txt"), "unrelated commit\n".encodeToByteArray())
            val store = LocalDiscussionStore(mapOf(ROOT to content))
            val entry = (store.read(ROOT, id, null) as EntriesRead.Present).entries.single()
            val tombstone = store.purge(ROOT, EntryPath(id, entry.name), entry.version)
                .shouldBeInstanceOf<StoreWrite.Written>().tombstone.shouldNotBeNull()
            withBootWorldAt(content, home.resolve("discussions.db")) { world ->
                world.boot(histories = mapOf(ROOT to provider), store = store).run()

                Files.exists(discussionFile(content, id, entry.name), LinkOption.NOFOLLOW_LINKS) shouldBe false
                val tombstonePath = discussionDirectory(content, id).resolve(tombstone.fileName)
                Files.exists(tombstonePath, LinkOption.NOFOLLOW_LINKS) shouldBe true
            }
        }
    }

    test("an unknown git answer keeps the tombstone") {
        withBootWorld { world ->
            val id = DiscussionId.require("01900000-0000-7000-8000-000000000056")
            val bytes = "unknown head target\n".encodeToByteArray()
            writeDiscussion(world.paths.getValue(ROOT), id, bytes)
            val entry = (world.store.read(ROOT, id, null) as EntriesRead.Present).entries.single()
            val tombstone = world.store.purge(ROOT, EntryPath(id, entry.name), entry.version)
                .shouldBeInstanceOf<StoreWrite.Written>().tombstone.shouldNotBeNull()
            val history = RecordingHistory(head = null)

            world.boot(histories = mapOf(ROOT to history)).run()

            history.headCalls shouldBe 2
            Files.exists(discussionFile(world.paths.getValue(ROOT), id, entry.name), LinkOption.NOFOLLOW_LINKS) shouldBe false
            val tombstonePath = discussionDirectory(world.paths.getValue(ROOT), id).resolve(tombstone.fileName)
            Files.exists(tombstonePath, LinkOption.NOFOLLOW_LINKS) shouldBe true
        }
    }

    test("an unverifiable head skips the sweep") {
        withBootWorld { world ->
            val id = DiscussionId.require("01900000-0000-7000-8000-000000000033")
            val bytes = "uncommitted marker content\n".encodeToByteArray()
            writeDiscussion(world.paths.getValue(ROOT), id, bytes)
            val history = RecordingHistory(head = null, blob = "blob-33")

            world.boot(histories = mapOf(ROOT to history)).run()

            history.headCalls shouldBe 1
            history.attempts shouldBe emptyList()
            world.sync.current(ROOT) shouldBe RootSync.Synced(0)
        }
    }

    test("a fresh repo crash converges at boot") {
        withGitRepoHome { content, exec, home ->
            val id = DiscussionId.require("01900000-0000-7000-8000-000000000034")
            val bytes = "crash residue marker\n".encodeToByteArray()
            writeDiscussion(content, id, bytes)
            val provider = providerOver(exec, content, home)
            provider.prepare()
            withBootWorldAt(content, home.resolve("discussions.db")) { world ->
                world.boot(histories = mapOf(ROOT to provider)).run()

                val path = discussionPath(id, EntryName.Marker)
                exec.run(listOf("show", "HEAD:${path.value}")).stdoutText shouldBe bytes.decodeToString()
            }
        }
    }

    test("a tombstone whose purge never committed is restored") {
        withGitRepoHome { content, exec, home ->
            val id = DiscussionId.require("01900000-0000-7000-8000-000000000035")
            val bytes = "tracked marker before interrupted purge\n".encodeToByteArray()
            writeDiscussion(content, id, bytes)
            val provider = providerOver(exec, content, home)
            provider.commit(discussionPath(id, EntryName.Marker), bytes)
            val store = LocalDiscussionStore(mapOf(ROOT to content))
            val entry = store.read(ROOT, id, null).let { read ->
                val present = read as EntriesRead.Present
                present.entries.single { it.name == EntryName.Marker }
            }
            val purge = store.purge(ROOT, EntryPath(id, EntryName.Marker), entry.version)
            purge.shouldBeInstanceOf<StoreWrite.Written>().tombstone.shouldNotBeNull()
            withBootWorldAt(content, home.resolve("discussions.db")) { world ->
                world.boot(histories = mapOf(ROOT to provider), store = store).run()

                Files.readAllBytes(discussionFile(content, id, EntryName.Marker)).contentEquals(bytes) shouldBe true
                Files.list(discussionDirectory(content, id)).use { entries ->
                    entries.noneMatch { it.fileName.toString().startsWith(".pbpurge.") } shouldBe true
                }
            }
        }
    }

    test("a tombstone is kept when HEAD has a different blob for its path") {
        withBootWorld { world ->
            val id = DiscussionId.require("01900000-0000-7000-8000-000000000057")
            val bytes = "older tombstoned marker bytes\n".encodeToByteArray()
            writeDiscussion(world.paths.getValue(ROOT), id, bytes)
            val entry = (world.store.read(ROOT, id, null) as EntriesRead.Present).entries.single()
            val tombstone = world.store.purge(ROOT, EntryPath(id, entry.name), entry.version)
                .shouldBeInstanceOf<StoreWrite.Written>().tombstone.shouldNotBeNull()
            val path = discussionPath(id, entry.name)
            val history = RecordingHistory(head = mapOf(path to "newer-head-blob"), blob = "older-tombstone-blob")

            world.boot(histories = mapOf(ROOT to history)).run()

            val restoredEntryExists = Files.exists(
                discussionFile(world.paths.getValue(ROOT), id, entry.name),
                LinkOption.NOFOLLOW_LINKS,
            )
            val tombstoneExists = Files.exists(
                discussionDirectory(world.paths.getValue(ROOT), id).resolve(tombstone.fileName),
                LinkOption.NOFOLLOW_LINKS,
            )
            restoredEntryExists shouldBe false
            tombstoneExists shouldBe true
            history.attempts shouldBe emptyList()
        }
    }
    test("disabled and all-disabled boot skip per-root stores and history while global rows truncate") {
        val extra = RootName.require("extra")
        withBootWorld(listOf(ROOT, extra)) { world ->
            world.startDiscussion("keep authoritative thread")
            val before = Files.walk(world.paths.getValue(ROOT)).use { paths ->
                paths.filter(Files::isRegularFile).toList().associate { it.toString() to Files.readAllBytes(it).toList() }
            }
            var disabledCalls = 0
            val store = object : DiscussionStore by world.store {
                override fun sweepBootResidue(root: RootName, now: Instant, minAge: Duration): List<BootTombstone> {
                    if (root == ROOT) {
                        disabledCalls++
                        error("disabled sweep")
                    }
                    return world.store.sweepBootResidue(root, now, minAge)
                }
                override fun visit(root: RootName, visitor: (DiscussionId, Boolean) -> Unit): CollectionVisit {
                    if (root == ROOT) {
                        disabledCalls++
                        error("disabled visit")
                    }
                    return world.store.visit(root, visitor)
                }
            }
            val history = RecordingHistory(head = emptyMap())
            world.boot(store = store, histories = mapOf(ROOT to history, extra to NoOpHistoryProvider), disabledRoots = setOf(ROOT)).run()
            disabledCalls shouldBe 0
            history.headCalls shouldBe 0
            history.attempts shouldBe emptyList()
            world.rows.rowsAfter(ROOT, null, 50) shouldBe emptyList()
            Files.walk(world.paths.getValue(ROOT)).use { paths ->
                paths.filter(Files::isRegularFile).toList().associate { it.toString() to Files.readAllBytes(it).toList() }
            } shouldBe before
            val forbidden = object : DiscussionStore by world.store {
                override fun sweepBootResidue(root: RootName, now: Instant, minAge: Duration): List<BootTombstone> {
                    disabledCalls++
                    error("all-disabled sweep")
                }
            }
            world.boot(store = forbidden, disabledRoots = setOf(ROOT, extra)).run()
            disabledCalls shouldBe 0
        }
    }
})

private val ROOT = RootName.PRIMARY
private val BOOT_ID = DiscussionId.require("01900000-0000-7000-8000-000000000040")

private data class HistoryAttempt(
    val changes: List<HistoryChange>,
    val message: String,
    val outcome: CommitOutcome,
)

private class RecordingHistory(
    override val enabled: Boolean = true,
    private val head: Map<TreePath, String>?,
    private val blob: String? = "recorded-blob",
    private val outcomeFor: (List<HistoryChange>, String) -> CommitOutcome = { _, _ -> CommitOutcome.Committed("recorded", null) },
) : HistoryProvider by NoOpHistoryProvider {
    var headCalls: Int = 0
    val attempts = mutableListOf<HistoryAttempt>()

    override fun headBlobs(dirs: List<TreePath>): Map<TreePath, String>? {
        headCalls++
        return head
    }

    override fun blobId(bytes: ByteArray): String? = blob

    override fun commitChanges(
        changes: List<HistoryChange>,
        message: String,
        author: CommitIdentity,
        committer: CommitIdentity,
    ): CommitOutcome = outcomeFor(changes, message).also { outcome ->
        attempts += HistoryAttempt(changes, message, outcome)
    }
}

private data class DiscussionEntrySnapshot(val root: RootName, val id: DiscussionId, val entry: EntryRow)

private data class DiscussionMatchSnapshot(val root: RootName, val id: DiscussionId, val match: CachedMatch)

private data class DiscussionDatabaseSnapshot(
    val rows: List<DiscussionRow>,
    val entries: List<DiscussionEntrySnapshot>,
    val matches: List<DiscussionMatchSnapshot>,
)

private class BootWorld(
    val base: Path,
    val paths: Map<RootName, Path>,
    val databasePath: Path,
    var db: DiscussionDb,
    var rows: JdbcDiscussionRows,
    val store: DiscussionStore,
    val sync: DiscussionSyncState,
) : AutoCloseable {
    val fullReads = DiscussionFullReads(store)
    val availability = RootAvailability(TEST_CLOCK)
    private val monitor = ContentWriteMonitor()
    private val ids = BootIds()
    private val pageBytes = "# Boot page\n\nA stable boot test page.\n".encodeToByteArray()
    private val page = PageRef(
        PageId.require("01900000-0000-4000-8000-000000000057"),
        TreePath.require("guide/boot-discussion.md"),
    )
    private val anchor = Anchor.Page(CitationFactory().contentHash(pageBytes), null)
    private val author = Author(
        Actor(SubjectKey("discussion-boot", "writer"), "Boot writer"),
        AuthorKind.HUMAN,
    )

    fun boot(
        histories: Map<RootName, HistoryProvider> = paths.keys.associateWith { NoOpHistoryProvider },
        store: DiscussionStore = this.store,
        registeredRoots: List<RootName> = paths.keys.toList(),
        editableRoots: Set<RootName> = registeredRoots.toSet(),
        disabledRoots: Set<RootName> = emptySet(),
    ): DiscussionBoot {
        val sharedReads = if (store === this.store) fullReads else DiscussionFullReads(store)
        val roots = registeredRoots.map { name ->
            val path = requireNotNull(paths[name]) { "missing fixture path for ${name.value}" }
            Root(
                name, RootBackend.Local(path), editable = name in editableRoots, history = HistoryMode.OFF,
                discussionsEnabled =
                name !in disabledRoots,
            )
        }
        val eligible = roots.filter { it.supportsDiscussions }.mapTo(linkedSetOf()) { it.name }
        val reparser = DiscussionReparser(eligible, rows, store, sharedReads)
        return DiscussionBoot(
            rows = rows,
            store = store,
            fullReads = sharedReads,
            reparser = reparser,
            sync = sync,
            histories = HistoryProviders(histories),
            monitor = monitor,
            availability = availability,
            registry = RootRegistry.of(roots),
            clock = TEST_CLOCK,
        )
    }

    fun reads(store: DiscussionStore = this.store): DiscussionReads = DiscussionReads(
        rows,
        store,
        if (store === this.store) fullReads else DiscussionFullReads(store),
        sync,
        availability,
    )

    fun startDiscussion(body: String): DiscussionId {
        val index = SyncedDiscussionIndex(rows, store, fullReads, sync)
        val writer = DiscussionWriter(
            monitor,
            store,
            DiscussionPageSource { _, _ -> ContentRead.Bytes(pageBytes.copyOf()) },
            { NoOpHistoryProvider },
            index,
            ids,
            TEST_CLOCK,
        )
        val result = writer.write(DiscussionCommand.Start(ROOT, author, page, anchor, body))
        return (result as? DiscussionWriteOutcome.Done)?.id ?: error("runtime discussion start failed: $result")
    }

    fun addComment(id: DiscussionId, body: String) {
        val index = SyncedDiscussionIndex(rows, store, fullReads, sync)
        val writer = DiscussionWriter(
            monitor,
            store,
            DiscussionPageSource { _, _ -> ContentRead.Bytes(pageBytes.copyOf()) },
            { NoOpHistoryProvider },
            index,
            ids,
            TEST_CLOCK,
        )
        val outcome = writer.write(DiscussionCommand.AddComment(ROOT, author, id, body))
        outcome.shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
    }

    fun snapshot(): DiscussionDatabaseSnapshot {
        val foundRows = paths.keys.flatMap { root ->
            val found = mutableListOf<DiscussionRow>()
            var after: DiscussionId? = null
            var continuePaging = true
            while (continuePaging) {
                val batch = rows.rowsAfter(root, after, 200)
                if (batch.isEmpty()) {
                    continuePaging = false
                } else {
                    found += batch
                    after = batch.last().id
                    continuePaging = batch.size == 200
                }
            }
            found
        }
        val entryRows = foundRows.flatMap { row ->
            val present = store.read(row.root, row.id, null) as? EntriesRead.Present
            present?.entries.orEmpty().mapNotNull { raw ->
                rows.entry(row.root, row.id, raw.name)?.let { DiscussionEntrySnapshot(row.root, row.id, it) }
            }
        }
        val matches = foundRows.mapNotNull { row ->
            rows.cached(row.root, row.id)?.let { DiscussionMatchSnapshot(row.root, row.id, it) }
        }
        return DiscussionDatabaseSnapshot(foundRows, entryRows, matches)
    }

    fun deleteDatabaseAndReopen() {
        db.close()
        Files.deleteIfExists(databasePath)
        Files.deleteIfExists(Path.of("$databasePath-wal"))
        Files.deleteIfExists(Path.of("$databasePath-shm"))
        db = DiscussionDb(databasePath)
        rows = JdbcDiscussionRows(db)
    }

    override fun close() {
        db.close()
        base.toFile().deleteRecursively()
    }
}

private class BootIds : DiscussionIdProvider {
    private val discussions = AtomicInteger(31)
    private val comments = AtomicInteger(131)

    override fun nextDiscussion(): DiscussionId = discussionId(discussions.getAndIncrement())

    override fun nextComment(): CommentId = CommentId.require(idValue(comments.getAndIncrement()))

    private fun discussionId(number: Int): DiscussionId = DiscussionId.require(idValue(number))

    private fun idValue(number: Int): String = "01900000-0000-7000-8000-${number.toString(16).padStart(12, '0')}"
}

private fun withBootWorld(
    roots: List<RootName> = listOf(ROOT),
    block: (BootWorld) -> Unit,
) {
    val base = Files.createTempDirectory("plainbase-discussion-boot-test")
    val paths = roots.associateWith { name -> base.resolve(name.value).also { Files.createDirectories(it) } }
    val databasePath = base.resolve("data/discussions.db")
    val db = DiscussionDb(databasePath)
    val world = BootWorld(base, paths, databasePath, db, JdbcDiscussionRows(db), LocalDiscussionStore(paths), DiscussionSyncState(roots))
    try {
        block(world)
    } finally {
        world.close()
    }
}

private fun withBootWorldAt(content: Path, dbPath: Path, block: (BootWorld) -> Unit) {
    val data = Files.createTempDirectory("plainbase-discussion-boot-data")
    val db = DiscussionDb(dbPath)
    val paths = mapOf(ROOT to content)
    val world = BootWorld(data, paths, dbPath, db, JdbcDiscussionRows(db), LocalDiscussionStore(paths), DiscussionSyncState(listOf(ROOT)))
    try {
        block(world)
    } finally {
        world.close()
    }
}

private fun withBootLogCapture(block: (ListAppender<ILoggingEvent>) -> Unit) {
    val logger = LoggerFactory.getLogger(DiscussionBoot::class.java) as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    logger.addAppender(appender)
    try {
        block(appender)
    } finally {
        logger.detachAppender(appender)
        appender.stop()
    }
}

private fun writeDiscussion(root: Path, id: DiscussionId, bytes: ByteArray) {
    val directory = Files.createDirectories(discussionDirectory(root, id))
    Files.write(discussionFile(root, id, EntryName.Marker), bytes)
}

private fun discussionDirectory(root: Path, id: DiscussionId): Path =
    root.resolve(".plainbase/discussions/${id.value}")

private fun discussionFile(root: Path, id: DiscussionId, name: EntryName): Path =
    discussionDirectory(root, id).resolve(name.fileName)

private fun discussionPath(id: DiscussionId, name: EntryName): TreePath =
    TreePath.require(".plainbase/discussions/${id.value}/${name.fileName}")

private val TEST_CLOCK = object : Clock {
    override fun now(): Instant = Instant.fromEpochSeconds(1_800_000_000)
}
