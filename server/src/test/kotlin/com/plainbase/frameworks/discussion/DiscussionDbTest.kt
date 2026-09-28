package com.plainbase.frameworks.discussion

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.discussion.AnchorMatch
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionRowData
import com.plainbase.domain.discussion.EntryName
import com.plainbase.domain.discussion.EntryRowData
import com.plainbase.domain.discussion.MatchRange
import com.plainbase.domain.discussion.PageAttachment
import com.plainbase.domain.discussion.RowUpdate
import com.plainbase.domain.discussion.Stamp
import com.plainbase.domain.page.Frontmatter
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.page.RootSection
import com.plainbase.domain.root.RootName
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class DiscussionDbTest : FunSpec({
    test("a schema version mismatch drops and recreates") {
        withDatabasePath { path ->
            DiscussionDb(path).use { db ->
                val rows = JdbcDiscussionRows(db)
                saveRow(rows, id(1), "ok", pageId = pageId(1), pagePath = TreePath.require("guide.md"))
            }
            DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("UPDATE discussion_meta SET value='0' WHERE key='schema_version'")
                }
            }
            DiscussionDb(path).use { db ->
                val rows = JdbcDiscussionRows(db)
                rows.row(ROOT, id(1)) shouldBe null
                db.read { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT value FROM discussion_meta WHERE key='schema_version'").use { result ->
                            result.next()
                            result.getString(1)
                        }
                    }
                } shouldBe DiscussionDb.SCHEMA_VERSION.toString()
            }
        }
    }

    test("the page cap counts ok unreadable and failed rows with that page id") {
        withDatabasePath { path ->
            DiscussionDb(path).use { db ->
                val rows = JdbcDiscussionRows(db)
                saveRow(rows, id(1), "ok", pageId = pageId(1), commentCount = 14)
                saveRow(rows, id(2), "unreadable", pageId = pageId(1), commentCount = 14)
                saveRow(rows, id(3), "failed", pageId = pageId(1), commentCount = 14)
                saveRow(rows, id(4), "incomplete", pageId = pageId(1), commentCount = 14)

                rows.pageCount(ROOT, pageId(1)) shouldBe 3
            }
        }
    }

    test("a moved page never hides the new page's discussions") {
        withDatabasePath { path ->
            DiscussionDb(path).use { db ->
                val rows = JdbcDiscussionRows(db)
                val oldPath = TreePath.require("guide/old.md")
                val newPath = TreePath.require("guide/new.md")
                repeat(200) { index ->
                    saveRow(rows, id(index + 1), "ok", pageId = pageId(10), pagePath = oldPath)
                }
                saveRow(rows, id(201), "ok", pageId = pageId(20), pagePath = oldPath)
                val moved = page(pageId(10), newPath)
                val newAtOldPath = page(pageId(20), oldPath)
                val snapshot = PageIndex(listOf(section(listOf(moved, newAtOldPath))))

                val forNewPage = PageAttachment.rows(rows, ROOT, newAtOldPath, snapshot, after = null, limit = 200)
                forNewPage.rows.map { it.id } shouldBe listOf(id(201))
                forNewPage.next shouldBe null
                val forMovedPage = PageAttachment.rows(rows, ROOT, moved, snapshot, after = null, limit = 200)
                forMovedPage.rows.size shouldBe 200
            }
        }
    }

    test("a page lists in keyset pages of 200") {
        withDatabasePath { path ->
            DiscussionDb(path).use { db ->
                val rows = JdbcDiscussionRows(db)
                val page = page(pageId(1), TreePath.require("guide.md"))
                repeat(250) { index ->
                    saveRow(rows, id(index + 1), "ok", pageId = page.id, pagePath = page.path)
                }
                val snapshot = PageIndex(listOf(section(listOf(page))))

                val first = PageAttachment.rows(rows, ROOT, page, snapshot, after = null, limit = 200)
                first.rows.size shouldBe 200
                first.next shouldBe first.rows.last().id
                val second = PageAttachment.rows(rows, ROOT, page, snapshot, after = first.next, limit = 200)
                second.rows.size shouldBe 50
                second.next shouldBe null
                PageAttachment.rows(rows, ROOT, page, snapshot, after = second.rows.last().id, limit = 200).rows.size shouldBe 0
            }
        }
    }

    test("apply replaces entries and drops a stale match") {
        withDatabasePath { path ->
            DiscussionDb(path).use { db ->
                val rows = JdbcDiscussionRows(db)
                val currentId = id(1)
                val comment = EntryName.Comment(com.plainbase.domain.discussion.CommentId.require(COMMENT_TEXT))
                rows.writing {
                    apply(ROOT, currentId, update(pageId(1), "hash-a", listOf(entry(comment, "one"))), Stamp("s1"), dropMatch = false)
                    storeMatch(ROOT, currentId, "hash-a", "page-a", AnchorMatch.Exact(MatchRange(10, 14)))
                    apply(
                        ROOT,
                        currentId,
                        update(pageId(1), "hash-a", listOf(entry(EntryName.Marker, "two"))),
                        Stamp("s2"),
                        dropMatch = false,
                    )
                }

                rows.entry(ROOT, currentId, comment) shouldBe null
                rows.entry(ROOT, currentId, EntryName.Marker)?.version shouldBe "two"
                rows.cached(ROOT, currentId)?.match shouldBe AnchorMatch.Exact(MatchRange(10, 14))
                rows.writing {
                    apply(ROOT, currentId, update(pageId(1), "hash-b", emptyList()), Stamp("s3"), dropMatch = false)
                }
                rows.cached(ROOT, currentId) shouldBe null
            }
        }
    }

    test("a failed apply keeps the last known page") {
        withDatabasePath { path ->
            DiscussionDb(path).use { db ->
                val rows = JdbcDiscussionRows(db)
                val currentId = id(1)
                rows.writing {
                    apply(ROOT, currentId, update(pageId(2), "hash", listOf(entry(EntryName.Marker, "v1"))), Stamp("old"), false)
                    apply(ROOT, currentId, RowUpdate.Failed("EIO"), Stamp("ignored"), true)
                }

                val row = requireNotNull(rows.row(ROOT, currentId))
                row.state shouldBe "failed"
                row.reason shouldBe "EIO"
                row.stamp shouldBe null
                row.pageId shouldBe pageId(2)
                row.pagePath shouldBe TreePath.require("guide/2.md")
                row.anchorHash shouldBe "hash"
                rows.entry(ROOT, currentId, EntryName.Marker)?.version shouldBe "v1"
            }
        }
    }

    test("a match that does not decode is a miss") {
        withDatabasePath { path ->
            DiscussionDb(path).use { db ->
                val rows = JdbcDiscussionRows(db)
                val currentId = id(1)
                saveRow(rows, currentId, "ok", pageId = pageId(1))
                rows.writing { storeMatch(ROOT, currentId, "anchor", "page", AnchorMatch.PageLevel) }
                db.writing { connection ->
                    connection.prepareStatement("UPDATE anchor_match SET match='unknown' WHERE root=? AND id=?").use { statement ->
                        statement.setString(1, ROOT.value)
                        statement.setString(2, currentId.value)
                        statement.executeUpdate()
                    }
                }

                rows.cached(ROOT, currentId) shouldBe null
            }
        }
    }

    listOf(
        Triple("exact", AnchorMatch.Exact(MatchRange(4, 8)), "exact:8-4"),
        Triple("moved", AnchorMatch.Moved(MatchRange(8, 8)), "moved:8-4"),
        Triple(
            "ambiguous",
            AnchorMatch.Ambiguous(2, listOf(MatchRange(4, 8), MatchRange(8, 8)), truncated = false),
            "ambiguous:2:f:4-8,9-8",
        ),
    ).forEach { (kind, valid, reversed) ->
        test("a reversed $kind cache range is a miss") {
            withDatabasePath { path ->
                DiscussionDb(path).use { db ->
                    val rows = JdbcDiscussionRows(db)
                    val currentId = id(1)
                    saveRow(rows, currentId, "ok", pageId = pageId(1))
                    rows.writing { storeMatch(ROOT, currentId, "anchor", "page", valid) }
                    rows.cached(ROOT, currentId)?.match shouldBe valid

                    corruptMatch(db, currentId, reversed)
                    rows.cached(ROOT, currentId) shouldBe null
                }
            }
        }
    }

    test("a late match write cannot recreate a deleted discussion cache row") {
        withDatabasePath { path ->
            DiscussionDb(path).use { db ->
                val rows = JdbcDiscussionRows(db)
                val deleted = id(1)
                val live = id(2)
                saveRow(rows, deleted, "ok", pageId = pageId(1))
                saveRow(rows, live, "ok", pageId = pageId(1))

                rows.writing { apply(ROOT, deleted, RowUpdate.Delete, null, dropMatch = false) }
                rows.writing {
                    storeMatch(ROOT, deleted, "anchor", "page", AnchorMatch.PageLevel)
                    storeMatch(ROOT, live, "anchor", "page", AnchorMatch.PageLevel)
                }

                rows.row(ROOT, deleted) shouldBe null
                rows.cached(ROOT, deleted) shouldBe null
                rows.cached(ROOT, live)?.match shouldBe AnchorMatch.PageLevel
            }
        }
    }

    test("try writing returns null while the lock is held") {
        withDatabasePath { path ->
            DiscussionDb(path).use { db ->
                val rows = JdbcDiscussionRows(db)
                val locked = CountDownLatch(1)
                val release = CountDownLatch(1)
                val writer = thread {
                    rows.writing {
                        locked.countDown()
                        release.await(5, TimeUnit.SECONDS)
                    }
                }
                try {
                    locked.await(5, TimeUnit.SECONDS) shouldBe true
                    val executor = Executors.newSingleThreadExecutor()
                    try {
                        executor.submit<Int?> { rows.tryWriting { 1 } }.get(2, TimeUnit.SECONDS) shouldBe null
                    } finally {
                        executor.shutdownNow()
                    }
                } finally {
                    release.countDown()
                    writer.join(5_000)
                }
            }
        }
    }

    test("an interrupted reader returns its connection before close") {
        withDatabasePath { path ->
            val db = DiscussionDb(path)
            val readReturned = CountDownLatch(1)
            val interruptPreserved = AtomicBoolean(false)
            val reader = thread(isDaemon = true) {
                db.read {
                    Thread.currentThread().interrupt()
                }
                interruptPreserved.set(Thread.currentThread().isInterrupted)
                Thread.interrupted()
                readReturned.countDown()
            }
            reader.join(1_000)

            val closeReturned = CountDownLatch(1)
            val closer = thread(isDaemon = true) {
                db.close()
                closeReturned.countDown()
            }
            closeReturned.await(1, TimeUnit.SECONDS) shouldBe true
            readReturned.count shouldBe 0L
            interruptPreserved.get() shouldBe true
            reader.isAlive shouldBe false
            closer.isAlive shouldBe false
        }
    }
})

