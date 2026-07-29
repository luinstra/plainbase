package com.plainbase.frameworks.sqldelight

import app.cash.sqldelight.db.QueryResult.Value
import app.cash.sqldelight.db.SqlDriver
import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.Path

/** Opens, creates, and migrates the app-state SQLite database via SQLDelight's JDBC driver. */
object DatabaseFactory {

    private const val SQLITE_BUSY_TIMEOUT_MS = 3_000

    /**
     * Opens (and creates/migrates if needed) the app-state SQLite database at [path].
     * Schema versioning is tracked in `user_version`; migrations are `.sqm` files
     * next to the `.sq` schema (see src/main/sqldelight/README.md).
     */
    fun createDriver(path: Path): SqlDriver {
        path.parent?.let(Files::createDirectories)
        return migrateOrClose(
            BeginImmediateSqliteDriver("jdbc:sqlite:$path", appDatabaseProperties()),
        )
    }

    /** Migrate [driver], closing the handle before rethrowing on ANY failure: a rejected boot must not leak the open connection. */
    internal fun migrateOrClose(driver: SqlDriver): SqlDriver {
        runCatching {
            migrate(driver)
        }.onFailure { failure ->
            driver.close()
            throw failure
        }
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
            target_rootAdapter = RootNameColumnAdapter,
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
        retired_bindingAdapter = Retired_binding.Adapter(
            idAdapter = PageIdColumnAdapter,
            rootAdapter = RootNameColumnAdapter,
            pathAdapter = TreePathColumnAdapter,
        ),
        root_observationAdapter = Root_observation.Adapter(rootAdapter = RootNameColumnAdapter),
        root_topologyAdapter = Root_topology.Adapter(rootAdapter = RootNameColumnAdapter),
        git_checkpointAdapter = Git_checkpoint.Adapter(rootAdapter = RootNameColumnAdapter),
        proposalsAdapter = Proposals.Adapter(
            idAdapter = ProposalIdColumnAdapter,
            page_idAdapter = PageIdColumnAdapter,
            target_pathAdapter = TreePathColumnAdapter,
            rootAdapter = RootNameColumnAdapter,
        ),
    )

    /** In-memory database for tests and the spike. */
    fun createInMemoryDriver(): SqlDriver =
        BeginImmediateSqliteDriver(
            BeginImmediateSqliteDriver.IN_MEMORY,
            appDatabaseProperties(),
        ).also { PlainbaseDb.Schema.create(it) }

    /**
     * Opens the database at [path] for reading with ZERO on-disk effect: no file or directory
     * creation, no migration, and writes rejected by SQLite itself (read-only open mode). This is
     * the driver behind `adopt --dry-run`'s nothing-was-written promise. When no database exists
     * yet — or an existing one predates the current schema, so the tables a caller would read
     * aren't there — the persisted state it would expose is empty by definition, and an empty
     * in-memory stand-in serves it without touching (or migrating) anything on disk. A database AT
     * OR AHEAD of the current schema (including a NEWER one) is served file-backed: a read-only open
     * cannot corrupt anything, and `adopt --dry-run`'s nothing-was-written promise is exactly what
     * this method exists for. The forward-only refusal is [migrate]'s job (the WRITABLE path), not
     * this one's.
     *
     * Because this routes through [BeginImmediateSqliteDriver] like the writable factories do (so tests exercise the
     * begin statement the binary ships), any TRANSACTION opened on it issues `BEGIN IMMEDIATE`. A read-only open defers
     * the write-lock failure to the first write, so a pure-read transaction still succeeds, but callers here should keep
     * to bare statements: today nothing transactional reaches this driver, and a future transactional read would be the
     * first thing to test against `adopt --dry-run`.
     */
    fun createReadOnlyDriver(path: Path): SqlDriver {
        if (Files.notExists(path)) return createInMemoryDriver()
        val driver = BeginImmediateSqliteDriver(
            "jdbc:sqlite:$path",
            appDatabaseProperties(readOnly = true),
        )
        if (driver.userVersion() >= PlainbaseDb.Schema.version) return driver
        driver.close()
        return createInMemoryDriver()
    }

    private fun migrate(driver: SqlDriver) {
        val current = driver.userVersion()
        val target = PlainbaseDb.Schema.version
        if (current == target) return
        // FORWARD-only refusal (C5): a C5-or-later binary will not silently open a DB written by a still-NEWER binary.
        // Opening a schema this build does not understand would run its queries against a shape it cannot reason about
        // and corrupt per-root identity. (It cannot stop an OLDER binary already running - that is the operational
        // upgrade rule in docs/operating-plainbase.md, not something this code can enforce.)
        if (current > target) throw newerSchemaError(current, target)
        // ONE SQLite transaction around the whole chain plus its `user_version` bump, all-or-nothing
        // (SQLite DDL is transactional; `user_version` lives in the DB header and rolls back too).
        // The generated Schema.create/migrate issue bare per-statement executes with NO transaction
        // of their own, so a crash mid-way through a multi-statement rebuild (10.sqm's
        // CREATE/INSERT/DROP/RENAME chain) would otherwise strand a half-rebuilt DB still stamped
        // with the OLD version - unable to retry, unable to boot. The DRIVER-managed transaction is
        // load-bearing: it pins one connection for every statement inside the block, which is required by this
        // multi-statement migration chain.
        createDatabase(driver).transaction {
            if (current == 0L) {
                PlainbaseDb.Schema.create(driver)
            } else {
                PlainbaseDb.Schema.migrate(driver, current, target)
            }
            driver.execute(null, "PRAGMA user_version = $target;", 0)
        }
    }

    private fun appDatabaseProperties(readOnly: Boolean = false) = SQLiteConfig().apply {
        // SQLiteConfig emits transaction_mode=DEFERRED in this properties bag; it is inert because this fork issues
        // BEGIN IMMEDIATE directly and never uses setAutoCommit(false).
        setBusyTimeout(SQLITE_BUSY_TIMEOUT_MS)
        if (readOnly) setReadOnly(true)
    }.toProperties()

    private fun newerSchemaError(current: Long, target: Long): IllegalStateException = IllegalStateException(
        "database schema v$current is NEWER than this binary understands (v$target); a newer Plainbase wrote it. " +
            "Upgrade the binary; this build will not open it (opening it would corrupt per-root identity).",
    )

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
