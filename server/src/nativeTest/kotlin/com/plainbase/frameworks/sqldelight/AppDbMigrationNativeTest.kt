package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.BindingEpoch
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The id_map migration chain end-to-end on the xerial JDBC/JNI divergence surface: the frozen one-way
 * v10 -> v11 rebuild (and the additive C0 tables above it) must run INSIDE the image, stamp every legacy row `'main'`,
 * and the full chain must leave the composite PK + UNIQUE(id, root) live (per-root identity, C5; `16.sqm` drops the
 * bare UNIQUE(id)). The pre-C2 database is built PROGRAMMATICALLY
 * (raw DriverManager DDL of the exact v10 shapes) rather than from a committed baseline: the JVM
 * test's baseline locator walks the filesystem from `user.dir`, which is brittle under the native
 * test runner, and hermetic DDL keeps the image test self-contained.
 *
 * @Tag("native") + kotlin.test only - this source set compiles INTO the native test image.
 */
@Tag("native")
class AppDbMigrationNativeTest {

    @Test
    fun `a v10 database migrates to the current schema in-image with every row stamped main and UNIQUE(id, root) enforced`() {
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
                val migrated = RootedPath(RootName.require("main"), TreePath.require("guides/a.md"))

                assertEquals(
                    migrated,
                    repo.bindingInRoot(RootName.require("main"), pageId)?.path, // stamped 'main', decodes through the typed layer
                )
                assertEquals("reason", (repo.issues().single() as com.plainbase.domain.model.IdentityIssue.PatchRefused).message)
                assertEquals(RootName.require("main"), db.pageCheckpointQueries.selectAll().executeAsOne().root)
                assertEquals(RootName.require("main"), db.dirtyPageQueries.selectAll().executeAsOne().root)
                assertEquals(1L, driver.queryLongNative("SELECT count(*) FROM proposals WHERE root = 'main'"))
                assertEquals(18L, driver.queryLongNative("PRAGMA user_version"))
                // C4 (15.sqm): the retired_binding_id index exists in-image after the full chain.
                assertEquals(
                    1L,
                    driver.queryLongNative("SELECT count(*) FROM sqlite_master WHERE type='index' AND name='retired_binding_id'"),
                )

                // The (root, id) composite PK enforces in-image on all three re-keyed tables: the same id under a
                // SECOND root inserts cleanly, but the same (root, id) twice FAILS - proven with a PLAIN RAW INSERT,
                // since the generated upsert/retire are INSERT OR REPLACE / ON CONFLICT DO UPDATE and never throw.
                val hexX = "x'01010101010101010101010101010101'" // idX, present under 'main' post-migration
                val hexW = "x'02020202020202020202020202020202'"
                driver.execute(
                    null,
                    "INSERT INTO dirty_page(id, root, path, expected_hash, stage) VALUES ($hexX, 'extra', 'g', 'h', 'WRITING')",
                    0,
                )
                assertFails {
                    driver.execute(
                        null,
                        "INSERT INTO dirty_page(id, root, path, expected_hash, stage) VALUES ($hexX, 'main', 'g2', 'h', 'WRITING')",
                        0,
                    )
                }
                driver.execute(null, "INSERT INTO page_checkpoint(id, root, url_path) VALUES ($hexX, 'extra', 'g')", 0)
                assertFails {
                    driver.execute(null, "INSERT INTO page_checkpoint(id, root, url_path) VALUES ($hexX, 'main', 'g2')", 0)
                }
                driver.execute(
                    null,
                    "INSERT INTO retired_binding(id, root, path, materialized, retired_at) VALUES ($hexW, 'main', 'g', 1, 1)",
                    0,
                )
                driver.execute(
                    null,
                    "INSERT INTO retired_binding(id, root, path, materialized, retired_at) VALUES ($hexW, 'extra', 'g', 1, 1)",
                    0,
                )
                assertFails {
                    driver.execute(
                        null,
                        "INSERT INTO retired_binding(id, root, path, materialized, retired_at) VALUES ($hexW, 'main', 'g2', 1, 1)",
                        0,
                    )
                }

                // url_alias.target_root round-trips: the seeded ('guides/old', idX) alias backfills to idX's REAL
                // root (main, from id_map), and find() carries it back as the target's rooted id.
                assertEquals(
                    RootedPageId(RootName.require("main"), pageId),
                    SqlDelightUrlAliasRepository(db).find(RootedPath(RootName.require("main"), TreePath.require("guides/old"))),
                )

                // The composite PK is live: the same relative path inserts under another root...
                db.idMapQueries.upsertBinding(
                    root = RootName.require("extra"),
                    path = TreePath.require("guides/a.md"),
                    id = PageId.require("02020202-0202-0202-0202-020202020202"),
                    materialized = false,
                )
                assertNull(repo.find(RootedPath(RootName.require("ghost"), TreePath.require("guides/a.md"))))
                // UNIQUE is now (id, root) (per-root identity, C5): the SAME id inserts CLEANLY under a DIFFERENT
                // root - the flip legalizes the cross-root duplicate...
                db.idMapQueries.upsertBinding(
                    root = RootName.require("extra"),
                    path = TreePath.require("guides/copy.md"),
                    id = pageId,
                    materialized = false,
                )
                assertNotNull(repo.bindingInRoot(RootName.require("extra"), pageId)) // pageId now lives in BOTH roots
                // ...but re-claiming it under its OWN root at a new path still raises - one id per root.
                assertFails {
                    db.idMapQueries.upsertBinding(
                        root = RootName.require("main"),
                        path = TreePath.require("guides/copy2.md"),
                        id = pageId,
                        materialized = false,
                    )
                }
                assertTrue(repo.roots().contains(RootName.require("main")))
            }
        } finally {
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `a v16 database migrates to v17 in-image - the row survives and a cross-root duplicate id becomes INSERTABLE`() {
        val dir = Files.createTempDirectory("pb-native-migration-v16")
        try {
            val dbPath = dir.resolve("plainbase.db")
            val idX = ByteArray(16) { 7 }
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { raw ->
                // The v16 id_map shape: composite PK (root, path) plus the OLD bare UNIQUE(id) that 16.sqm drops.
                // root_observation in its v16 shape (no binding_epoch) so the 17.sqm ALTER below has a table to touch.
                raw.createStatement().use { statement ->
                    statement.execute(
                        "CREATE TABLE id_map (root TEXT NOT NULL, path TEXT NOT NULL, id BLOB NOT NULL, " +
                            "materialized INTEGER NOT NULL, PRIMARY KEY (root, path), UNIQUE (id))",
                    )
                    statement.execute("CREATE TABLE root_observation (root TEXT NOT NULL PRIMARY KEY, observation_id INTEGER NOT NULL)")
                }
                raw.prepareStatement("INSERT INTO id_map(root, path, id, materialized) VALUES ('main', 'guides/a.md', ?, 1)").use {
                    it.setBytes(1, idX)
                    it.executeUpdate()
                }
                raw.createStatement().use { it.execute("PRAGMA user_version = 16") }
            }

            DatabaseFactory.createDriver(dbPath).use { driver ->
                val db = DatabaseFactory.createDatabase(driver)
                val repo = SqlDelightIdMapRepository(db)
                val pageId = PageId.require("07070707-0707-0707-0707-070707070707")

                assertEquals(18L, driver.queryLongNative("PRAGMA user_version")) // 16.sqm + 17.sqm applied in-image
                // The pre-flip row survives the id_map rebuild under its own (root, path).
                assertEquals(
                    RootedPath(RootName.require("main"), TreePath.require("guides/a.md")),
                    repo.bindingInRoot(RootName.require("main"), pageId)?.path,
                )
                // The flip's headline: the SAME id under a DIFFERENT root now inserts, where UNIQUE(id) forbade it.
                db.idMapQueries.upsertBinding(
                    root = RootName.require("extra"),
                    path = TreePath.require("guides/a.md"),
                    id = pageId,
                    materialized = false,
                )
                assertNotNull(repo.bindingInRoot(RootName.require("extra"), pageId))
            }
        } finally {
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `a v17 database migrates to v18 in-image - root_observation gains binding_epoch defaulting 0 and it increments`() {
        val dir = Files.createTempDirectory("pb-native-migration-v17")
        try {
            val dbPath = dir.resolve("plainbase.db")
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { raw ->
                // The v17 root_observation shape: observation_id only, no binding_epoch (17.sqm adds it).
                raw.createStatement().use {
                    it.execute("CREATE TABLE root_observation (root TEXT NOT NULL PRIMARY KEY, observation_id INTEGER NOT NULL)")
                }
                raw.createStatement().use { it.execute("INSERT INTO root_observation(root, observation_id) VALUES ('main', 5)") }
                raw.createStatement().use { it.execute("PRAGMA user_version = 17") }
            }

            DatabaseFactory.createDriver(dbPath).use { driver ->
                val db = DatabaseFactory.createDatabase(driver)
                val repo = SqlDelightRetirementRepository(db)

                assertEquals(18L, driver.queryLongNative("PRAGMA user_version")) // 17.sqm applied in-image
                assertEquals(5L, driver.queryLongNative("SELECT observation_id FROM root_observation WHERE root = 'main'"))
                // binding_epoch back-filled by the DEFAULT.
                assertEquals(0L, driver.queryLongNative("SELECT binding_epoch FROM root_observation WHERE root = 'main'"))
                assertEquals(BindingEpoch(0), repo.bindingEpoch(RootName.require("main"))) // the typed port reads it through the JNI seam

                db.rootObservationQueries.incrementBindingEpoch(RootName.require("main"))
                assertEquals(BindingEpoch(1), repo.bindingEpoch(RootName.require("main")))
                assertEquals(5L, driver.queryLongNative("SELECT observation_id FROM root_observation WHERE root = 'main'")) // untouched
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
