package com.plainbase.frameworks.sqldelight

import app.cash.sqldelight.db.QueryResult.Value
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
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

    fun createDatabase(driver: SqlDriver): PlainbaseDb = PlainbaseDb(driver)

    /** In-memory database for tests and the spike. */
    fun createInMemoryDriver(): SqlDriver =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { PlainbaseDb.Schema.create(it) }

    private fun migrate(driver: SqlDriver) {
        val current = driver.executeQuery(
            identifier = null,
            sql = "PRAGMA user_version;",
            mapper = { cursor ->
                cursor.next()
                Value(cursor.getLong(0) ?: 0L)
            },
            parameters = 0,
        ).value
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
}
