package com.plainbase.frameworks.cli

import app.cash.sqldelight.db.SqlDriver
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.service.AdoptionPass
import com.plainbase.domain.service.FrontmatterPatcher
import com.plainbase.domain.service.PageIdentityService
import com.plainbase.domain.service.UuidV7IdProvider
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.filesystem.DataDirLock
import com.plainbase.frameworks.filesystem.IgnoreRules
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository

/**
 * `plainbase adopt [--write-ids [--dry-run]]` — the chunk 4b adoption CLI.
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

    /** Entry point for the `main` dispatch: real env config, exit-code result. */
    fun runAsMain(args: List<String>): Int = run(args, PlainbaseConfig.fromEnv())

    fun run(args: List<String>, config: PlainbaseConfig): Int {
        val mode = parseMode(args)
        if (mode == null) {
            System.err.println(USAGE)
            return 2
        }
        // Object mode is not built until C4; this CLI runs a LocalContentStore over the now-ignored CONTENT_DIR,
        // so refuse it before any driver opens or the lock is taken (every mode, PREVIEW included — a dry run on
        // the wrong tree is still wrong).
        config.objectBackendUnavailableRefusal()?.let {
            System.err.println("adopt: $it")
            return 1
        }

        // PREVIEW's contract is zero writes, db included: it must not create or migrate the app db,
        // only read whatever identity state an existing install already holds — so an accurate
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
        try {
            val pass = AdoptionPass(
                contentStore = LocalContentStore(root = config.contentDir, ignoreRules = IgnoreRules()),
                idMap = SqlDelightIdMapRepository(DatabaseFactory.createDatabase(driver)),
                identity = PageIdentityService(UuidV7IdProvider()),
                patcher = FrontmatterPatcher(),
            )
            val report = pass.run(mode) { path, id -> println("intent: write id $id -> ${path.value}") }
            print(render(report, config))
        } finally {
            driver.close()
        }
        return 0
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

    private fun render(report: AdoptionPass.Report, config: PlainbaseConfig): String = buildString {
        appendLine("adopt: ${report.pages.size} page(s) under ${config.contentDir}")
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
            "is performed, and adopt is idempotent — re-run after an interruption to reconcile."
}
