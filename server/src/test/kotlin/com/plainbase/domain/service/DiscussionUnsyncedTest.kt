package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.discussion.Actor
import com.plainbase.domain.discussion.Anchor
import com.plainbase.domain.discussion.Author
import com.plainbase.domain.discussion.AuthorKind
import com.plainbase.domain.discussion.CollectionVisit
import com.plainbase.domain.discussion.CommentId
import com.plainbase.domain.discussion.CommentRecord
import com.plainbase.domain.discussion.DiscussionCodec
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionRead
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
import com.plainbase.domain.discussion.IdentityDigest
import com.plainbase.domain.discussion.PageRef
import com.plainbase.domain.discussion.RowUpdate
import com.plainbase.domain.discussion.Stamp
import com.plainbase.domain.discussion.StoreWrite
import com.plainbase.domain.page.Frontmatter
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.page.RootSection
import com.plainbase.domain.principal.SubjectKey
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.frameworks.discussion.DbFaults
import com.plainbase.frameworks.discussion.DiscussionDb
import com.plainbase.frameworks.discussion.DiscussionWorld
import com.plainbase.frameworks.discussion.JdbcDiscussionRows
import com.plainbase.frameworks.filesystem.LocalDiscussionStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class DiscussionUnsyncedTest : FunSpec({
    test("an unsynced page discussion count is refused before reading marker files") {
        withDiscussionIndexFixture { rootPath, db, baseStore ->
            var readCalls = 0
            var countCalls = 0
            val store = object : DiscussionStore by baseStore {
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead {
                    readCalls++
                    return baseStore.read(root, id, only)
                }
            }
            val baseRows = JdbcDiscussionRows(db)
            val rows = object : DiscussionRows by baseRows {
                override fun pageCount(root: RootName, pageId: PageId): Int {
                    countCalls++
                    return baseRows.pageCount(root, pageId)
                }
            }
            val sync = DiscussionSyncState(setOf(ROOT))
            sync.enter(ROOT, "previous write failed")
            val index = SyncedDiscussionIndex(rows, store, DiscussionFullReads(store), sync)

            val failure = shouldThrow<DiscussionReadFailed> { index.pageDiscussionCount(ROOT, pageId(1)) }

            failure.reason shouldBe "unsynced"
            readCalls shouldBe 0
            countCalls shouldBe 0
            sync.isUnsynced(ROOT) shouldBe true
            Files.exists(rootPath.resolve(".plainbase/discussions")) shouldBe false
        }
    }

    test("a direct publish of a failed read throws without publish failed") {
        withDiscussionIndexFixture { _, db, baseStore ->
            val store = object : DiscussionStore by baseStore {
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead =
                    EntriesRead.Failed("read failed")
            }
            val baseRows = JdbcDiscussionRows(db)
            var applyCalls = 0
            val rows = object : DiscussionRows by baseRows {
                override fun <T> writing(block: DiscussionRowWriter.() -> T): T = baseRows.writing {
                    val delegate = this
                    block(object : DiscussionRowWriter by delegate {
                        override fun apply(root: RootName, id: DiscussionId, update: RowUpdate, stamp: Stamp?, dropMatch: Boolean) {
                            applyCalls++
                            delegate.apply(root, id, update, stamp, dropMatch)
                        }
                    })
                }
            }
            val sync = DiscussionSyncState(setOf(ROOT))
            val index = SyncedDiscussionIndex(rows, store, DiscussionFullReads(store), sync)

            shouldThrow<IllegalStateException> {
                index.publish(ROOT, DISCUSSION, markerChanged = false) { store.read(ROOT, DISCUSSION) }
            }

            applyCalls shouldBe 0
            baseRows.row(ROOT, DISCUSSION) shouldBe null
            sync.isUnsynced(ROOT) shouldBe false
        }
    }

    test("a page count database failure enters unsynced and surfaces as a discussion read failure") {
        withDiscussionIndexFixtureAndDbPath { _, databasePath, db, store ->
            DbFaults(databasePath).use { faults ->
                faults.failReads()
                val sync = DiscussionSyncState(setOf(ROOT))
                val index = SyncedDiscussionIndex(JdbcDiscussionRows(db), store, DiscussionFullReads(store), sync)

                val failure = shouldThrow<DiscussionReadFailed> { index.pageDiscussionCount(ROOT, pageId(1)) }

                failure.root shouldBe ROOT
                sync.isUnsynced(ROOT) shouldBe true
            }
        }
    }

    test("a non scope root is refused by the discussion index") {
        withDiscussionIndexFixture { _, db, store ->
            val outside = RootName.require("archive")
            val index = SyncedDiscussionIndex(
                JdbcDiscussionRows(db),
                store,
                DiscussionFullReads(store),
                DiscussionSyncState(setOf(ROOT)),
            )

            val failure = shouldThrow<IllegalArgumentException> {
                index.publish(outside, DISCUSSION, markerChanged = false) { EntriesRead.Absent }
            }

            failure.message shouldBe "not a discussion root: archive"
            JdbcDiscussionRows(db).row(outside, DISCUSSION) shouldBe null
        }
    }

    test("publishing reads under the writer lock") {
        withDiscussionIndexFixture { _, db, baseStore ->
            val store = object : DiscussionStore by baseStore {
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead {
                    db.writeLock.isHeldByCurrentThread shouldBe true
                    return EntriesRead.Absent
                }
            }
            val sync = DiscussionSyncState(setOf(ROOT))
            val index = SyncedDiscussionIndex(JdbcDiscussionRows(db), store, DiscussionFullReads(store), sync)

            index.publish(ROOT, DISCUSSION, markerChanged = false) { store.read(ROOT, DISCUSSION) }

            JdbcDiscussionRows(db).row(ROOT, DISCUSSION) shouldBe null
        }
    }

    test("publishing reads the files once") {
        withDiscussionIndexFixture { _, db, baseStore ->
            writeReadableDiscussion(baseStore, DISCUSSION)
            var fileReads = 0
            var thunkCalls = 0
            val store = object : DiscussionStore by baseStore {
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead {
                    if (only == null) fileReads++
                    return baseStore.read(root, id, only)
                }
            }
            val index = SyncedDiscussionIndex(
                JdbcDiscussionRows(db),
                store,
                DiscussionFullReads(store),
                DiscussionSyncState(setOf(ROOT)),
            )

            index.publish(ROOT, DISCUSSION, markerChanged = false) {
                thunkCalls++
                store.read(ROOT, DISCUSSION)
            }

            thunkCalls shouldBe 1
            fileReads shouldBe 1
        }
    }

    test("post then an immediate list and detail are served from the files") {
        withDiscussionIndexFixture { _, db, store ->
            writeReadableDiscussion(store, DISCUSSION)
            val sync = DiscussionSyncState(setOf(ROOT)).also { it.enter(ROOT, "write failed") }
            val reads = reads(JdbcDiscussionRows(db), store, sync)

            val listed = reads.rootDiscussions(ROOT).discussions.single()
            val page = indexedPage(pageId(1))
            val pageListed = reads.pageDiscussions(ROOT, page, PageIndex(listOf(RootSection(ROOT, listOf(page), emptyList(), emptySet()))))
                .discussions.single()
            val detail = reads.detail(ROOT, DISCUSSION) as DetailPage.Content

            listed.id shouldBe DISCUSSION
            listed.commentCount shouldBe 1
            pageListed.id shouldBe DISCUSSION
            pageListed.commentCount shouldBe 1
            (detail.read as DiscussionRead.Ok).files.comments.size shouldBe 1
        }
    }

    test("detail reads the files on a synced root") {
        withDiscussionIndexFixture { _, db, store ->
            writeReadableDiscussion(store, DISCUSSION)
            val rows = JdbcDiscussionRows(db)
            rows.writing {
                apply(
                    ROOT,
                    DISCUSSION,
                    RowUpdate.Upsert(DiscussionRowData("incomplete"), emptyList()),
                    stamp = null,
                    dropMatch = false,
                )
            }
            val reads = reads(rows, store, DiscussionSyncState(setOf(ROOT)))

            val detail = reads.detail(ROOT, DISCUSSION) as DetailPage.Content

            (detail.read as DiscussionRead.Ok).files.marker.value.status shouldBe DiscussionStatus.OPEN
        }
    }

    test("an unreadable database serves facts and lists from the files") {
        withDiscussionIndexFixtureAndDbPath { _, databasePath, db, baseStore ->
            writeReadableDiscussion(baseStore, DISCUSSION)
            DbFaults(databasePath).use { faults ->
                faults.failReads()
                val sync = DiscussionSyncState(setOf(ROOT))
                val reads = reads(JdbcDiscussionRows(db), baseStore, sync)

                val facts = reads.facts(ROOT, DISCUSSION, COMMENT_ID) as DiscussionFacts.Known
                val listed = reads.rootDiscussions(ROOT).discussions.single()
                val page = indexedPage(pageId(1))
                val snapshot = PageIndex(listOf(RootSection(ROOT, listOf(page), emptyList(), emptySet())))
                val pageListed = reads.pageDiscussions(ROOT, page, snapshot).discussions.single()

                sync.isUnsynced(ROOT) shouldBe true
                facts.starterKey shouldBe IdentityDigest.of(SubjectKey("issuer-a", "starter-a"))
                facts.authorKey shouldBe IdentityDigest.of(SubjectKey("issuer-b", "commenter-b"))
                listed.commentCount shouldBe 1
                pageListed.id shouldBe DISCUSSION
            }
        }
    }

    test("facts keep known degraded states and reserve unknown for failed or symlinked reads") {
        withDiscussionIndexFixture { rootPath, db, baseStore ->
            val incompleteId = discussionId(2_001)
            val unreadableId = discussionId(2_002)
            val failedId = discussionId(2_003)
            val symlinkedId = discussionId(2_004)
            Files.createDirectories(discussionDirectory(rootPath, incompleteId))
            Files.createDirectories(discussionDirectory(rootPath, unreadableId))
            Files.write(markerPath(rootPath, unreadableId), byteArrayOf(0xff.toByte()))
            Files.createDirectories(discussionDirectory(rootPath, symlinkedId))
            val outside = Files.createTempDirectory("pb-discussion-facts-target")
            try {
                val target = Files.write(outside.resolve("discussion.md"), byteArrayOf(1))
                Files.createSymbolicLink(markerPath(rootPath, symlinkedId), target)
                val store = object : DiscussionStore by baseStore {
                    override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead =
                        if (id == failedId) EntriesRead.Failed("disk failed") else baseStore.read(root, id, only)
                }
                val sync = DiscussionSyncState(setOf(ROOT)).also { it.enter(ROOT, "force file facts") }
                val reads = reads(JdbcDiscussionRows(db), store, sync)

                val incomplete = reads.facts(ROOT, incompleteId).shouldBeInstanceOf<DiscussionFacts.Known>()
                reads.claim(ROOT, incompleteId).shouldBeInstanceOf<DiscussionClaim.Present>().facts shouldBe incomplete
                incomplete.state shouldBe "incomplete"
                incomplete.pageId shouldBe null
                incomplete.status shouldBe null
                val unreadable = reads.facts(ROOT, unreadableId).shouldBeInstanceOf<DiscussionFacts.Known>()
                reads.claim(ROOT, unreadableId).shouldBeInstanceOf<DiscussionClaim.Present>().facts shouldBe unreadable
                unreadable.state shouldBe "unreadable"
                unreadable.pageId shouldBe null
                reads.facts(ROOT, failedId) shouldBe DiscussionFacts.Unknown
                reads.facts(ROOT, symlinkedId) shouldBe DiscussionFacts.Unknown
                reads.claim(ROOT, failedId) shouldBe DiscussionClaim.Unknown
                reads.claim(ROOT, symlinkedId) shouldBe DiscussionClaim.Unknown
            } finally {
                outside.toFile().deleteRecursively()
            }
        }
    }

    test("facts return the same digests synced and unsynced") {
        withDiscussionIndexFixture { _, db, store ->
            writeReadableDiscussion(store, DISCUSSION)
            val rows = JdbcDiscussionRows(db)
            val sync = DiscussionSyncState(setOf(ROOT))
            val index = SyncedDiscussionIndex(rows, store, DiscussionFullReads(store), sync)
            index.publish(ROOT, DISCUSSION, markerChanged = false) { store.read(ROOT, DISCUSSION) }
            val reads = reads(rows, store, sync)

            val synced = reads.facts(ROOT, DISCUSSION, COMMENT_ID)
            sync.enter(ROOT, "force files arm")
            val unsynced = reads.facts(ROOT, DISCUSSION, COMMENT_ID)

            synced shouldBe unsynced
            reads.claim(ROOT, DISCUSSION, COMMENT_ID).shouldBeInstanceOf<DiscussionClaim.Present>().facts shouldBe synced
        }
    }

    test("a synced symlink row retains known presence while file fallback cannot establish it") {
        withDiscussionIndexFixture { rootPath, db, store ->
            val symlinkedId = discussionId(2_005)
            Files.createDirectories(discussionDirectory(rootPath, symlinkedId))
            val outside = Files.createTempDirectory("pb-discussion-symlink-row")
            try {
                val target = Files.write(outside.resolve("discussion.md"), byteArrayOf(1))
                Files.createSymbolicLink(markerPath(rootPath, symlinkedId), target)
                val rows = JdbcDiscussionRows(db)
                val sync = DiscussionSyncState(setOf(ROOT))
                val index = SyncedDiscussionIndex(rows, store, DiscussionFullReads(store), sync)
                index.publish(ROOT, symlinkedId, markerChanged = true) { store.read(ROOT, symlinkedId) }
                val reads = reads(rows, store, sync)

                val known = reads.claim(ROOT, symlinkedId).shouldBeInstanceOf<DiscussionClaim.Present>().facts
                    .shouldBeInstanceOf<DiscussionFacts.Known>()
                known.state shouldBe "unreadable"
                sync.enter(ROOT, "force fallback")
                reads.claim(ROOT, symlinkedId) shouldBe DiscussionClaim.Unknown
            } finally {
                outside.toFile().deleteRecursively()
            }
        }
    }

    test("a failed detail read throws") {
        withDiscussionIndexFixture { _, db, baseStore ->
            val store = object : DiscussionStore by baseStore {
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead = EntriesRead.Failed("disk failed")
            }
            val reads = reads(JdbcDiscussionRows(db), store, DiscussionSyncState(setOf(ROOT)))

            val failure = shouldThrow<DiscussionReadFailed> { reads.detail(ROOT, DISCUSSION) }

            failure.reason shouldBe "disk failed"
        }
    }

    test("a root lost mid read propagates") {
        withDiscussionIndexFixture { _, db, baseStore ->
            val store = object : DiscussionStore by baseStore {
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead =
                    throw RootUnavailable(root, UnavailableCause.VANISHED)
            }
            val reads = reads(JdbcDiscussionRows(db), store, DiscussionSyncState(setOf(ROOT)))

            shouldThrow<RootUnavailable> { reads.detail(ROOT, DISCUSSION) }
        }
    }

    test("a failed or partial visit is never served") {
        withDiscussionIndexFixture { _, db, baseStore ->
            val store = object : DiscussionStore by baseStore {
                override fun visit(root: RootName, visitor: (DiscussionId, Boolean) -> Unit): CollectionVisit {
                    visitor(DISCUSSION, false)
                    return CollectionVisit.Failed("visit failed")
                }
            }
            val sync = DiscussionSyncState(setOf(ROOT)).also { it.enter(ROOT, "rows failed") }
            val reads = reads(JdbcDiscussionRows(db), store, sync)

            val failure = shouldThrow<DiscussionReadFailed> { reads.rootDiscussions(ROOT) }

            failure.reason shouldBe "visit failed"
        }
    }

    test("a degraded root listing shows a failed id as failed") {
        withDiscussionIndexFixture { _, db, baseStore ->
            val store = object : DiscussionStore by baseStore {
                override fun visit(root: RootName, visitor: (DiscussionId, Boolean) -> Unit): CollectionVisit {
                    visitor(DISCUSSION, false)
                    return CollectionVisit.Visited(1)
                }

                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead = EntriesRead.Failed("disk failed")
            }
            val sync = DiscussionSyncState(setOf(ROOT)).also { it.enter(ROOT, "rows failed") }
            val reads = reads(JdbcDiscussionRows(db), store, sync)

            val listed = reads.rootDiscussions(ROOT).discussions.single()

            listed.state shouldBe "failed"
            listed.reason shouldBe "disk failed"
        }
    }

    test("a symlinked marker is rejected during a page listing") {
        withDiscussionIndexFixture { rootPath, db, store ->
            val id = discussionId(2_101)
            writeReadableDiscussion(store, id)
            val marker = markerPath(rootPath, id)
            val outside = Files.createTempDirectory("pb-discussion-page-marker")
            try {
                val target = Files.write(outside.resolve("discussion.md"), Files.readAllBytes(marker))
                Files.delete(marker)
                Files.createSymbolicLink(marker, target)
                val sync = DiscussionSyncState(setOf(ROOT)).also { it.enter(ROOT, "rows failed") }
                val reads = reads(JdbcDiscussionRows(db), store, sync)
                val page = indexedPage(pageId(1))
                val snapshot = PageIndex(listOf(RootSection(ROOT, listOf(page), emptyList(), emptySet())))

                val failure = shouldThrow<DiscussionReadFailed> {
                    reads.pageDiscussions(ROOT, page, snapshot)
                }

                failure.reason shouldBe "symlink"
            } finally {
                outside.toFile().deleteRecursively()
            }
        }
    }

    test("a degraded page list is bounded and keeps the keep rule") {
        withDiscussionIndexFixture { rootPath, db, store ->
            val requestedId = pageId(1)
            val otherId = pageId(2)
            val page = indexedPage(requestedId)
            val otherPage = indexedPage(otherId).copy(
                path = TreePath.require("archive.md"),
                urlPath = TreePath.require("archive"),
            )
            repeat(300) { index ->
                writeReadableDiscussion(store, discussionId(index + 1), otherId, page.path)
            }
            repeat(250) { index ->
                writeReadableDiscussion(store, discussionId(index + 1_000), requestedId, page.path)
            }
            Files.createDirectories(discussionDirectory(rootPath, discussionId(2_000)))
            val sync = DiscussionSyncState(setOf(ROOT)).also { it.enter(ROOT, "rows failed") }
            val reads = reads(JdbcDiscussionRows(db), store, sync)
            val snapshot = PageIndex(listOf(RootSection(ROOT, listOf(page, otherPage), emptyList(), emptySet())))

            val first = reads.pageDiscussions(ROOT, page, snapshot)
            val second = reads.pageDiscussions(ROOT, page, snapshot, after = first.next)

            first.discussions.size shouldBe 200
            first.discussions.all { it.id.value >= discussionId(1_000).value } shouldBe true
            second.discussions.size shouldBe 50
            second.next shouldBe null
            (first.discussions + second.discussions).map { it.id }.toSet().size shouldBe 250
            // The store returns owned byte arrays and has no release hook, so retained body liveness is not observable here.
        }
    }

    test("a marker read failure does not hide an unknown page") {
        withDiscussionIndexFixture { _, db, baseStore ->
            val store = object : DiscussionStore by baseStore {
                override fun visit(root: RootName, visitor: (DiscussionId, Boolean) -> Unit): CollectionVisit {
                    visitor(DISCUSSION, false)
                    return CollectionVisit.Visited(1)
                }

                override fun read(
                    root: RootName,
                    id: DiscussionId,
                    only: Set<EntryName>?,
                ): EntriesRead = EntriesRead.Failed("marker failed")
            }
            val sync = DiscussionSyncState(setOf(ROOT)).also { it.enter(ROOT, "rows failed") }
            val reads = reads(JdbcDiscussionRows(db), store, sync)
            val page = indexedPage(pageId(1))
            val snapshot = PageIndex(listOf(RootSection(ROOT, listOf(page), emptyList(), emptySet())))

            val failure = shouldThrow<DiscussionReadFailed> { reads.pageDiscussions(ROOT, page, snapshot) }

            failure.message shouldBe "discussion read failed for root docs: marker failed"
        }
    }

    test("an unreadable marker without a page is omitted from degraded page results") {
        withDiscussionIndexFixture { rootPath, db, store ->
            val id = discussionId(2_500)
            Files.createDirectories(discussionDirectory(rootPath, id))
            Files.write(markerPath(rootPath, id), byteArrayOf(0xff.toByte()))
            val sync = DiscussionSyncState(setOf(ROOT)).also { it.enter(ROOT, "rows failed") }
            val reads = reads(JdbcDiscussionRows(db), store, sync)
            val page = indexedPage(pageId(1))
            val snapshot = PageIndex(listOf(RootSection(ROOT, listOf(page), emptyList(), emptySet())))

            reads.pageDiscussions(ROOT, page, snapshot).discussions shouldBe emptyList()
        }
    }

    test("a disappeared full read is omitted from degraded lists") {
        withDiscussionIndexFixture { _, db, baseStore ->
            val id = discussionId(2_501)
            writeReadableDiscussion(baseStore, id)
            val store = object : DiscussionStore by baseStore {
                override fun visit(root: RootName, visitor: (DiscussionId, Boolean) -> Unit): CollectionVisit {
                    visitor(id, false)
                    return CollectionVisit.Visited(1)
                }

                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead =
                    if (only == null) EntriesRead.Absent else baseStore.read(root, id, only)
            }
            val sync = DiscussionSyncState(setOf(ROOT)).also { it.enter(ROOT, "rows failed") }
            val reads = reads(JdbcDiscussionRows(db), store, sync)
            val page = indexedPage(pageId(1))
            val snapshot = PageIndex(listOf(RootSection(ROOT, listOf(page), emptyList(), emptySet())))

            reads.rootDiscussions(ROOT).discussions shouldBe emptyList()
            reads.pageDiscussions(ROOT, page, snapshot).discussions shouldBe emptyList()
        }
    }

    test("an unavailable root returns unknown facts and refuses lists") {
        withDiscussionIndexFixture { _, db, store ->
            val availability = RootAvailability(kotlin.time.Clock.System)
            availability.markUnavailable(ROOT, UnavailableCause.VANISHED)
            val reads =
                DiscussionReads(JdbcDiscussionRows(db), store, DiscussionFullReads(store), DiscussionSyncState(setOf(ROOT)), availability)

            reads.facts(ROOT, DISCUSSION) shouldBe DiscussionFacts.Unknown
            shouldThrow<RootUnavailable> { reads.rootDiscussions(ROOT) }
            shouldThrow<RootUnavailable> { reads.detail(ROOT, DISCUSSION) }
        }
    }

    test("a comment write failure during running recovery keeps the root unsynced") {
        DiscussionWorld().use { world ->
            val id = world.startDiscussion(body = "recovery generation body\n")
            val beforeClearCalls = AtomicInteger()
            DbFaults(world.databasePath).use { faults ->
                val executor = world.executor(
                    recoveryBaseMillis = 50,
                    beforeClear = {
                        if (beforeClearCalls.incrementAndGet() == 1) {
                            faults.failWrites()
                            try {
                                world.addComment(id, "comment failure body\n")
                                    .shouldBeInstanceOf<DiscussionWriteOutcome.Done>().id shouldBe id
                            } finally {
                                faults.heal()
                            }
                        }
                    },
                )
                try {
                    executor.start()
                    world.sync.enter(DiscussionWorld.ROOT, "recovery required")
                    world.alarm.runNext(50)

                    world.sync.isUnsynced(DiscussionWorld.ROOT) shouldBe true
                    world.alarm.delays shouldBe listOf(50, 100)
                    world.rows.row(DiscussionWorld.ROOT, id)?.commentCount shouldBe 1

                    world.alarm.runNext(100)

                    world.sync.isUnsynced(DiscussionWorld.ROOT) shouldBe false
                    world.rows.row(DiscussionWorld.ROOT, id)?.commentCount shouldBe 2
                    beforeClearCalls.get() shouldBe 2
                } finally {
                    faults.heal()
                    executor.close()
                }
            }
        }
    }

    test("enters during a pending timer start one recovery") {
        DiscussionWorld().use { world ->
            val visits = AtomicInteger()
            val store = object : DiscussionStore by world.store {
                override fun visit(root: RootName, visitor: (DiscussionId, Boolean) -> Unit): CollectionVisit {
                    visits.incrementAndGet()
                    return world.store.visit(root, visitor)
                }
            }
            val executor = world.executor(store, recoveryBaseMillis = 50)
            try {
                executor.start()
                world.sync.enter(DiscussionWorld.ROOT, "first failure")
                world.sync.enter(DiscussionWorld.ROOT, "second failure while timer pending")

                world.alarm.delays shouldBe listOf(50)
                world.alarm.runNext(50)

                visits.get() shouldBe 1
                world.sync.isUnsynced(DiscussionWorld.ROOT) shouldBe false
            } finally {
                executor.close()
            }
        }
    }

    test("a successful apply resets the recovery backoff") {
        DiscussionWorld().use { world ->
            val id = world.startDiscussion(body = "backoff reset body\n")
            val visits = AtomicInteger()
            val fullReads = AtomicInteger()
            val store = object : DiscussionStore by world.store {
                override fun visit(root: RootName, visitor: (DiscussionId, Boolean) -> Unit): CollectionVisit {
                    visits.incrementAndGet()
                    return CollectionVisit.Failed("temporary visit failure")
                }

                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead {
                    if (only == null) fullReads.incrementAndGet()
                    return world.store.read(root, id, only)
                }
            }
            val executor = world.executor(store, recoveryBaseMillis = 50)
            try {
                executor.start()
                world.sync.enter(DiscussionWorld.ROOT, "initial recovery failure")
                repeat(2) { world.alarm.runNext() }
                world.alarm.delays shouldBe listOf(50, 100, 200)

                executor.discussionChanged(DiscussionWorld.ROOT, id)
                world.alarm.runNext(0)
                Thread.sleep(60)
                world.alarm.runNext(50)
                fullReads.get() shouldBe 1
                world.rows.row(DiscussionWorld.ROOT, id)?.state shouldBe "ok"

                world.alarm.runNext(200)

                visits.get() shouldBe 3
                world.alarm.delays.last() shouldBe 100
                world.sync.isUnsynced(DiscussionWorld.ROOT) shouldBe true
            } finally {
                executor.close()
            }
        }
    }
})

