package com.plainbase.frameworks.discussion

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.discussion.AnchorMatch
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionPersistenceFailure
import com.plainbase.domain.discussion.DiscussionRowData
import com.plainbase.domain.discussion.EntryRowData
import com.plainbase.domain.discussion.MatchRange
import com.plainbase.domain.discussion.RowUpdate
import com.plainbase.domain.discussion.Stamp
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Tag("native")
class DiscussionDbNativeTest {
    @Test
    fun schemaVersionMismatchDropsAndRecreatesTables() {
        withDatabase { path ->
            DiscussionDb(path).use { db ->
                val rows = JdbcDiscussionRows(db)
                rows.writing {
                    apply(ROOT, ID, update(), Stamp("before"), dropMatch = false)
                }
            }
            DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("UPDATE discussion_meta SET value='0' WHERE key='schema_version'")
                }
            }
            DiscussionDb(path).use { db ->
                assertEquals(null, JdbcDiscussionRows(db).row(ROOT, ID))
                val version = db.read { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT value FROM discussion_meta WHERE key='schema_version'").use { result ->
                            assertTrue(result.next())
                            result.getString(1)
                        }
                    }
                }
                assertEquals(DiscussionDb.SCHEMA_VERSION.toString(), version)
            }
        }
    }

    @Test
    fun upsertThenPageCountRoundTripsUnderWal() {
        withDatabase { path ->
            DiscussionDb(path).use { db ->
                val rows = JdbcDiscussionRows(db)
                rows.writing {
                    apply(ROOT, ID, update(), Stamp("stamp"), dropMatch = false)
                }
                val row = assertNotNull(rows.row(ROOT, ID))
                assertEquals(PAGE, row.pageId)
                assertEquals("ok", row.state)
                assertEquals(1, rows.pageCount(ROOT, PAGE))
                val journal = db.read { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("PRAGMA journal_mode").use { result ->
                            assertTrue(result.next())
                            result.getString(1)
                        }
                    }
                }
                assertEquals("wal", journal.lowercase())
            }
        }
    }

    @Test
    fun injectedTriggerFailureSurfacesAsPersistenceFailure() {
        withDatabase { path ->
            DiscussionDb(path).use { db ->
                val rows = JdbcDiscussionRows(db)
                db.writing { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute(
                            "CREATE TRIGGER reject_discussion BEFORE INSERT ON discussion " +
                                "BEGIN SELECT RAISE(ABORT, 'injected trigger failure'); END",
                        )
                    }
                }
                var persistenceFailure: DiscussionPersistenceFailure? = null
                try {
                    rows.writing { apply(ROOT, ID, update(), Stamp("stamp"), dropMatch = false) }
                } catch (failure: DiscussionPersistenceFailure) {
                    persistenceFailure = failure
                }
                assertNotNull(persistenceFailure, "the JDBC trigger failure must reach the caller")
                assertTrue(persistenceFailure.cause is SQLException, "the SQL cause must be preserved")
                assertEquals(null, rows.row(ROOT, ID))
            }
        }
    }

    @Test
    fun deletedDiscussionRejectsLateMatchWrite() {
        withDatabase { path ->
            DiscussionDb(path).use { db ->
                val rows = JdbcDiscussionRows(db)
                val live = DiscussionId.require("01900000-0000-7000-8000-000000000002")
                rows.writing {
                    apply(ROOT, ID, update(), Stamp("before-delete"), dropMatch = false)
                    apply(ROOT, live, update(), Stamp("live"), dropMatch = false)
                    apply(ROOT, ID, RowUpdate.Delete, null, dropMatch = false)
                    storeMatch(ROOT, ID, "anchor", "page", AnchorMatch.PageLevel)
                    storeMatch(ROOT, live, "anchor", "page", AnchorMatch.PageLevel)
                }

                assertEquals(null, rows.row(ROOT, ID))
                assertEquals(null, rows.cached(ROOT, ID))
                assertEquals(AnchorMatch.PageLevel, rows.cached(ROOT, live)?.match)
            }
        }
    }

    @Test
    fun reversedCachedRangesAreMisses() {
        withDatabase { path ->
            DiscussionDb(path).use { db ->
                val rows = JdbcDiscussionRows(db)
                rows.writing { apply(ROOT, ID, update(), Stamp("stamp"), dropMatch = false) }
                val cases = listOf(
                    AnchorMatch.Exact(MatchRange(4, 8)) to "exact:8-4",
                    AnchorMatch.Moved(MatchRange(8, 8)) to "moved:8-4",
                    AnchorMatch.Ambiguous(2, listOf(MatchRange(4, 8), MatchRange(8, 8)), false) to
                        "ambiguous:2:f:4-8,9-8",
                )
                for ((valid, reversed) in cases) {
                    rows.writing { storeMatch(ROOT, ID, "anchor", "page", valid) }
                    assertEquals(valid, rows.cached(ROOT, ID)?.match)
                    db.writing { connection ->
                        connection.prepareStatement("UPDATE anchor_match SET match=? WHERE root=? AND id=?").use { statement ->
                            statement.setString(1, reversed)
                            statement.setString(2, ROOT.value)
                            statement.setString(3, ID.value)
                            statement.executeUpdate()
                        }
                    }
                    assertEquals(null, rows.cached(ROOT, ID))
                }
            }
        }
    }
}

private val ROOT = RootName.PRIMARY
private val ID = DiscussionId.require("01900000-0000-7000-8000-000000000001")
private val PAGE = PageId.require("01900000-0000-4000-8000-000000000002")

private fun update() = RowUpdate.Upsert(
    DiscussionRowData(
        state = "ok",
        pageId = PAGE,
        pagePath = TreePath.require("guide/native.md"),
        status = "open",
        anchorKind = "quote",
        anchorHash = "anchor-hash",
        starterKey = "sha256:starter",
        created = 1,
        updated = 2,
        commentCount = 1,
    ),
    listOf(EntryRowData(com.plainbase.domain.discussion.EntryName.Marker, "version", "sha256:version", "sha256:author")),
)

private inline fun withDatabase(block: (java.nio.file.Path) -> Unit) {
    val directory = Files.createTempDirectory("pb-discussion-db-native")
    try {
        block(directory.resolve("discussions.db"))
    } finally {
        directory.toFile().deleteRecursively()
    }
}
