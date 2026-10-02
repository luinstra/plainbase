package com.plainbase.frameworks.discussion

import com.plainbase.domain.discussion.Decoded
import com.plainbase.domain.discussion.DiscussionCodec
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionRead
import com.plainbase.domain.discussion.DiscussionRow
import com.plainbase.domain.discussion.DiscussionRowData
import com.plainbase.domain.discussion.DiscussionRows
import com.plainbase.domain.discussion.DiscussionStore
import com.plainbase.domain.discussion.EntriesRead
import com.plainbase.domain.discussion.EntryListing
import com.plainbase.domain.discussion.EntryName
import com.plainbase.domain.discussion.EntryPut
import com.plainbase.domain.discussion.Retraction
import com.plainbase.domain.discussion.RowUpdate
import com.plainbase.domain.discussion.UnreadableReason
import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.DetailPage
import com.plainbase.domain.service.DiscussionFullReads
import com.plainbase.domain.service.DiscussionReadFailed
import com.plainbase.domain.service.DiscussionReads
import com.plainbase.domain.service.DiscussionWriteOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Instant

class DiscussionReadsTest : FunSpec({
    test("indexed incomplete detail uses current files, names and count") {
        DiscussionWorld().use { world ->
            val id = world.startDiscussion()
            world.rows.writing {
                apply(
                    DiscussionWorld.ROOT, id,
                    RowUpdate.Upsert(DiscussionRowData("incomplete", commentCount = 0), emptyList()), null, false,
                )
            }
            val folder = world.rootPath.resolve(".plainbase/discussions/${id.value}")
            val marker = folder.resolve(EntryName.Marker.fileName)

            val healed = world.reads.detail(DiscussionWorld.ROOT, id).shouldBeInstanceOf<DetailPage.Content>()
            healed.read.shouldBeInstanceOf<DiscussionRead.Ok>()
            healed.summary.commentCount shouldBe 1

            Files.delete(marker)
            val missingMarker = world.reads.detail(DiscussionWorld.ROOT, id).shouldBeInstanceOf<DetailPage.Content>()
            missingMarker.read.shouldBeInstanceOf<DiscussionRead.Incomplete>()
            missingMarker.summary.commentCount shouldBe 1

            Files.createSymbolicLink(marker, folder.resolve("absent.md"))
            world.reads.detail(DiscussionWorld.ROOT, id).shouldBeInstanceOf<DetailPage.Content>()
                .read.shouldBeInstanceOf<DiscussionRead.Unreadable>().reason shouldBe UnreadableReason.SYMLINK

            folder.toFile().deleteRecursively()
            world.reads.detail(DiscussionWorld.ROOT, id) shouldBe DetailPage.Absent
        }
    }

    test("detail rejects too many present comments before reading selected bodies") {
        DiscussionWorld().use { world ->
            val id = world.startDiscussion()
            val folder = world.rootPath.resolve(".plainbase/discussions/${id.value}")
            repeat(1000) { index ->
                Files.write(folder.resolve(EntryName.Comment(DiscussionWorld.commentId(index + 1000)).fileName), byteArrayOf(1))
            }

            val detail = world.reads.detail(DiscussionWorld.ROOT, id, limit = 1)
                .shouldBeInstanceOf<DetailPage.Content>()
            detail.read.shouldBeInstanceOf<DiscussionRead.Unreadable>().reason shouldBe UnreadableReason.TOO_MANY_COMMENTS
            detail.summary.commentCount shouldBe 1001
        }
    }

    test("a synced detail reads only marker and selected body while fallback validates each body") {
        val names = CopyOnWriteArrayList<String>()
        DiscussionWorld(readEntryBytes = { path, _ ->
            names += path.fileName.toString()
            Files.readAllBytes(path)
        }).use { world ->
            val id = world.startDiscussion()
            repeat(7) { world.addComment(id, "reply $it").shouldBeInstanceOf<DiscussionWriteOutcome.Done>() }

            names.clear()
            val synced = world.reads.detail(DiscussionWorld.ROOT, id, DiscussionWorld.commentId(107), 1)
                .shouldBeInstanceOf<DetailPage.Content>()
            val selected = synced.read.shouldBeInstanceOf<DiscussionRead.Ok>().files.comments
            selected.map { it.value.id } shouldBe listOf(DiscussionWorld.commentId(108))
            synced.summary.commentCount shouldBe 8
            names.sorted() shouldBe listOf(EntryName.Comment(DiscussionWorld.commentId(108)).fileName, "discussion.md")

            names.clear()
            val firstSeven = world.reads.detail(DiscussionWorld.ROOT, id, limit = 7)
                .shouldBeInstanceOf<DetailPage.Content>()
            firstSeven.read.shouldBeInstanceOf<DiscussionRead.Ok>().files.comments.size shouldBe 7
            firstSeven.nextComment shouldBe DiscussionWorld.commentId(107)
            names.size shouldBe 8

            world.sync.enter(DiscussionWorld.ROOT, "force file fallback")
            names.clear()
            val fallback = world.reads.detail(DiscussionWorld.ROOT, id, DiscussionWorld.commentId(107), 1)
                .shouldBeInstanceOf<DetailPage.Content>()
            fallback.read.shouldBeInstanceOf<DiscussionRead.Ok>().files.comments.map { it.value.id } shouldBe
                listOf(DiscussionWorld.commentId(108))
            fallback.summary.commentCount shouldBe 8
            names.size shouldBe 9
        }
    }

    test("root filter uses 200 inspected rows and advances without skipping later matches") {
        DiscussionWorld().use { world ->
            world.rows.writing {
                repeat(250) { index ->
                    apply(
                        DiscussionWorld.ROOT, DiscussionWorld.discussionId(index + 1),
                        RowUpdate.Upsert(DiscussionRowData("ok"), emptyList()), null, false,
                    )
                }
            }
            var inspected = 0
            val empty = world.reads.rootDiscussions(DiscussionWorld.ROOT, limit = 50) {
                inspected++
                false
            }
            inspected shouldBe 200
            empty.discussions shouldBe emptyList()
            empty.next shouldBe DiscussionWorld.discussionId(200)

            val tail = world.reads.rootDiscussions(DiscussionWorld.ROOT, after = empty.next, limit = 50) {
                it.id == DiscussionWorld.discussionId(250)
            }
            tail.discussions.map { it.id } shouldBe listOf(DiscussionWorld.discussionId(250))
            tail.next shouldBe null

            val all = mutableListOf<DiscussionId>()
            var cursor: DiscussionId? = null
            do {
                val page = world.reads.rootDiscussions(DiscussionWorld.ROOT, cursor, 50)
                all += page.discussions.map { it.id }
                cursor = page.next
            } while (cursor != null)
            all shouldBe (1..250).map(DiscussionWorld::discussionId)

            val wanted = (1..250).filter { it % 3 == 0 }.mapTo(mutableSetOf(), DiscussionWorld::discussionId)
            val mixed = mutableListOf<DiscussionId>()
            cursor = null
            do {
                val page = world.reads.rootDiscussions(DiscussionWorld.ROOT, cursor, 7) { it.id in wanted }
                mixed += page.discussions.map { it.id }
                cursor = page.next
            } while (cursor != null)
            mixed shouldBe wanted.sortedBy { it.value }
        }
    }

    test("degraded root listing stops file reads as soon as output limit is reached") {
        DiscussionWorld().use { world ->
            repeat(5) { world.startDiscussion() }
            world.sync.enter(DiscussionWorld.ROOT, "force file fallback")
            var fullReads = 0
            val counting = object : DiscussionStore by world.store {
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead {
                    if (only == null) fullReads++
                    return world.store.read(root, id, only)
                }
            }
            val reads = DiscussionReads(world.rows, counting, DiscussionFullReads(counting), world.sync, world.availability)

            reads.rootDiscussions(DiscussionWorld.ROOT, limit = 1).discussions.size shouldBe 1
            fullReads shouldBe 1
        }
    }

    test("degraded root filter advances after 200 nonmatching files without skipping a later hit") {
        DiscussionWorld().use { world ->
            val first = world.startDiscussion()
            val marker = (world.store.read(DiscussionWorld.ROOT, first, setOf(EntryName.Marker)) as EntriesRead.Present)
                .entries.single()
            val template = (DiscussionCodec.decodeDiscussion(marker.take()) as Decoded.Ok).value
            repeat(249) { index ->
                val id = DiscussionWorld.discussionId(index + 2)
                world.store.createFiles(
                    DiscussionWorld.ROOT, id,
                    listOf(EntryPut(EntryName.Marker, DiscussionCodec.encodeDiscussion(template.copy(id = id)))),
                )
            }
            world.sync.enter(DiscussionWorld.ROOT, "force file fallback")
            var inspected = 0

            val empty = world.reads.rootDiscussions(DiscussionWorld.ROOT, limit = 50) {
                inspected++
                false
            }
            inspected shouldBe 200
            empty.discussions shouldBe emptyList()
            empty.next shouldBe DiscussionWorld.discussionId(200)

            val remaining = world.reads.rootDiscussions(DiscussionWorld.ROOT, after = empty.next, limit = 50) {
                it.id == DiscussionWorld.discussionId(250)
            }
            remaining.discussions.map { it.id } shouldBe listOf(DiscussionWorld.discussionId(250))
            remaining.next shouldBe null
        }
    }

    test("projection callback failures do not mark a synced root unsynced or retry file reads") {
        DiscussionWorld().use { world ->
            world.startDiscussion()
            var fileReads = 0
            val counting = object : DiscussionStore by world.store {
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead {
                    fileReads++
                    return world.store.read(root, id, only)
                }
            }
            val reads = DiscussionReads(world.rows, counting, DiscussionFullReads(counting), world.sync, world.availability)

            shouldThrow<IllegalStateException> {
                reads.rootDiscussions(DiscussionWorld.ROOT, limit = 1) { error("projection failed") }
            }
            world.sync.isUnsynced(DiscussionWorld.ROOT) shouldBe false
            fileReads shouldBe 0
        }
    }

    test("a rows query failure still enters file fallback") {
        DiscussionWorld().use { world ->
            val id = world.startDiscussion()
            val broken = object : DiscussionRows by world.rows {
                override fun rowsAfter(root: RootName, after: DiscussionId?, limit: Int): List<DiscussionRow> =
                    error("database unavailable")
            }
            val reads = DiscussionReads(broken, world.store, world.fullReads, world.sync, world.availability)

            reads.rootDiscussions(DiscussionWorld.ROOT, limit = 1).discussions.map { it.id } shouldBe listOf(id)
            world.sync.isUnsynced(DiscussionWorld.ROOT) shouldBe true
        }
    }

    test("synced root listing fetches only inspected rows and one ID lookahead") {
        DiscussionWorld().use { world ->
            world.rows.writing {
                repeat(250) { index ->
                    apply(
                        DiscussionWorld.ROOT, DiscussionWorld.discussionId(index + 1),
                        RowUpdate.Upsert(DiscussionRowData("ok"), emptyList()), null, false,
                    )
                }
            }
            var fetchedRows = 0
            var lookaheadIds = 0
            val counted = object : DiscussionRows by world.rows {
                override fun rowsAfter(root: RootName, after: DiscussionId?, limit: Int): List<DiscussionRow> =
                    world.rows.rowsAfter(root, after, limit).also { fetchedRows += it.size }

                override fun ids(root: RootName, after: DiscussionId?, limit: Int): List<DiscussionId> =
                    world.rows.ids(root, after, limit).also { lookaheadIds += it.size }
            }
            val reads = DiscussionReads(counted, world.store, world.fullReads, world.sync, world.availability)

            val page = reads.rootDiscussions(DiscussionWorld.ROOT, limit = 50)
            page.discussions.size shouldBe 50
            page.next shouldBe DiscussionWorld.discussionId(50)
            fetchedRows shouldBe 50
            lookaheadIds shouldBe 1
        }
    }

    test("detail retries a selected-read change once and never returns a partial window") {
        DiscussionWorld().use { world ->
            val id = world.startDiscussion()
            var selectedReads = 0
            val changing = object : DiscussionStore by world.store {
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead {
                    if (only != null && only.size > 1) {
                        selectedReads++
                        if (selectedReads == 1) return EntriesRead.Present(emptyList(), 1)
                    }
                    return world.store.read(root, id, only)
                }
            }
            val reads = DiscussionReads(world.rows, changing, DiscussionFullReads(changing), world.sync, world.availability)
            reads.detail(DiscussionWorld.ROOT, id, limit = 1).shouldBeInstanceOf<DetailPage.Content>()
                .read.shouldBeInstanceOf<DiscussionRead.Ok>().files.comments.size shouldBe 1
            selectedReads shouldBe 2
        }
    }

    test("detail refuses a second selected-read change") {
        DiscussionWorld().use { world ->
            val id = world.startDiscussion()
            var selectedReads = 0
            val changing = object : DiscussionStore by world.store {
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead {
                    if (only != null && only.size > 1) {
                        selectedReads++
                        return EntriesRead.Present(emptyList(), 1)
                    }
                    return world.store.read(root, id, only)
                }
            }
            val reads = DiscussionReads(world.rows, changing, DiscussionFullReads(changing), world.sync, world.availability)
            shouldThrow<DiscussionReadFailed> { reads.detail(DiscussionWorld.ROOT, id, limit = 1) }
            selectedReads shouldBe 2
        }
    }

    test("detail retries a changed membership snapshot") {
        DiscussionWorld().use { world ->
            val id = world.startDiscussion()
            var scans = 0
            val changing = object : DiscussionStore by world.store {
                override fun listEntries(root: RootName, id: DiscussionId): EntryListing {
                    scans++
                    val actual = world.store.listEntries(root, id)
                    return if (scans == 2) {
                        (actual as EntryListing.Present).copy(commentCount = actual.commentCount + 1)
                    } else {
                        actual
                    }
                }
            }
            val reads = DiscussionReads(world.rows, changing, DiscussionFullReads(changing), world.sync, world.availability)
            reads.detail(DiscussionWorld.ROOT, id, limit = 1).shouldBeInstanceOf<DetailPage.Content>()
                .read.shouldBeInstanceOf<DiscussionRead.Ok>().files.comments.size shouldBe 1
            scans shouldBe 4
        }
    }

    test("an unselected recognized symlink is visible to detail") {
        DiscussionWorld().use { world ->
            val id = world.startDiscussion()
            val folder = world.rootPath.resolve(".plainbase/discussions/${id.value}")
            Files.createSymbolicLink(
                folder.resolve(EntryName.Comment(DiscussionWorld.commentId(999)).fileName),
                folder.resolve("absent.md"),
            )

            val detail = world.reads.detail(DiscussionWorld.ROOT, id, limit = 1)
                .shouldBeInstanceOf<DetailPage.Content>()
            detail.read.shouldBeInstanceOf<DiscussionRead.Unreadable>().reason shouldBe UnreadableReason.SYMLINK
        }
    }

    test("indexed detail trusts prior unselected validity until reparse but detects selected corruption") {
        DiscussionWorld().use { world ->
            val id = world.startDiscussion()
            world.addComment(id, "second reply").shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
            val folder = world.rootPath.resolve(".plainbase/discussions/${id.value}")
            val second = folder.resolve(EntryName.Comment(DiscussionWorld.commentId(102)).fileName)
            Files.write(second, byteArrayOf(0xc3.toByte(), 0x28))

            val before = world.reads.detail(DiscussionWorld.ROOT, id, limit = 1)
                .shouldBeInstanceOf<DetailPage.Content>()
            before.read.shouldBeInstanceOf<DiscussionRead.Ok>().files.comments.size shouldBe 1

            world.reparser().reparseOne(DiscussionWorld.ROOT, id)
            val after = world.reads.detail(DiscussionWorld.ROOT, id, limit = 1)
                .shouldBeInstanceOf<DetailPage.Content>()
            after.read.shouldBeInstanceOf<DiscussionRead.Unreadable>()
            after.summary.state shouldBe "unreadable"

            val first = folder.resolve(EntryName.Comment(DiscussionWorld.commentId(101)).fileName)
            Files.write(first, byteArrayOf(0xc3.toByte(), 0x28))
            // Re-publishing a clean indexed marker is not required to see corruption in the selected file.
            world.rows.writing {
                apply(
                    DiscussionWorld.ROOT, id,
                    RowUpdate.Upsert(
                        DiscussionRowData(
                            "ok", pageId = world.page.pageId,
                            pagePath = world.page.path, commentCount = 2,
                        ),
                        emptyList(),
                    ),
                    null, false,
                )
            }
            val selected = world.reads.detail(DiscussionWorld.ROOT, id, limit = 1)
                .shouldBeInstanceOf<DetailPage.Content>()
            selected.read.shouldBeInstanceOf<DiscussionRead.Unreadable>()
            selected.summary.state shouldBe "unreadable"
        }
    }

    test("synced detail updated time includes fresh selected comment timestamps") {
        DiscussionWorld().use { world ->
            val id = world.startDiscussion()
            val name = EntryName.Comment(DiscussionWorld.commentId(101))
            val file = world.rootPath.resolve(".plainbase/discussions/${id.value}/${name.fileName}")
            val raw = (world.store.read(DiscussionWorld.ROOT, id, setOf(name)) as EntriesRead.Present).entries.single()
            val comment = (DiscussionCodec.decodeComment(raw.take()) as Decoded.Ok).value
            val created = Instant.parse("2026-09-27T10:00:00Z")
            val edited = Instant.parse("2026-09-28T10:00:00Z")
            val retracted = Instant.parse("2026-09-29T10:00:00Z")
            val variants = listOf(
                comment.copy(created = created) to created,
                comment.copy(created = created, editedAt = edited) to edited,
                comment.copy(created = created, editedAt = edited, retraction = Retraction(world.actor.actor, retracted)) to retracted,
            )
            variants.forEach { (fresh, expected) ->
                Files.write(file, DiscussionCodec.encodeComment(fresh))
                val detail = world.reads.detail(DiscussionWorld.ROOT, id, limit = 1)
                    .shouldBeInstanceOf<DetailPage.Content>()
                detail.summary.updated shouldBe expected.toEpochMilliseconds()
            }
        }
    }

    test("streamed fallback scans directory names twice while validating 1000 bodies") {
        val inspectedNames = AtomicInteger()
        DiscussionWorld(onEntryNameScanned = { inspectedNames.incrementAndGet() }).use { world ->
            val id = world.startDiscussion()
            val firstName = EntryName.Comment(DiscussionWorld.commentId(101))
            val first = (world.store.read(DiscussionWorld.ROOT, id, setOf(firstName)) as EntriesRead.Present)
                .entries.single()
            val comment = (DiscussionCodec.decodeComment(first.take()) as Decoded.Ok).value
            val folder = world.rootPath.resolve(".plainbase/discussions/${id.value}")
            repeat(999) { index ->
                val name = EntryName.Comment(DiscussionWorld.commentId(index + 1000))
                Files.write(folder.resolve(name.fileName), DiscussionCodec.encodeComment(comment.copy(id = name.id)))
            }
            world.sync.enter(DiscussionWorld.ROOT, "force file fallback")
            inspectedNames.set(0)

            val detail = world.reads.detail(DiscussionWorld.ROOT, id, limit = 1)
                .shouldBeInstanceOf<DetailPage.Content>()
            detail.read.shouldBeInstanceOf<DiscussionRead.Ok>().files.comments.size shouldBe 1
            detail.summary.commentCount shouldBe 1000
            inspectedNames.get() shouldBe 2002
        }
    }

    test("streamed detail prefers a malformed comment over a marker identity mismatch") {
        DiscussionWorld().use { world ->
            val id = world.startDiscussion()
            val markerName = EntryName.Marker
            val marker = (world.store.read(DiscussionWorld.ROOT, id, setOf(markerName)) as EntriesRead.Present)
                .entries.single()
            val record = (DiscussionCodec.decodeDiscussion(marker.take()) as Decoded.Ok).value
            val folder = world.rootPath.resolve(".plainbase/discussions/${id.value}")
            Files.write(
                folder.resolve(markerName.fileName),
                DiscussionCodec.encodeDiscussion(record.copy(id = DiscussionWorld.discussionId(99))),
            )
            val commentName = EntryName.Comment(DiscussionWorld.commentId(101))
            Files.write(folder.resolve(commentName.fileName), byteArrayOf(0xff.toByte()))
            world.sync.enter(DiscussionWorld.ROOT, "force file fallback")

            val unreadable = world.reads.detail(DiscussionWorld.ROOT, id).shouldBeInstanceOf<DetailPage.Content>()
                .read.shouldBeInstanceOf<DiscussionRead.Unreadable>()
            unreadable.entry shouldBe commentName.fileName
        }
    }

    test("streamed detail prefers a later decode failure over an earlier comment mismatch") {
        DiscussionWorld().use { world ->
            val id = world.startDiscussion()
            world.addComment(id, "second reply")
            val firstName = EntryName.Comment(DiscussionWorld.commentId(101))
            val first = (world.store.read(DiscussionWorld.ROOT, id, setOf(firstName)) as EntriesRead.Present)
                .entries.single()
            val comment = (DiscussionCodec.decodeComment(first.take()) as Decoded.Ok).value
            val folder = world.rootPath.resolve(".plainbase/discussions/${id.value}")
            Files.write(
                folder.resolve(firstName.fileName),
                DiscussionCodec.encodeComment(comment.copy(discussionId = DiscussionWorld.discussionId(99))),
            )
            val secondName = EntryName.Comment(DiscussionWorld.commentId(102))
            Files.write(folder.resolve(secondName.fileName), byteArrayOf(0xff.toByte()))
            world.sync.enter(DiscussionWorld.ROOT, "force file fallback")

            val unreadable = world.reads.detail(DiscussionWorld.ROOT, id).shouldBeInstanceOf<DetailPage.Content>()
                .read.shouldBeInstanceOf<DiscussionRead.Unreadable>()
            unreadable.entry shouldBe secondName.fileName
        }
    }
})
