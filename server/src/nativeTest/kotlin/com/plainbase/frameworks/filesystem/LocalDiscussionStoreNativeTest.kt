package com.plainbase.frameworks.filesystem

import com.plainbase.domain.discussion.CollectionVisit
import com.plainbase.domain.discussion.CommentId
import com.plainbase.domain.discussion.DiscussionAssembly
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionRead
import com.plainbase.domain.discussion.EntriesRead
import com.plainbase.domain.discussion.EntryName
import com.plainbase.domain.discussion.EntryPath
import com.plainbase.domain.discussion.EntryPut
import com.plainbase.domain.discussion.StoreWrite
import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.RootUnavailable
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import java.time.Instant as JavaInstant

@Tag("native")
class LocalDiscussionStoreNativeTest {
    @Test
    fun createIsExclusiveAndLinksTheMarkerLast() {
        withRoot { root ->
            val order = mutableListOf<String>()
            val atomics = object : FileAtomics by FileAtomics.Real {
                override fun createLink(link: Path, existing: Path) {
                    order += link.fileName.toString()
                    FileAtomics.Real.createLink(link, existing)
                }
            }
            val store = LocalDiscussionStore(mapOf(ROOT to root), atomics = atomics)
            assertTrue(store.createFiles(ROOT, ID, puts()).let { it is StoreWrite.Written })
            assertEquals(listOf(COMMENT_NAME.fileName, EntryName.Marker.fileName), order)
            assertEquals(StoreWrite.Exists, store.createFiles(ROOT, ID, puts()))
        }
    }

    @Test
    fun staleVersionReplaceIsMismatchAndKeepsBytes() {
        withRoot { root ->
            val store = LocalDiscussionStore(mapOf(ROOT to root))
            store.createFiles(ROOT, ID, puts())
            val initial = entry(store, COMMENT_NAME)
            val replacement = "replacement\n".encodeToByteArray()
            assertTrue(store.replace(ROOT, EntryPath(ID, COMMENT_NAME), initial.version, replacement) is StoreWrite.Written)
            val stale = store.replace(ROOT, EntryPath(ID, COMMENT_NAME), initial.version, BYTE)
            assertTrue(stale is StoreWrite.Mismatch && stale.current != initial.version)
            assertContentEquals(replacement, entry(store, COMMENT_NAME).take())
        }
    }

    @Test
    fun purgeMovesToTombstoneAndRestoreIsByteExact() {
        withRoot { root ->
            val store = LocalDiscussionStore(mapOf(ROOT to root))
            store.createFiles(ROOT, ID, puts())
            val original = entry(store, COMMENT_NAME)
            val purged = store.purge(ROOT, EntryPath(ID, COMMENT_NAME), original.version)
            assertTrue(purged is StoreWrite.Written && purged.tombstone != null)
            val tombstone = requireNotNull((purged as StoreWrite.Written).tombstone)
            assertFalse(Files.exists(entryPath(root), LinkOption.NOFOLLOW_LINKS))
            assertTrue(store.restore(ROOT, tombstone) is StoreWrite.Written)
            assertContentEquals(BYTE, entry(store, COMMENT_NAME).take())
        }
    }

