package com.plainbase.frameworks.cli

import app.cash.sqldelight.db.SqlDriver
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.service.AdoptionPass
import com.plainbase.domain.service.FrontmatterPatcher
import com.plainbase.domain.service.PageIdentityService
import com.plainbase.domain.service.UuidV7IdProvider
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.StorageBackend
import com.plainbase.frameworks.filesystem.DataDirLock
import com.plainbase.frameworks.filesystem.IgnoreRules
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.objectstore.ObjectContentStoreFactory
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightDirtyPageRepository
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import java.nio.file.Path

/**
 * `plainbase adopt [--write-ids [--dry-run]]` - the chunk 4b adoption CLI.
 *
 * **Its mutating modes refuse to run while a server is up.** RECORD/MATERIALIZE write the app db
 * (MATERIALIZE the tree too) from a second process, so they acquire the DATA_DIR advisory lock
 * ([DataDirLock]) FIRST, before any driver opens and migrates, and exit 1 if a server holds it
 * (the reindex/admin rule). PREVIEW's zero-writes read-only-driver contract keeps it lock-free.
 *
 * stdout is a CLI output contract (`println` by design, like `spike`): the per-page report, the
 * rule-naming refusal reasons (the §A3 asymmetric-freeze measurement input), and the pre-write
 * intent log (`intent:` lines, emitted BEFORE each file write so an interrupted run is
 * reconcilable). Diagnostics still go through the logging facade.
 */
object AdoptCommand {

    /**
     * Entry point for the `main` dispatch: env + `DATA_DIR/plainbase.conf`, exit-code result. Resolves via
     * [PlainbaseConfig.loadForCommand] (NOT the env-only fast path) so the storage-backend decision matches
     * `serve` for the same DATA_DIR: an operator who sets `storage.backend=object` only in `plainbase.conf`
     * must not get the LOCAL branch here and silently adopt over an ignored CONTENT_DIR while the bucket is
     * the real authority. A bad config (IAE or HOCON) surfaces as the actionable `adopt:` stderr + exit 1.
     */
    fun runAsMain(args: List<String>): Int {
        val config = PlainbaseConfig.loadForCommand("adopt") ?: return 1
        return run(args, config)
    }

    fun run(args: List<String>, config: PlainbaseConfig): Int {
        val mode = parseMode(args)
        if (mode == null) {
            System.err.println(USAGE)
            return 2
        }

        // PREVIEW's contract is zero writes, db included: it must not create or migrate the app db,
        // only read whatever identity state an existing install already holds - so an accurate
        // preview never falls out of date, yet a fresh tree gains no plainbase.db from a dry run.
        // That same contract is why it stays lock-free below.
        if (mode == AdoptionPass.Mode.PREVIEW) {
            return adopt(mode, config, DatabaseFactory.createReadOnlyDriver(config.appDatabasePath))
        }
        // RECORD/MATERIALIZE write the db (MATERIALIZE the files too) and trigger createDriver's
        // implicit non-idempotent migrate, so they hold the DataDirLock BEFORE the driver opens,
        // exactly like reindex/admin: never racing or writing underneath a live server.
        val lock = DataDirLock.tryAcquire(config.dataDir)
        if (lock == null) {
            System.err.println("adopt: a Plainbase server is holding ${config.dataDir}; stop it before running this command")
            return 1
        }
        return lock.use { adopt(mode, config, DatabaseFactory.createDriver(config.appDatabasePath)) }
    }

    private fun adopt(mode: AdoptionPass.Mode, config: PlainbaseConfig, driver: SqlDriver): Int {
        var store: ContentStore? = null
        try {
            val database = DatabaseFactory.createDatabase(driver)
            when (config.storage.backend) {
                StorageBackend.LOCAL -> store = LocalContentStore(root = config.contentDir, ignoreRules = IgnoreRules())
                StorageBackend.OBJECT -> {
                    // Object mode adopts over the DATA_DIR mirror (the bucket is the authority).
                    // RECORD/MATERIALIZE hydrate first - under the lock already held, race-free (the
                    // server is down). PREVIEW hydrates NOTHING (its contract is zero writes and it is
                    // lock-free): it reads the existing mirror as-is, point-in-time, possibly stale.
                    val dirtyPages = SqlDelightDirtyPageRepository(database)
                    // Assign BEFORE hydrate so a hydrate-failure early return still closes the transport.
                    val hybrid = ObjectContentStoreFactory.build(
                        config,
                        IgnoreRules(),
                        dirtyPaths = { dirtyPages.all().map { it.path }.toSet() },
                        isDirty = { dirtyPages.isDirty(it) },
                    )
                    store = hybrid
                    if (mode != AdoptionPass.Mode.PREVIEW) {
                        try {
                            hybrid.hydrate()
                        } catch (e: Exception) {
                            System.err.println("adopt: ${e.message}")
                            return 1
                        }
                    }
                }
            }
            val pass = AdoptionPass(
                contentStore = store,
                idMap = SqlDelightIdMapRepository(database),
                identity = PageIdentityService(UuidV7IdProvider()),
                patcher = FrontmatterPatcher(),
            )
            val report = pass.run(mode) { path, id -> println("intent: write id $id -> ${path.value}") }
            print(render(report, adoptedRoot(config)))
        } finally {
            (store as? AutoCloseable)?.close() // release the object-store transport (LocalContentStore is not closeable)
            driver.close()
        }
        return 0
    }

