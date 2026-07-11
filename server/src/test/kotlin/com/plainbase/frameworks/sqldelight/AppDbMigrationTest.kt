package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
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
 * `page_checkpoint` (§B3) present and usable, `dirty_page` (W1's `3.sqm` write-ahead journal) present,
 * `api_tokens` (A2's `4.sqm` agent-token store) present and usable, `subject_role` + `audit_log` (A3's `5.sqm`
 * authZ choke point) present and usable, `users` + `sessions` + `setup_tokens` (A4a's `6.sqm` human-login
 * substrate) present and usable, and `proposals` (P1a's `7.sqm` agent-proposal store) present and usable.
 * `verifyMigrations` checks the DDL at build time; this proves the runtime path end to end.
 */
class AppDbMigrationTest : FunSpec({

    test(
        "v2 baseline migrates to current: page_search dropped, checkpoint/dirty_page/api_tokens/subject_role/audit_log/users/sessions/setup_tokens/proposals created",
    ) {
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
                db.pageCheckpointQueries.insertRow(id, RootName.MAIN, TreePath.require("docs/start"))
                val row = db.pageCheckpointQueries.selectAll().executeAsOne()
                row.id shouldBe id
                row.root shouldBe RootName.MAIN
                row.url_path?.value shouldBe "docs/start"

                db.dirtyPageQueries.upsert(id, RootName.MAIN, TreePath.require("docs/start"), "sha256:abc", "WRITING")
                val dirty = db.dirtyPageQueries.selectAll().executeAsOne()
                dirty.id shouldBe id
                dirty.root shouldBe RootName.MAIN
                dirty.path.value shouldBe "docs/start"
                dirty.stage shouldBe "WRITING"

                db.apiTokensQueries.insert(
                    id = "00ff", secretHash = ByteArray(32), agentLabel = "ci", issuer = "agent",
                    externalId = "00ff", mode = "READ_ONLY", createdAt = 0, lastUsedAt = null,
                    expiresAt = null, revokedAt = null,
                )
                db.apiTokensQueries.selectById("00ff").executeAsOne().agent_label shouldBe "ci"

                // A3 (5.sqm): subject_role + audit_log are live through the typed layer.
                db.subjectRoleQueries.upsert(issuer = "builtin", externalId = "alice", role = "EDITOR", createdAt = 0)
                db.subjectRoleQueries.selectByIdentity("builtin", "alice").executeAsOne().role shouldBe "EDITOR"
                db.auditLogQueries.insert(
                    id = "a1",
                    ts = 0,
                    principalKind = "human",
                    issuer = "builtin",
                    externalId = "alice",
                    action = "EDIT",
                    resource = "p",
                    decision = "allowed",
                )
                db.auditLogQueries.selectRecent(10).executeAsOne().decision shouldBe "allowed"

                // A4a (6.sqm): users + sessions + setup_tokens are live through the typed layer.
                db.usersQueries.insert(
                    id = "u1",
                    username = "alice",
                    passwordHash = "\$argon2id\$dummy",
                    displayName = "Alice",
                    disabled = 0,
                    createdAt = 0,
                    updatedAt = 0,
                )
                db.usersQueries.selectByUsername("alice").executeAsOne().id shouldBe "u1"
                db.sessionsQueries.insert(
                    tokenHash = ByteArray(32),
                    userId = "u1",
                    csrfToken = ByteArray(32) { 1 },
                    createdAt = 0,
                    idleExpiresAt = 1000,
                    absoluteExpiresAt = 2000,
                    revokedAt = null,
                )
                db.sessionsQueries.selectByTokenHash(ByteArray(32)).executeAsOne().user_id shouldBe "u1"
                db.setupTokensQueries.insert(
                    tokenHash = ByteArray(32) { 2 },
                    purpose = "BOOTSTRAP",
                    userId = null,
                    createdAt = 0,
                    expiresAt = 1000,
                    usedAt = null,
                )
                db.setupTokensQueries.selectByTokenHash(ByteArray(32) { 2 }).executeAsOne().purpose shouldBe "BOOTSTRAP"

                // P1a (7.sqm): proposals is live through the typed layer — a row round-trips with its adapted columns.
                val proposalId = com.plainbase.domain.page.ProposalId.require("01900000-0000-7000-9000-000000000001")
                db.proposalsQueries.insert(
                    id = proposalId, operation = "EDIT", pageId = id, baseHash = "sha256:abc",
                    targetPath = TreePath.require("docs/start"), proposedContent = ByteArray(3) { 1 }, rationale = "r",
                    diffArtifact = "", status = "PENDING", authorIssuer = "agent", authorExternalId = "00ff",
                    authorLabel = "ci", approverIssuer = null, approverExternalId = null, decisionComment = null,
                    createdAt = 0, decidedAt = null, appliedCommit = null, statusReason = null,
                )
                db.proposalsQueries.selectById(proposalId).executeAsOne().status shouldBe "PENDING"
            }

            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { raw ->
                raw.createStatement().use { statement ->
                    val tables = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { rows ->
                        buildList { while (rows.next()) add(rows.getString(1)) }
                    }
                    tables shouldContain "page_checkpoint"
                    tables shouldContain "dirty_page"
                    tables shouldContain "api_tokens"
                    tables shouldContain "subject_role"
                    tables shouldContain "audit_log"
                    tables shouldContain "users"
                    tables shouldContain "sessions"
                    tables shouldContain "setup_tokens"
                    tables shouldContain "proposals"
                    tables shouldNotContain "page_search"
                    val version = statement.executeQuery("PRAGMA user_version").use { rows ->
                        rows.next()
                        rows.getLong(1)
                    }
                    version shouldBe 11L
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("v10 baseline migrates to v11 preserving EVERY affected table's data, all rows stamped root='main'") {
        // The frozen one-way C2 migration: seed one or two rows into each of the six root-gaining
        // tables on a real committed v10 baseline through raw JDBC (the pre-C2 shapes), migrate via
        // the production factory, and read everything back through the typed layer.
        val dir = Files.createTempDirectory("plainbase-migration-test")
        try {
            val dbPath = dir.resolve("plainbase.db")
            Files.copy(schemaBaseline("10.db"), dbPath, StandardCopyOption.REPLACE_EXISTING)
            val idX = ByteArray(16) { 1 }
            val proposalId = ByteArray(16) { 3 }
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { raw ->
                raw.createStatement().use { it.execute("PRAGMA user_version = 10") }
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
                    it.setBytes(1, proposalId)
                    it.executeUpdate()
                }
            }

            DatabaseFactory.createDriver(dbPath).use { driver ->
                val db = DatabaseFactory.createDatabase(driver)
                val pageId = PageId.require("01010101-0101-0101-0101-010101010101")

                val binding = db.idMapQueries.selectAllBindings().executeAsOne()
                binding.root shouldBe RootName.MAIN
                binding.path.value shouldBe "guides/a.md"
                binding.id shouldBe pageId

                val alias = db.idMapQueries.selectAllAliases().executeAsOne()
                alias.root shouldBe RootName.MAIN
                alias.path.value shouldBe "guides/old"

                // Decodes through the repository's per-kind mapping: root stamped, other_root the sentinel.
                SqlDelightIdMapRepository(db).issues() shouldContainExactly
                    listOf(IdentityIssue.PatchRefused(RootName.MAIN, TreePath.require("guides/a.md"), "reason"))
                driver.queryLong("SELECT count(*) FROM identity_issue WHERE other_root = ''") shouldBe 1L

                val checkpoint = db.pageCheckpointQueries.selectAll().executeAsOne()
                checkpoint.root shouldBe RootName.MAIN
                checkpoint.url_path?.value shouldBe "guides/a"

                val dirty = db.dirtyPageQueries.selectAll().executeAsOne()
                dirty.root shouldBe RootName.MAIN
                dirty.path.value shouldBe "guides/a.md"

                db.proposalsQueries.selectById(
                    com.plainbase.domain.page.ProposalId.require("03030303-0303-0303-0303-030303030303"),
                ) { _, _, _, _, _, _, _, _, status, _, _, _, _, _, _, _, _, _, _, root -> status to root }
                    .executeAsOne() shouldBe ("PENDING" to "main")

                // The composite PK is live (same relative path under another root inserts cleanly)...
                db.idMapQueries.upsertBinding(
                    root = RootName.require("extra"),
                    path = TreePath.require("guides/b.md"),
                    id = PageId.require("02020202-0202-0202-0202-020202020202"),
                    materialized = false,
                )
                // ...and UNIQUE(id) still holds across roots: re-claiming a bound id under a new key throws.
                shouldThrowAny {
                    db.idMapQueries.upsertBinding(
                        root = RootName.require("extra"),
                        path = TreePath.require("guides/copy.md"),
                        id = pageId,
                        materialized = false,
                    )
                }
            }

            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { raw ->
                raw.createStatement().use { statement ->
                    val indexes = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='index'").use { rows ->
                        buildList { while (rows.next()) add(rows.getString(1)) }
                    }
                    indexes shouldContain "dirty_page_root_path"
                    indexes shouldNotContain "dirty_page_path"
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("an interrupted migration rolls back whole: the DB stays intact at v10 and a retry after fixing succeeds") {
        // The generated Schema.migrate issues bare per-statement executes; DatabaseFactory wraps the
        // chain + the user_version bump in ONE transaction. Inject a failure MID-sequence: dirty_page
        // is rebuilt AFTER id_map/identity_issue/url_alias/page_checkpoint in 10.sqm, so a corrupt
        // dirty_page row (NULL stage smuggled past a constraint-stripped rebuild) fails the fifth
        // rebuild - without the transaction, the first four would already be committed in v11 shape
        // under a v10 stamp: unable to retry, unable to boot.
        val dir = Files.createTempDirectory("plainbase-migration-test")
        try {
            val dbPath = dir.resolve("plainbase.db")
            Files.copy(schemaBaseline("10.db"), dbPath, StandardCopyOption.REPLACE_EXISTING)
            val idX = ByteArray(16) { 1 }
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { raw ->
                raw.createStatement().use { statement ->
                    statement.execute("PRAGMA user_version = 10")
                    statement.execute(
                        "INSERT INTO id_map(path, id, materialized) VALUES ('guides/a.md', x'01010101010101010101010101010101', 1)",
                    )
                    // Strip dirty_page's NOT NULLs so a NULL stage can exist (the corrupted-source shape).
                    statement.execute("CREATE TABLE dirty_page_corrupt (id BLOB PRIMARY KEY, path TEXT, expected_hash TEXT, stage TEXT)")
                    statement.execute("INSERT INTO dirty_page_corrupt SELECT * FROM dirty_page")
                    statement.execute("DROP TABLE dirty_page")
                    statement.execute("ALTER TABLE dirty_page_corrupt RENAME TO dirty_page")
                }
                raw.prepareStatement(
                    "INSERT INTO dirty_page(id, path, expected_hash, stage) VALUES (?, 'guides/a.md', 'sha256:abc', NULL)",
                ).use {
                    it.setBytes(1, idX)
                    it.executeUpdate()
                }
            }

            shouldThrowAny { DatabaseFactory.createDriver(dbPath) } // NOT NULL violation mid-chain

            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { raw ->
                raw.createStatement().use { statement ->
                    // All-or-nothing: still v10, no half-rebuilt tables, the pre-C2 shapes and data intact.
                    statement.executeQuery("PRAGMA user_version").use { rows ->
                        rows.next()
                        rows.getLong(1) shouldBe 10L
                    }
                    val tables = statement.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE '%_v11'",
                    ).use { rows ->
                        buildList { while (rows.next()) add(rows.getString(1)) }
                    }
                    tables shouldBe emptyList()
                    statement.executeQuery("SELECT count(*) FROM pragma_table_info('id_map') WHERE name = 'root'").use { rows ->
                        rows.next()
                        rows.getLong(1) shouldBe 0L
                    }
                    statement.executeQuery("SELECT count(*) FROM id_map").use { rows ->
                        rows.next()
                        rows.getLong(1) shouldBe 1L
                    }
                    // Fix the corrupt row; the retry must then migrate cleanly.
                    statement.execute("UPDATE dirty_page SET stage = 'WRITING' WHERE stage IS NULL")
                }
            }

            DatabaseFactory.createDriver(dbPath).use { driver ->
                val db = DatabaseFactory.createDatabase(driver)
                driver.queryLong("PRAGMA user_version") shouldBe 11L
                db.idMapQueries.selectAllBindings().executeAsOne().root shouldBe RootName.MAIN
                db.dirtyPageQueries.selectAll().executeAsOne().root shouldBe RootName.MAIN
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
