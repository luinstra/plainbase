package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The C2 id_map migration (`10.sqm`) end-to-end on the xerial JDBC/JNI divergence surface: the
 * frozen one-way v10 -> v11 rebuild (and the additive C0 tables above it) must run INSIDE the image, stamp every legacy row `'main'`,
 * and leave the composite PK + UNIQUE(id) live. The pre-C2 database is built PROGRAMMATICALLY
 * (raw DriverManager DDL of the exact v10 shapes) rather than from a committed baseline: the JVM
 * test's baseline locator walks the filesystem from `user.dir`, which is brittle under the native
 * test runner, and hermetic DDL keeps the image test self-contained.
 *
 * @Tag("native") + kotlin.test only - this source set compiles INTO the native test image.
 */
@Tag("native")
class AppDbMigrationNativeTest {

    @Test
    fun `a v10 database migrates to the current schema in-image with every row stamped main and UNIQUE(id) enforced`() {
        val dir = Files.createTempDirectory("pb-native-migration")
        try {
            val dbPath = dir.resolve("plainbase.db")
            val idX = ByteArray(16) { 1 }
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { raw ->
                raw.createStatement().use { statement ->
                    // The exact v10 shapes of the six root-gaining tables (1/2/3/7/8/9.sqm lineage).
                    statement.execute(
                        "CREATE TABLE id_map (path TEXT NOT NULL PRIMARY KEY, id BLOB NOT NULL UNIQUE, materialized INTEGER NOT NULL)",
                    )
                    statement.execute(
                        "CREATE TABLE identity_issue (kind TEXT NOT NULL, path TEXT NOT NULL, other_path TEXT NOT NULL, " +
                            "page_id BLOB NOT NULL, message TEXT, UNIQUE(kind, path, other_path, page_id))",
                    )
                    statement.execute("CREATE TABLE url_alias (path TEXT NOT NULL PRIMARY KEY, id BLOB NOT NULL)")
                    statement.execute("CREATE TABLE page_checkpoint (id BLOB NOT NULL PRIMARY KEY, url_path TEXT)")
                    statement.execute(
                        "CREATE TABLE dirty_page (id BLOB NOT NULL PRIMARY KEY, path TEXT NOT NULL, " +
                            "expected_hash TEXT NOT NULL, stage TEXT NOT NULL)",
                    )
                    statement.execute("CREATE INDEX dirty_page_path ON dirty_page(path)")
                    statement.execute(
                        "CREATE TABLE proposals (id BLOB NOT NULL PRIMARY KEY, operation TEXT NOT NULL, page_id BLOB, " +
                            "base_hash TEXT, target_path TEXT NOT NULL, proposed_content BLOB NOT NULL, rationale TEXT NOT NULL, " +
                            "diff_artifact TEXT NOT NULL, status TEXT NOT NULL, author_issuer TEXT NOT NULL, " +
                            "author_external_id TEXT NOT NULL, author_label TEXT NOT NULL, approver_issuer TEXT, " +
                            "approver_external_id TEXT, decision_comment TEXT, created_at INTEGER NOT NULL, decided_at INTEGER, " +
                            "applied_commit TEXT, status_reason TEXT)",
                    )
                    statement.execute("CREATE INDEX proposals_created_at ON proposals(created_at DESC, id DESC)")
                }
                raw.prepareStatement("INSERT INTO id_map(path, id, materialized) VALUES ('guides/a.md', ?, 1)").use {
                    it.setBytes(1, idX)
                    it.executeUpdate()
                }
                raw.prepareStatement("INSERT INTO url_alias(path, id) VALUES ('guides/old', ?)").use {
                    it.setBytes(1, idX)
                    it.executeUpdate()
                }
                raw.createStatement().use {
                    it.execute(
                        "INSERT INTO identity_issue(kind, path, other_path, page_id, message) VALUES ('PATCH_REFUSED', 'guides/a.md', '', x'', 'reason')",
                    )
                }
                raw.prepareStatement("INSERT INTO page_checkpoint(id, url_path) VALUES (?, 'guides/a')").use {
                    it.setBytes(1, idX)
                    it.executeUpdate()
                }
                raw.prepareStatement(
                    "INSERT INTO dirty_page(id, path, expected_hash, stage) VALUES (?, 'guides/a.md', 'sha256:abc', 'WRITING')",
                ).use {
                    it.setBytes(1, idX)
                    it.executeUpdate()
                }
                raw.prepareStatement(
                    "INSERT INTO proposals(id, operation, target_path, proposed_content, rationale, diff_artifact, status, " +
                        "author_issuer, author_external_id, author_label, created_at) " +
                        "VALUES (?, 'EDIT', 'guides/a.md', x'01', 'r', '', 'PENDING', 'agent', '00ff', 'ci', 0)",
                ).use {
                    it.setBytes(1, ByteArray(16) { 3 })
                    it.executeUpdate()
                }
                raw.createStatement().use { it.execute("PRAGMA user_version = 10") }
            }

            DatabaseFactory.createDriver(dbPath).use { driver ->
                val db = DatabaseFactory.createDatabase(driver)
                val repo = SqlDelightIdMapRepository(db)
                val pageId = PageId.require("01010101-0101-0101-0101-010101010101")
                val migrated = RootedPath(RootName.MAIN, TreePath.require("guides/a.md"))

                assertEquals(migrated, repo.pathOf(pageId)) // stamped 'main', decodes through the typed layer
                assertEquals("reason", (repo.issues().single() as com.plainbase.domain.model.IdentityIssue.PatchRefused).message)
                assertEquals(RootName.MAIN, db.pageCheckpointQueries.selectAll().executeAsOne().root)
                assertEquals(RootName.MAIN, db.dirtyPageQueries.selectAll().executeAsOne().root)
                assertEquals(1L, driver.queryLongNative("SELECT count(*) FROM proposals WHERE root = 'main'"))
                assertEquals(13L, driver.queryLongNative("PRAGMA user_version"))

                // The composite PK is live: the same relative path inserts under another root...
                db.idMapQueries.upsertBinding(
                    root = RootName.require("extra"),
                    path = TreePath.require("guides/a.md"),
                    id = PageId.require("02020202-0202-0202-0202-020202020202"),
                    materialized = false,
                )
                assertNull(repo.find(RootedPath(RootName.require("ghost"), TreePath.require("guides/a.md"))))
                // ...and UNIQUE(id) raises through the driver when a bound id is re-claimed under a new key.
                assertFails {
                    db.idMapQueries.upsertBinding(
                        root = RootName.require("extra"),
                        path = TreePath.require("guides/copy.md"),
                        id = pageId,
                        materialized = false,
                    )
                }
                assertTrue(repo.roots().contains(RootName.MAIN))
            }
        } finally {
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

/** Raw single-value probe below the typed layer (the JVM DbTestSupport twin, local to this image test). */
private fun app.cash.sqldelight.db.SqlDriver.queryLongNative(sql: String): Long =
    executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            cursor.next()
            app.cash.sqldelight.db.QueryResult.Value(requireNotNull(cursor.getLong(0)))
        },
        parameters = 0,
    ).value