    /** The tree the pass actually walked: CONTENT_DIR locally, the DATA_DIR mirror in object mode. */
    private fun adoptedRoot(config: PlainbaseConfig) = when (config.storage.backend) {
        StorageBackend.LOCAL -> config.contentDir
        StorageBackend.OBJECT -> config.dataDir.resolve("mirror")
    }

    /** The exact documented flag surface; anything else (including `--dry-run` alone) is a usage error. */
    private fun parseMode(args: List<String>): AdoptionPass.Mode? {
        if (args.any { it != "--write-ids" && it != "--dry-run" }) return null
        val writeIds = "--write-ids" in args
        val dryRun = "--dry-run" in args
        return when {
            dryRun && writeIds -> AdoptionPass.Mode.PREVIEW
            dryRun -> null // --dry-run previews --write-ids; alone it has nothing to preview
            writeIds -> AdoptionPass.Mode.MATERIALIZE
            else -> AdoptionPass.Mode.RECORD
        }
    }

    private fun render(report: AdoptionPass.Report, root: Path): String = buildString {
        appendLine("adopt: ${report.pages.size} page(s) under $root")
        when (report.mode) {
            AdoptionPass.Mode.RECORD -> renderRecord(report)
            AdoptionPass.Mode.PREVIEW -> renderPreview(report)
            AdoptionPass.Mode.MATERIALIZE -> renderMaterialize(report)
        }
        val issues = report.issues
        if (issues.isNotEmpty()) {
            appendLine("issues (${issues.size}):")
            issues.forEach { appendLine("  ${describe(it)}") }
        }
        if (report.mode != AdoptionPass.Mode.RECORD) appendLine(NETWORK_FS_CAVEAT)
    }

    private fun StringBuilder.renderRecord(report: AdoptionPass.Report) {
        val mapped = report.pages(AdoptionPass.Disposition.MAPPED)
        val inFile = report.pages(AdoptionPass.Disposition.ALREADY_MATERIALIZED)
        appendLine("recorded ${mapped.size} id_map-only identity(ies); ${inFile.size} page(s) already carry their id")
    }

    private fun StringBuilder.renderPreview(report: AdoptionPass.Report) {
        appendLine("dry run: nothing was written")
        val would = report.pages(AdoptionPass.Disposition.WOULD_MATERIALIZE)
        appendLine("would materialize ${would.size} page(s):")
        would.forEach { appendLine("  ${it.path.value}") }
        val refused = report.pages(AdoptionPass.Disposition.REFUSED)
        if (refused.isNotEmpty()) {
            appendLine("would refuse ${refused.size} page(s):")
            refused.forEach { page ->
                val reason = page.issues.filterIsInstance<IdentityIssue.PatchRefused>()
                    .joinToString { it.message }
                appendLine("  ${page.path.value}: $reason")
            }
        }
    }

    private fun StringBuilder.renderMaterialize(report: AdoptionPass.Report) {
        val written = report.pages(AdoptionPass.Disposition.MATERIALIZED)
        val inFile = report.pages(AdoptionPass.Disposition.ALREADY_MATERIALIZED)
        val refused = report.pages(AdoptionPass.Disposition.REFUSED)
        appendLine("materialized ${written.size} page(s); ${inFile.size} already carried their id; ${refused.size} refused")
    }

    private fun describe(issue: IdentityIssue): String = when (issue) {
        is IdentityIssue.DuplicateId ->
            "duplicate_id ${issue.id}: kept by ${issue.keptPath.value}; ${issue.reassignedPath.value} reassigned a fresh id"
        is IdentityIssue.PatchRefused ->
            "patch_refused ${issue.path.value}: ${issue.message}"
        is IdentityIssue.RedirectConflict ->
            "redirect_conflict ${issue.path.value}: ${issue.message}"
        is IdentityIssue.PathCollision ->
            "path_collision: ${issue.keptPath.value} kept; on-disk sibling '${issue.loserRawName}' excluded"
        is IdentityIssue.PathSlugCollision ->
            "path_slug_collision: ${issue.keptPath.value} owns the URL; ${issue.loserPath.value} reachable by id only"
    }

    private const val USAGE = "usage: plainbase adopt [--write-ids [--dry-run]]"

    /** Operator-facing durability caveat (plan line 555): network filesystems lose crash-atomicity. */
    private const val NETWORK_FS_CAVEAT =
        "note: on network filesystems (NFS/SMB) atomic rename is unsupported and writes fall back to " +
            "copy+delete, which is not crash-atomic; every write is intent-logged (path + id) before it " +
            "is performed, and adopt is idempotent - re-run after an interruption to reconcile."
}