private val ROOT = RootName.require("docs")
private val DISCUSSION = DiscussionId.require("01900000-0000-7000-8000-000000000001")

private fun pageId(value: Int): PageId = PageId.require("01900000-0000-4000-8000-${value.toString(16).padStart(12, '0')}")

private fun withDiscussionIndexFixture(block: (Path, DiscussionDb, DiscussionStore) -> Unit) =
    withDiscussionIndexFixtureAndDbPath { root, _, db, store -> block(root, db, store) }

private fun withDiscussionIndexFixtureAndDbPath(
    block: (Path, Path, DiscussionDb, DiscussionStore) -> Unit,
) {
    val root = Files.createTempDirectory("pb-discussion-index-test")
    val databasePath = Files.createTempDirectory("pb-discussion-index-db").resolve("discussions.db")
    val store = LocalDiscussionStore(mapOf(ROOT to root))
    DiscussionDb(databasePath).use { db ->
        try {
            block(root, databasePath, db, store)
        } finally {
            root.toFile().deleteRecursively()
            databasePath.parent.toFile().deleteRecursively()
        }
    }
}

private val COMMENT_ID = CommentId.require("01900000-0000-7000-8000-000000000002")

private fun discussionDirectory(root: Path, id: DiscussionId) =
    root.resolve(".plainbase/discussions/${id.value}")

