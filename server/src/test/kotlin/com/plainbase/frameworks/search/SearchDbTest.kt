package com.plainbase.frameworks.search

import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.SearchIndexer
import com.plainbase.domain.service.SectionSplitter
import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * search.db is DERIVED state, by privilege (§B5/ADR-0004): deletable with zero data loss
 * (engine-truth diffing makes the next sync a full repopulation), schema-versioned by
 * drop-and-recreate (never a migration), and always a separate file from the app database —
 * deleting it never touches app state (§4 hard rule, asserted here).
 */
class SearchDbTest : FunSpec({

    fun dataDir(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("plainbase-searchdb-test")
        try {
            block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("self-healing: delete search.db (provider closed), reopen, sync repopulates fully from the snapshot") {
        withTempTree(seed = { root ->
            writePage(root, "docs/alpha.md", "# Alpha\n\nkubernetes rollout notes\n")
            writePage(root, "docs/beta.md", "# Beta\n\nterraform plan output\n")
        }) { root ->
            IndexHarness(root).use { harness ->
                val snapshot = harness.builder.rebuild()
                dataDir { dir ->
                    val dbPath = dir.resolve("search.db")
                    SearchDb(dbPath).use { db ->
                        val provider = Fts5SearchProvider(db)
                        SearchIndexer(provider, SectionSplitter()).sync(snapshot)
                        provider.search(query("kubernetes")).total shouldBe 1L
                    }

                    Files.list(dir).use { files -> files.forEach(Files::deleteIfExists) } // search.db + -wal/-shm
                    Files.exists(dbPath) shouldBe false

                    SearchDb(dbPath).use { db ->
                        val provider = Fts5SearchProvider(db)
                        provider.indexedState() shouldBe emptyMap() // empty engine truth ⇒ full upsert
                        SearchIndexer(provider, SectionSplitter()).sync(snapshot)
                        provider.search(query("kubernetes")).total shouldBe 1L
                        provider.search(query("terraform")).total shouldBe 1L
                        provider.indexedState().keys shouldBe snapshot.pages.map { it.id }.toSet()
                    }
                }
            }
        }
    }

    test("schema_version mismatch on open: every table is dropped and recreated, index state gone") {
        dataDir { dir ->
            val dbPath = dir.resolve("search.db")
            SearchDb(dbPath).use { db ->
                Fts5SearchProvider(db).index(listOf(pageDocuments(1, preamble = "survivor probe")))
            }
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { raw ->
                raw.createStatement().use { it.executeUpdate("UPDATE search_meta SET value='999' WHERE key='schema_version'") }
            }
            SearchDb(dbPath).use { db ->
                val provider = Fts5SearchProvider(db)
                provider.indexedState() shouldBe emptyMap()
                provider.search(query("survivor")).total shouldBe 0L
            }
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { raw ->
                raw.createStatement().use { statement ->
                    statement.executeQuery("SELECT value FROM search_meta WHERE key='schema_version'").use { rows ->
                        rows.next()
                        rows.getString(1) shouldBe SearchDb.SCHEMA_VERSION.toString()
                    }
                }
            }
        }
    }

    test("a matching schema_version on open preserves the index (no gratuitous drop)") {
        dataDir { dir ->
            val dbPath = dir.resolve("search.db")
            SearchDb(dbPath).use { db -> Fts5SearchProvider(db).index(listOf(pageDocuments(1, preamble = "persistent probe"))) }
            SearchDb(dbPath).use { db -> Fts5SearchProvider(db).search(query("persistent")).total shouldBe 1L }
        }
    }

    test("§4 hard rule: search.db is a separate file; deleting it never touches app state") {
        dataDir { dir ->
            val config = PlainbaseConfig.fromEnv(mapOf("DATA_DIR" to dir.toString()))
            config.searchDatabasePath shouldNotBe config.appDatabasePath
            config.searchDatabasePath.parent shouldBe config.appDatabasePath.parent

            DatabaseFactory.createDriver(config.appDatabasePath).use { driver ->
                DatabaseFactory.createDatabase(driver).appMetaQueries.upsert("survival.key", "app-state")
            }
            SearchDb(config.searchDatabasePath).use { db ->
                Fts5SearchProvider(db).index(listOf(pageDocuments(1, preamble = "derived state")))
            }

            Files.list(dir).use { files ->
                files.filter { it.fileName.toString().startsWith("search.db") }.forEach(Files::deleteIfExists)
            }
            Files.exists(config.searchDatabasePath) shouldBe false
            Files.exists(config.appDatabasePath) shouldBe true

            DatabaseFactory.createDriver(config.appDatabasePath).use { driver ->
                DatabaseFactory.createDatabase(driver).appMetaQueries.selectByKey("survival.key").executeAsOne() shouldBe "app-state"
            }
        }
    }
})
