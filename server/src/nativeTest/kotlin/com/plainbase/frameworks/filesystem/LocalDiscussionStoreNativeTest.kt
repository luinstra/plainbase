package com.plainbase.frameworks.filesystem

import com.plainbase.domain.discussion.CommentId
import com.plainbase.domain.discussion.DiscussionId
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
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
}

private val ROOT = RootName.PRIMARY
private val ID = DiscussionId.require("01900000-0000-7000-8000-000000000001")
private val COMMENT_ID = CommentId.require("01900000-0000-7000-8000-000000000002")
private val COMMENT_NAME = EntryName.Comment(COMMENT_ID)
private val BYTE = "comment bytes\n".encodeToByteArray()

private fun puts() = listOf(EntryPut(COMMENT_NAME, BYTE), EntryPut(EntryName.Marker, "marker bytes\n".encodeToByteArray()))

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
