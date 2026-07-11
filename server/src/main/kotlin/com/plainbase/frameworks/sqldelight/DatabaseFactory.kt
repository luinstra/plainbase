package com.plainbase.frameworks.sqldelight

import app.cash.sqldelight.db.QueryResult.Value
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.Path

/** Opens, creates, and migrates the app-state SQLite database via SQLDelight's JDBC driver. */
object DatabaseFactory {

    /**
     * Opens (and creates/migrates if needed) the app-state SQLite database at [path].
     * Schema versioning is tracked in `user_version`; migrations are `.sqm` files
     * next to the `.sq` schema (see src/main/sqldelight/README.md).
     */
    fun createDriver(path: Path): SqlDriver {
        path.parent?.let(Files::createDirectories)
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        migrate(driver)
        return driver
    }

    /** Builds the typed database. Id columns are 16-byte BLOBs; paths are NFC text; roots validated slugs (the three column adapters). */
    fun createDatabase(driver: SqlDriver): PlainbaseDb = PlainbaseDb(
        driver = driver,
        id_mapAdapter = Id_map.Adapter(
            rootAdapter = RootNameColumnAdapter,
            pathAdapter = TreePathColumnAdapter,
            idAdapter = PageIdColumnAdapter,
        ),
        // identity_issue's other_root/other_path/page_id stay untyped: their UNIQUE-key sentinels
        // ('' / x'') are not valid RootName/TreePath/PageId values, so the repository maps them
        // (see IssueRow).
        identity_issueAdapter = Identity_issue.Adapter(rootAdapter = RootNameColumnAdapter, pathAdapter = TreePathColumnAdapter),
        url_aliasAdapter = Url_alias.Adapter(
            rootAdapter = RootNameColumnAdapter,
            pathAdapter = TreePathColumnAdapter,
            idAdapter = PageIdColumnAdapter,
        ),
        page_checkpointAdapter = Page_checkpoint.Adapter(
            idAdapter = PageIdColumnAdapter,
            rootAdapter = RootNameColumnAdapter,
            url_pathAdapter = TreePathColumnAdapter,
        ),
        dirty_pageAdapter = Dirty_page.Adapter(
            idAdapter = PageIdColumnAdapter,
            rootAdapter = RootNameColumnAdapter,
            pathAdapter = TreePathColumnAdapter,
        ),
        proposalsAdapter = Proposals.Adapter(
            idAdapter = ProposalIdColumnAdapter,
            page_idAdapter = PageIdColumnAdapter,
            target_pathAdapter = TreePathColumnAdapter,
        ),
    )

    /** In-memory database for tests and the spike. */
    fun createInMemoryDriver(): SqlDriver =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { PlainbaseDb.Schema.create(it) }

    /**
     * Opens the database at [path] for reading with ZERO on-disk effect: no file or directory
     * creation, no migration, and writes rejected by SQLite itself (read-only open mode). This is
     * the driver behind `adopt --dry-run`'s nothing-was-written promise. When no database exists
     * yet — or an existing one predates the current schema, so the tables a caller would read
     * aren't there — the persisted state it would expose is empty by definition, and an empty
     * in-memory stand-in serves it without touching (or migrating) anything on disk.
     */
    fun createReadOnlyDriver(path: Path): SqlDriver {
        if (Files.notExists(path)) return createInMemoryDriver()
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path", SQLiteConfig().apply { setReadOnly(true) }.toProperties())
        if (driver.userVersion() >= PlainbaseDb.Schema.version) return driver
        driver.close()
        return createInMemoryDriver()
    }

    private fun migrate(driver: SqlDriver) {
        val current = driver.userVersion()
        val target = PlainbaseDb.Schema.version
        // current > target: an older binary opening a newer DB. Intentionally a no-op for now —
        // a downgrade guard (throwing) would be a behavior change; defer that hardening.
        if (current >= target) return
        // ONE SQLite transaction around the whole chain plus its `user_version` bump, all-or-nothing
        // (SQLite DDL is transactional; `user_version` lives in the DB header and rolls back too).
        // The generated Schema.create/migrate issue bare per-statement executes with NO transaction
        // of their own, so a crash mid-way through a multi-statement rebuild (10.sqm's
        // CREATE/INSERT/DROP/RENAME chain) would otherwise strand a half-rebuilt DB still stamped
        // with the OLD version - unable to retry, unable to boot. The DRIVER-managed transaction is
        // load-bearing: it pins one connection for every statement inside the block, where a raw
        // BEGIN would die with the per-statement connection the file-backed driver borrows.
        createDatabase(driver).transaction {
            if (current == 0L) {
                PlainbaseDb.Schema.create(driver)
            } else {
                PlainbaseDb.Schema.migrate(driver, current, target)
            }
            driver.execute(null, "PRAGMA user_version = $target;", 0)
        }
    }

    private fun SqlDriver.userVersion(): Long = executeQuery(
        identifier = null,
        sql = "PRAGMA user_version;",
        mapper = { cursor ->
            cursor.next()
            Value(cursor.getLong(0) ?: 0L)
        },
        parameters = 0,
    ).value
}
