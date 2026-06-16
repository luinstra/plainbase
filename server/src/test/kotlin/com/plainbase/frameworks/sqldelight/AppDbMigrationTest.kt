package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.DriverManager

/**
 * The full app-DB migration chain from the committed v2 baseline: a REAL v2 database file (the
 * SQLDelight `schema/2.db` baseline) opened through the production [DatabaseFactory] must migrate
 * cleanly to the current schema — `page_search` (the Phase-0 FTS5 spike table) dropped by `2.sqm`,
 * `page_checkpoint` (§B3) present and usable, and `dirty_page` (W1's `3.sqm` write-ahead journal)
 * present. `verifyMigrations` checks the DDL at build time; this proves the runtime path end to end.
 */
class AppDbMigrationTest : FunSpec({

    test("v2 baseline migrates to the current schema: page_search dropped, page_checkpoint + dirty_page created") {
        val dir = Files.createTempDirectory("plainbase-migration-test")
        try {
            val dbPath = dir.resolve("plainbase.db")
            Files.copy(schemaBaseline("2.db"), dbPath, StandardCopyOption.REPLACE_EXISTING)
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { raw ->
                raw.createStatement().use { it.execute("PRAGMA user_version = 2") }
            }

            DatabaseFactory.createDriver(dbPath).use { driver ->
                // The tables are live, not just present: rows round-trip through the typed layer.
                val db = DatabaseFactory.createDatabase(driver)
                val id = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
                db.pageCheckpointQueries.insertRow(id, TreePath.require("docs/start"))
                val row = db.pageCheckpointQueries.selectAll().executeAsOne()
                row.id shouldBe id
                row.url_path?.value shouldBe "docs/start"

                db.dirtyPageQueries.upsert(id, TreePath.require("docs/start"), "sha256:abc", "WRITING")
                val dirty = db.dirtyPageQueries.selectAll().executeAsOne()
                dirty.id shouldBe id
                dirty.path.value shouldBe "docs/start"
                dirty.stage shouldBe "WRITING"
            }

            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { raw ->
                raw.createStatement().use { statement ->
                    val tables = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { rows ->
                        buildList { while (rows.next()) add(rows.getString(1)) }
                    }
                    tables shouldContain "page_checkpoint"
                    tables shouldContain "dirty_page"
                    tables shouldNotContain "page_search"
                    val version = statement.executeQuery("PRAGMA user_version").use { rows ->
                        rows.next()
                        rows.getLong(1)
                    }
                    version shouldBe 4L
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
})

/** Locates the committed SQLDelight schema baseline by walking up from the test CWD (Fixtures pattern). */
private fun schemaBaseline(name: String): Path {
    var dir: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
    while (dir != null) {
        for (candidate in listOf("src/main/sqldelight/schema", "server/src/main/sqldelight/schema")) {
            val resolved = dir.resolve(candidate).resolve(name)
            if (Files.isRegularFile(resolved)) return resolved
        }
        dir = dir.parent
    }
    error("Could not locate the SQLDelight schema baseline $name from ${System.getProperty("user.dir")}")
}