private fun markerPath(root: Path, id: DiscussionId) =
    discussionDirectory(root, id).resolve(EntryName.Marker.fileName)

private fun reads(rows: DiscussionRows, store: DiscussionStore, sync: DiscussionSyncState) = DiscussionReads(
    rows,
    store,
    DiscussionFullReads(store),
    sync,
    RootAvailability(kotlin.time.Clock.System),
)

private fun writeReadableDiscussion(
    store: DiscussionStore,
    id: DiscussionId,
    linkedPageId: PageId = pageId(1),
    linkedPagePath: TreePath = TreePath.require("guide.md"),
) {
    val starter = Author(Actor(SubjectKey("issuer-a", "starter-a"), "Starter"), AuthorKind.HUMAN)
    val commenter = Author(Actor(SubjectKey("issuer-b", "commenter-b"), "Commenter"), AuthorKind.HUMAN)
    val marker = DiscussionRecord(
        id,
        PageRef(linkedPageId, linkedPagePath),
        DiscussionStatus.OPEN,
        kotlin.time.Instant.parse("2026-09-26T10:00:00.000Z"),
        starter,
        null,
        Anchor.Page("sha256:${"a".repeat(64)}", null),
        null,
        FrontmatterExtras.NONE,
    )
    val comment = CommentRecord(
        COMMENT_ID,
        id,
        commenter,
        kotlin.time.Instant.parse("2026-09-26T10:01:00.000Z"),
        null,
        null,
        "Comment body\n",
        FrontmatterExtras.NONE,
    )
    val result = store.createFiles(
        ROOT,
        id,
        listOf(
            EntryPut(EntryName.Comment(COMMENT_ID), DiscussionCodec.encodeComment(comment)),
            EntryPut(EntryName.Marker, DiscussionCodec.encodeDiscussion(marker)),
        ),
    )
    result.shouldBeInstanceOf<StoreWrite.Written>()
}

private fun discussionId(value: Int): DiscussionId =
    DiscussionId.require("01900000-0000-7000-8000-${value.toString(16).padStart(12, '0')}")

private fun indexedPage(id: PageId) = IndexedPage(
    id = id,
    root = ROOT,
    path = TreePath.require("guide.md"),
    slug = "guide",
    urlPath = TreePath.require("guide"),
    title = "Guide",
    frontmatter = Frontmatter.EMPTY,
    materialized = true,
    markdown = "body",
    contentHash = "sha256:${"b".repeat(64)}",
    commit = null,
    html = "<p>body</p>",
    headings = emptyList(),
    links = emptyList(),
    sections = emptyList(),
)
