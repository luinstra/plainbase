package com.plainbase.frameworks.discussion

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.discussion.Actor
import com.plainbase.domain.discussion.Anchor
import com.plainbase.domain.discussion.AnchorSelection
import com.plainbase.domain.discussion.Author
import com.plainbase.domain.discussion.AuthorKind
import com.plainbase.domain.discussion.DiscussionCodec
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionRecord
import com.plainbase.domain.discussion.DiscussionStatus
import com.plainbase.domain.discussion.EntriesRead
import com.plainbase.domain.discussion.EntryListing
import com.plainbase.domain.discussion.EntryName
import com.plainbase.domain.discussion.EntryPut
import com.plainbase.domain.discussion.EntryVersion
import com.plainbase.domain.discussion.FrontmatterExtras
import com.plainbase.domain.discussion.HeadingPath
import com.plainbase.domain.discussion.PageRef
import com.plainbase.domain.discussion.QuoteCapture
import com.plainbase.domain.discussion.RawEntry
import com.plainbase.domain.discussion.RowDerivation
import com.plainbase.domain.discussion.RowUpdate
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.SubjectKey
import com.plainbase.domain.root.RootName
import com.plainbase.frameworks.filesystem.LocalDiscussionStore
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

@Tag("native")
class DiscussionProjectionNativeTest {
    @Test
    fun clippedUnicodeColumnsSurviveJdbcReopen() {
        val root = RootName.require("docs")
        val id = DiscussionId.require("01900000-0000-7000-8000-000000000001")
        val capture = QuoteCapture(
            "😀".repeat(100), "", "", 0, 400, 0, 1, AnchorSelection.NARROWED,
            HeadingPath.EMPTY,
        )
        val record = DiscussionRecord(
            id,
            PageRef(PageId.require("01900000-0000-4000-8000-000000000001"), TreePath.require("guide.md")),
            DiscussionStatus.OPEN, Instant.parse("2026-09-26T10:00:00Z"),
            Author(Actor(SubjectKey("builtin", "u-1"), "é".repeat(200)), AuthorKind.HUMAN), null,
            Anchor.Quote("sha256:" + "a".repeat(64), null, capture), null, FrontmatterExtras.NONE,
        )
        val bytes = DiscussionCodec.encodeDiscussion(record)
        val derived = RowDerivation.derive(
            id,
            EntriesRead.Present(
                listOf(
                    RawEntry(EntryName.Marker, bytes, EntryVersion("sha256:" + "b".repeat(64)), true),
                ),
                0,
            ),
        )
        val update = assertIs<RowUpdate.Upsert>(derived.update)
        val path = Files.createTempDirectory("discussion-projection-native").resolve("discussions.db")
        try {
            DiscussionDb(path).use { db -> JdbcDiscussionRows(db).writing { apply(root, id, update, null, false) } }
            DiscussionDb(path).use { db ->
                val row = requireNotNull(JdbcDiscussionRows(db).row(root, id))
                assertEquals("é".repeat(128), row.starterLabel)
                assertEquals("😀".repeat(64), row.quotePreview)
                assertEquals("human", row.starterKind)
                assertTrue(requireNotNull(row.starterLabel).encodeToByteArray().size <= 256)
            }
        } finally {
            path.parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun metadataScanEnforcesGlobalSymlinkAndTooManyPrecedence() {
        val root = RootName.require("docs")
        val id = DiscussionId.require("01900000-0000-7000-8000-000000000001")
        val base = Files.createTempDirectory("discussion-metadata-native")
        try {
            val store = LocalDiscussionStore(mapOf(root to base))
            store.createFiles(root, id, listOf(EntryPut(EntryName.Marker, byteArrayOf(1))))
            val folder = base.resolve(".plainbase/discussions/${id.value}")
            val link = folder.resolve("01900000-0000-7000-8000-000000000002.md")
            Files.createSymbolicLink(link, folder.resolve("absent.md"))
            assertTrue(store.listEntries(root, id) is EntryListing.Symlinked)
            repeat(1001) { index ->
                val name = "01900000-0000-7000-8000-${(1000 + index).toString(16).padStart(12, '0')}.md"
                Files.write(folder.resolve(name), byteArrayOf(1))
            }
            val tooMany = store.listEntries(root, id)
            assertTrue(tooMany is EntryListing.TooMany)
            assertEquals(1001, tooMany.count)
            Files.delete(folder.resolve(EntryName.Marker.fileName))
            Files.createSymbolicLink(folder.resolve(EntryName.Marker.fileName), folder.resolve("absent.md"))
            val symlink = store.listEntries(root, id)
            assertTrue(symlink is EntryListing.Symlinked)
            assertEquals(EntryName.Marker.fileName, symlink.entry)
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun knownEntryReadKeepsNoFollowGuards() {
        val root = RootName.require("docs")
        val id = DiscussionId.require("01900000-0000-7000-8000-000000000001")
        val base = Files.createTempDirectory("discussion-known-entry-native")
        try {
            val store = LocalDiscussionStore(mapOf(root to base))
            store.createFiles(root, id, listOf(EntryPut(EntryName.Marker, byteArrayOf(1))))
            val folder = base.resolve(".plainbase/discussions/${id.value}")
            val marker = folder.resolve(EntryName.Marker.fileName)
            assertTrue(store.readKnownEntry(root, id, EntryName.Marker) is EntriesRead.Present)
            Files.delete(marker)
            Files.createSymbolicLink(marker, folder.resolve("absent.md"))
            assertTrue(store.readKnownEntry(root, id, EntryName.Marker) is EntriesRead.Symlinked)
            Files.delete(marker)
            Files.move(folder, folder.resolveSibling("elsewhere"))
            Files.createSymbolicLink(folder, folder.resolveSibling("elsewhere"))
            assertTrue(store.readKnownEntry(root, id, EntryName.Marker) is EntriesRead.Symlinked)
        } finally {
            base.toFile().deleteRecursively()
        }
    }
}