private const val COMMENT_TEXT = "01900000-0000-7000-8000-000000000002"
private val ROOT = RootName.PRIMARY

private fun withDatabasePath(block: (java.nio.file.Path) -> Unit) {
    val directory = Files.createTempDirectory("pb-discussion-db-test")
    try {
        block(directory.resolve("discussions.db"))
    } finally {
        directory.toFile().deleteRecursively()
    }
}

private fun id(value: Int): DiscussionId = DiscussionId.require("01900000-0000-7000-8000-${value.toString(16).padStart(12, '0')}")

private fun pageId(value: Int): PageId = PageId.require("01900000-0000-4000-8000-${value.toString(16).padStart(12, '0')}")

private fun update(
    pageId: PageId,
    anchorHash: String,
    entries: List<EntryRowData>,
) = RowUpdate.Upsert(
    DiscussionRowData(
        state = "ok",
        pageId = pageId,
        pagePath = TreePath.require("guide/${pageId.value.takeLast(1)}.md"),
        status = "open",
        anchorKind = "quote",
        anchorHash = anchorHash,
        starterKey = "sha256:starter",
        created = 1,
        updated = 2,
        commentCount = 1,
    ),
    entries,
)

private fun entry(name: EntryName, version: String) = EntryRowData(name, version, version, "sha256:author")

