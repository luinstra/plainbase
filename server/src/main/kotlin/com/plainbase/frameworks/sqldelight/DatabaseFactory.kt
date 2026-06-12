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

    /** Builds the typed database. Id columns are 16-byte BLOBs; paths are NFC text (the two column adapters). */
    fun createDatabase(driver: SqlDriver): PlainbaseDb = PlainbaseDb(
        driver = driver,
        id_mapAdapter = Id_map.Adapter(pathAdapter = TreePathColumnAdapter, idAdapter = PageIdColumnAdapter),
        // identity_issue's other_path/page_id stay untyped: their UNIQUE-key sentinels ('' / x'')
        // are not valid TreePath/PageId values, so the repository maps them (see IssueRow).
        identity_issueAdapter = Identity_issue.Adapter(pathAdapter = TreePathColumnAdapter),
        url_aliasAdapter = Url_alias.Adapter(pathAdapter = TreePathColumnAdapter, idAdapter = PageIdColumnAdapter),
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
        when {
            current == 0L -> {
                PlainbaseDb.Schema.create(driver)
                driver.execute(null, "PRAGMA user_version = $target;", 0)
            }
            current < target -> {
                PlainbaseDb.Schema.migrate(driver, current, target)
                driver.execute(null, "PRAGMA user_version = $target;", 0)
            }
            // current > target: an older binary opening a newer DB. Intentionally a no-op for now —
            // a downgrade guard (throwing) would be a behavior change; defer that hardening.
            else -> Unit
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