    @Test
    fun symlinkedCollectionIsRefusedAndNeverFollowed() {
        withRoot { root ->
            val outside = Files.createTempDirectory("pb-discussion-native-outside")
            try {
                Files.writeString(outside.resolve("sentinel"), "outside")
                Files.createSymbolicLink(root.resolve(".plainbase"), outside)
                val store = LocalDiscussionStore(mapOf(ROOT to root))
                val read = store.read(ROOT, ID)
                assertTrue(read is EntriesRead.Symlinked, "the symlinked collection must be refused")
                assertTrue(store.createFiles(ROOT, ID, puts()) is StoreWrite.Refused)
                assertEquals("outside", Files.readString(outside.resolve("sentinel")))
                assertFalse(Files.exists(outside.resolve("discussions"), LinkOption.NOFOLLOW_LINKS))
            } finally {
                outside.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun vanishedRootThrowsRootUnavailable() {
        withRoot { root ->
            val store = LocalDiscussionStore(mapOf(ROOT to root))
            root.toFile().deleteRecursively()
            var unavailable = false
            try {
                store.read(ROOT, ID)
            } catch (failure: Exception) {
                unavailable = failure is RootUnavailable
            }
            assertTrue(unavailable, "a vanished root must not become an absent discussion")
        }
    }

    @Test
    fun stampAndVisitAgreeWithAFullRead() {
        withRoot { root ->
            val store = LocalDiscussionStore(mapOf(ROOT to root))
            val collection = root.resolve(".plainbase/discussions")
            val largeId = discussionId(10)
            val incompleteId = discussionId(11)
            val markerLinkId = discussionId(12)
            val commentLinkId = discussionId(13)
            val tooManyId = discussionId(14)
            val ids = listOf(largeId, incompleteId, markerLinkId, commentLinkId, tooManyId)
            Files.createDirectories(collection)

            val largeDir = Files.createDirectories(collection.resolve(largeId.value))
            Files.write(largeDir.resolve(EntryName.Marker.fileName), BYTE)
            Files.write(largeDir.resolve(COMMENT_NAME.fileName), ByteArray(600 * 1024) { 0x61 })

            Files.createDirectories(collection.resolve(incompleteId.value))

            val outside = Files.createTempFile("pb-discussion-native-link", ".md")
            try {
                Files.write(outside, BYTE)
                val markerLinkDir = Files.createDirectories(collection.resolve(markerLinkId.value))
                Files.createSymbolicLink(markerLinkDir.resolve(EntryName.Marker.fileName), outside)
                val commentLinkDir = Files.createDirectories(collection.resolve(commentLinkId.value))
                Files.write(commentLinkDir.resolve(EntryName.Marker.fileName), BYTE)
                Files.createSymbolicLink(commentLinkDir.resolve(COMMENT_NAME.fileName), outside)
                val firstCommentLinkStamp = requireNotNull(store.stamp(ROOT, commentLinkId))
                Files.write(commentLinkDir.resolve(EntryName.Marker.fileName), BYTE + byteArrayOf(1))
                val changedCommentLinkStamp = requireNotNull(store.stamp(ROOT, commentLinkId))
                assertNotEquals(firstCommentLinkStamp.value, changedCommentLinkStamp.value)
                assertEquals(changedCommentLinkStamp, store.stamp(ROOT, commentLinkId))

                val tooManyDir = Files.createDirectories(collection.resolve(tooManyId.value))
                Files.write(tooManyDir.resolve(EntryName.Marker.fileName), BYTE)
                repeat(1_001) { index ->
                    val comment = EntryName.Comment(commentId(index + 1))
                    Files.write(tooManyDir.resolve(comment.fileName), byteArrayOf(0x61))
                }

                val reads = ids.associateWith { id -> store.read(ROOT, id) }
                val visited = mutableSetOf<DiscussionId>()
                val visit = store.visit(ROOT) { id, _ -> visited += id }

                assertTrue(visit is CollectionVisit.Visited)
                assertEquals(ids.toSet(), visited)
                ids.filterNot { it == tooManyId }.forEach { id -> assertTrue(store.stamp(ROOT, id) != null) }
                assertNull(store.stamp(ROOT, tooManyId))
                assertTrue(store.read(ROOT, discussionId(15)) is EntriesRead.Absent)
                assertEquals(null, store.stamp(ROOT, discussionId(15)))
                assertTrue(reads.getValue(largeId) is EntriesRead.Present)
                assertTrue(DiscussionAssembly.assemble(incompleteId, reads.getValue(incompleteId)) is DiscussionRead.Incomplete)
                assertTrue(reads.getValue(markerLinkId) is EntriesRead.Symlinked)
                assertTrue(reads.getValue(commentLinkId) is EntriesRead.Symlinked)
                assertTrue(reads.getValue(tooManyId) is EntriesRead.TooMany)
            } finally {
                Files.deleteIfExists(outside)
            }
        }
    }

    @Test
    fun aTransientChildChangesTheAgedDirectoryStamp() {
        withRoot { root ->
            val store = LocalDiscussionStore(mapOf(ROOT to root))
            val directory = Files.createDirectories(root.resolve(".plainbase/discussions/${ID.value}"))
            val marker = directory.resolve(EntryName.Marker.fileName)
            Files.write(marker, BYTE)
            val aged = FileTime.fromMillis(System.currentTimeMillis() - 10_000)
            Files.setLastModifiedTime(marker, aged)
            Files.setLastModifiedTime(directory, aged)
            val before = requireNotNull(store.stamp(ROOT, ID))
            assertFalse(before.racy)

            val transient = directory.resolve(COMMENT_NAME.fileName)
            Files.write(transient, BYTE)
            Files.setLastModifiedTime(transient, aged)
            assertContentEquals(BYTE, Files.readAllBytes(transient))
            Files.delete(transient)
            Files.setLastModifiedTime(directory, FileTime.fromMillis(System.currentTimeMillis() - 500))

            val after = requireNotNull(store.stamp(ROOT, ID))
            assertNotEquals(before.value, after.value)
            assertTrue(after.racy, "a recent directory mutation must be rechecked despite aged surviving children")
        }
    }

    @Test
    fun bootSweepRemovesOnlyAgedTempsAndEmptyDirectories() {
        withRoot { root ->
            val store = LocalDiscussionStore(mapOf(ROOT to root))
            val collection = root.resolve(".plainbase/discussions")
            val emptyId = discussionId(20)
            val keptId = discussionId(21)
            val now = Instant.fromEpochMilliseconds(2_000_000_000_000)
            val oldTemp = ".pbtmp.0123456789abcdef.tmp"
            val freshTemp = ".pbtmp.fedcba9876543210.tmp"
            val oldDir = Files.createDirectories(collection.resolve(emptyId.value))
            val keptDir = Files.createDirectories(collection.resolve(keptId.value))
            val oldPath = Files.writeString(oldDir.resolve(oldTemp), "old")
            val freshPath = Files.writeString(keptDir.resolve(freshTemp), "fresh")
            Files.setLastModifiedTime(
                oldPath,
                FileTime.from(JavaInstant.ofEpochMilli(now.toEpochMilliseconds() - 25.hours.inWholeMilliseconds)),
            )
            Files.setLastModifiedTime(freshPath, FileTime.from(JavaInstant.ofEpochMilli(now.toEpochMilliseconds())))
            Files.writeString(keptDir.resolve("keep"), "ordinary")

            store.sweepBootResidue(ROOT, now, 24.hours)

            assertFalse(Files.exists(oldPath))
            assertTrue(Files.exists(freshPath))
            assertFalse(Files.exists(oldDir))
            assertTrue(Files.exists(keptDir.resolve("keep")))
        }
    }
}

private val ROOT = RootName.PRIMARY
private val ID = DiscussionId.require("01900000-0000-7000-8000-000000000001")
private val COMMENT_ID = CommentId.require("01900000-0000-7000-8000-000000000002")
private val COMMENT_NAME = EntryName.Comment(COMMENT_ID)
private val BYTE = "comment bytes\n".encodeToByteArray()

private fun puts() = listOf(EntryPut(COMMENT_NAME, BYTE), EntryPut(EntryName.Marker, "marker bytes\n".encodeToByteArray()))

private fun discussionId(value: Int) = DiscussionId.require("01900000-0000-7000-8000-${value.toString(16).padStart(12, '0')}")

private fun commentId(value: Int) = CommentId.require("01900000-0000-7000-8000-${value.toString(16).padStart(12, '0')}")

private fun entry(store: LocalDiscussionStore, name: EntryName) =
    (store.read(ROOT, ID, setOf(name)) as EntriesRead.Present).entries.single()

private fun entryPath(root: Path) =
    root.resolve(".plainbase/discussions/${ID.value}/${COMMENT_NAME.fileName}")

private inline fun <T> withRoot(block: (Path) -> T): T {
    val root = Files.createTempDirectory("pb-discussion-native-root")
    try {
        return block(root)
    } finally {
        root.toFile().deleteRecursively()
    }
}