private fun saveRow(
    rows: JdbcDiscussionRows,
    id: DiscussionId,
    state: String,
    pageId: PageId? = null,
    pagePath: TreePath? = null,
    commentCount: Int = 1,
) {
    val update = RowUpdate.Upsert(
        DiscussionRowData(
            state = state,
            pageId = pageId,
            pagePath = pagePath,
            status = "open",
            anchorKind = "quote",
            anchorHash = "anchor-${id.value}",
            starterKey = "starter-${id.value}",
            created = 1,
            updated = 2,
            commentCount = commentCount,
        ),
        emptyList(),
    )
    rows.writing { apply(ROOT, id, update, Stamp("stamp-${id.value}"), dropMatch = false) }
}

private fun corruptMatch(db: DiscussionDb, id: DiscussionId, match: String) {
    db.writing { connection ->
        connection.prepareStatement("UPDATE anchor_match SET match=? WHERE root=? AND id=?").use { statement ->
            statement.setString(1, match)
            statement.setString(2, ROOT.value)
            statement.setString(3, id.value)
            statement.executeUpdate()
        }
    }
}

private fun page(id: PageId, path: TreePath) = IndexedPage(
    id = id,
    root = ROOT,
    path = path,
    slug = path.name.substringBeforeLast('.'),
    urlPath = path,
    title = path.name,
    frontmatter = Frontmatter.EMPTY,
    materialized = true,
    markdown = "",
    contentHash = "content-${id.value}",
    commit = null,
    html = "",
    headings = emptyList(),
    links = emptyList(),
    sections = emptyList(),
)

private fun section(pages: List<IndexedPage>) = RootSection(ROOT, pages, emptyList(), emptySet())
