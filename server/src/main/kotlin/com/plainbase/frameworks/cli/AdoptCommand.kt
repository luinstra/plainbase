package com.plainbase.frameworks.cli

import app.cash.sqldelight.db.SqlDriver
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.service.AdoptWriteFailed
import com.plainbase.domain.service.AdoptionPass
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.FrontmatterPatcher
import com.plainbase.domain.service.PageIdentityService
import com.plainbase.domain.service.PlanStale
import com.plainbase.domain.service.RootLossClassifier
import com.plainbase.domain.service.RootUnavailable
import com.plainbase.domain.service.UuidV7IdProvider
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.StorageBackend
import com.plainbase.frameworks.filesystem.DataDirLock
import com.plainbase.frameworks.filesystem.IgnoreRules
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.objectstore.ObjectContentStore
import com.plainbase.frameworks.objectstore.ObjectContentStoreFactory
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightDirtyPageRepository
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import kotlin.time.Clock

/**
 * `plainbase adopt [--write-ids [--dry-run]]` - the chunk 4b adoption CLI.
 *
 * **It covers EVERY configured root, and refuses to run unless it can see all of them.** Adoption is what moves a
 * page's identity OUT of `DATA_DIR` and into the page itself, so a root it skips is a root whose permalinks and
 * citations do not survive a lost `DATA_DIR` - the precise disaster `--write-ids` exists to prevent, silently
 * unaddressed for every root but main. Every root is scanned into ONE global, READ-ONLY plan before a single byte
 * is written ([AdoptionPass]), so every within-root duplicate is resolved against the complete picture and no
 * root can be mistaken for one this pass could not see.
 *
 * **Its mutating modes refuse to run while a server is up.** RECORD/MATERIALIZE write the app db
 * (MATERIALIZE the tree too) from a second process, so they acquire the DATA_DIR advisory lock
 * ([DataDirLock]) FIRST, before any driver opens and migrates, and exit 1 if a server holds it
 * (the reindex/admin rule). PREVIEW's zero-writes read-only-driver contract keeps it lock-free.
 *
 * **If the tree moves under it, it ABORTS - it never half-applies and never improvises.** A root that
 * disappears mid-run, a page deleted after the plan read it, a page edited after the plan read it: each
 * stops the run with the reason and the page named, and NOTHING is conjured back or overwritten (the
 * writes are compare-and-swaps against the exact bytes the plan was built on - [AdoptionPass]). What an
 * abort can leave behind is a page whose id_map row exists while its file has no `id:` line yet, which is
 * the very state adopt exists to repair. So the remedy is always the same and it is always safe: restore
 * the root (or let the tree settle) and RE-RUN. Adoption is idempotent and deletes nothing.
 *
 * Reports use the deterministic result channel and refusals use the deterministic error channel. Pre-write
 * intent is a separate checked event channel, synchronously flushed BEFORE each file write so an interrupted
 * run is reconcilable. Unexpected failures preserve their throwable through the logging facade.
 */
object AdoptCommand {
    private val logger = KotlinLogging.logger {}

    private sealed interface PlanExecution {
        data class Success(val plan: AdoptionPass.Plan) : PlanExecution

        data class Failed(val exitCode: Int) : PlanExecution
    }

    /**
     * Entry point for the `main` dispatch: env + `DATA_DIR/plainbase.conf`, exit-code result. Resolves via
     * [PlainbaseConfig.loadForCommand] (NOT the env-only fast path) so the storage-backend decision matches
     * `serve` for the same DATA_DIR: an operator who sets `storage.backend=object` only in `plainbase.conf`
     * must not get the LOCAL branch here and silently adopt over an ignored CONTENT_DIR while the bucket is
     * the real authority. A bad config (IAE or HOCON) surfaces as the actionable `adopt:` stderr + exit 1.
     */
    fun runAsMain(args: List<String>, output: CommandOutput = systemCommandOutput()): Int {
        val config = PlainbaseConfig.loadForCommand("adopt", output::error) ?: return 1
        return run(args, config, output)
    }

    fun run(args: List<String>, config: PlainbaseConfig, output: CommandOutput = systemCommandOutput()): Int {
        val mode = parseMode(args)
        if (mode == null) {
            output.error(USAGE)
            return 2
        }

        // PREVIEW's contract is zero writes, db included: it must not create or migrate the app db,
        // only read whatever identity state an existing install already holds - so an accurate
        // preview never falls out of date, yet a fresh tree gains no plainbase.db from a dry run.
        // That same contract is why it stays lock-free below.
        if (mode == AdoptionPass.Mode.PREVIEW) {
            return adopt(mode, config, DatabaseFactory.createReadOnlyDriver(config.appDatabasePath), output)
        }
        // RECORD/MATERIALIZE write the db (MATERIALIZE the files too) and trigger createDriver's
        // implicit non-idempotent migrate, so they hold the DataDirLock BEFORE the driver opens,
        // exactly like reindex/admin: never racing or writing underneath a live server.
        val lock = DataDirLock.tryAcquire(config.dataDir)
        if (lock == null) {
            output.error("adopt: a Plainbase server is holding ${config.dataDir}; stop it before running this command")
            return 1
        }
        return lock.use { adopt(mode, config, DatabaseFactory.createDriver(config.appDatabasePath), output) }
    }

    private fun adopt(mode: AdoptionPass.Mode, config: PlainbaseConfig, driver: SqlDriver, output: CommandOutput): Int {
        val registry = RootRegistry.of(config.roots.list)
        val stores = LinkedHashMap<RootName, ContentStore>()
        try {
            val database = DatabaseFactory.createDatabase(driver)
            when (config.storage.backend) {
                // EVERY configured root, not just main. The identity an adopt writes into a page's frontmatter is
                // the ONLY copy of it that survives a lost DATA_DIR - so a root this pass skips is a root whose
                // permalinks and citations die with that directory, which is the exact disaster --write-ids exists
                // to prevent. Extras are local-only in v1 (D10), so this is the whole local topology.
                //
                // Main is EXPLICIT (its tree is `mainContentRoot()`, the env-sourced path, not `localPath`); the fold
                // sees ONLY extras and never re-selects main by name. The report below iterates the REGISTRY, so this
                // map's insertion order is nobody's contract.
                StorageBackend.LOCAL -> {
                    stores[registry.main.name] = localStore(config, config.mainContentRoot(), registry.main.name)
                    registry.extras.forEach { root ->
                        val path = requireNotNull(root.localPath) { "extra root '${root.name}' must be local-backed" }
                        stores[root.name] = localStore(config, path, root.name)
                    }
                }
                StorageBackend.OBJECT -> {
                    // Object mode is single-root by decision (D10 rejects an explicit roots block over a bucket), so
                    // there is exactly one tree here: the DATA_DIR mirror (the bucket is the authority).
                    // RECORD/MATERIALIZE hydrate first - under the lock already held, race-free (the
                    // server is down). PREVIEW hydrates NOTHING (its contract is zero writes and it is
                    // lock-free): it reads the existing mirror as-is, point-in-time, possibly stale.
                    val dirtyPages = SqlDelightDirtyPageRepository(database)
                    // Register BEFORE hydrate so a hydrate-failure early return still closes the transport.
                    val hybrid = ObjectContentStoreFactory.build(
                        config,
                        IgnoreRules(),
                        dirtyPaths = { dirtyPages.all().map { it.path.path }.toSet() },
                        isDirty = { dirtyPages.isDirty(RootedPath(RootName.MAIN, it)) },
                    )
                    stores[registry.main.name] = hybrid
                    if (mode != AdoptionPass.Mode.PREVIEW && !hydrate(hybrid, output)) {
                        return 1
                    }
                }
            }
            if (refuseUnavailableRoots(registry, stores, output)) return 1
            val pass = AdoptionPass(
                sources = stores.map { (root, store) -> AdoptionPass.Source(root, store) },
                idMap = SqlDelightIdMapRepository(database),
                identity = PageIdentityService(UuidV7IdProvider()),
                patcher = FrontmatterPatcher(),
                // The shared root-loss rule (probe decides, a live-root fault still rethrows). Its availability
                // holder is inert here - a CLI serves no 503s and exits - but the CLASSIFICATION is the one every
                // other rooted call takes, so a vanished disk surfaces as the actionable abort below rather than
                // as a raw IOException stack trace, and a corrupt file is never laundered into "the disk is gone".
                rootLoss = RootLossClassifier(RootAvailability(Clock.System)),
                // The CAS precondition for every `--write-ids` file write: the frozen hash of the bytes the patch
                // was computed from, so adopt replaces the page it PLANNED and never creates or clobbers one.
                citations = CitationFactory(),
                rootRank = registry::rank,
                registeredRoots = registry.roots.map { it.name }.toSet(),
            )
            val qualified = stores.size > 1
            // ONE global read-only plan across ALL roots, THEN the write (D19). The identity motivation was a
            // cross-root rank contest, which per-root identity dissolved (ADR-0012), but a WITHIN-root one took
            // its place with the shared owner gate: a materialized binding whose file lost its `id:` is
            // displaceable, so the winner's bind sweeps its row and only resolve-before-bind keeps the beaten
            // owner visible enough to record its issue instead of silently minting it a fresh id - which
            // `--write-ids` would then put in the FILE. Plus whole-command ATOMICITY (the plan writes nothing,
            // so a root vanishing mid-scan costs nothing) and preview/write equivalence.
            val plan = when (val execution = executePlan(pass, mode, qualified, output)) {
                is PlanExecution.Success -> execution.plan
                is PlanExecution.Failed -> return execution.exitCode
            }

            // D7 order, said out loud rather than inherited from a map's insertion order. The two key sets are equal:
            // LOCAL registers every registry root above, and OBJECT is single-root by D10 (refused at parse).
            registry.roots.forEach { root ->
                output.result(
                    render(plan.report(root.name), root.name, adoptedTree(config, registry, root.name), qualified),
                    newline = false,
                )
            }
            // ONE caveat for the whole run, not one per root (it is about the WRITE mechanism, not about a tree).
            if (mode != AdoptionPass.Mode.RECORD) output.result(NETWORK_FS_CAVEAT)
        } finally {
            stores.values.forEach { (it as? AutoCloseable)?.close() } // the object-store transport; LocalContentStore is not closeable
            driver.close()
        }
        return 0
    }

    private fun hydrate(store: ObjectContentStore, output: CommandOutput): Boolean =
        runCatching { store.hydrate() }.fold(
            onSuccess = { true },
            onFailure = { failure ->
                when (failure) {
                    is Error -> throw failure
                    else -> {
                        logger.error(failure) { "adopt hydrate failed" }
                        output.error("adopt: ${failure.message ?: "unexpected failure"}")
                        false
                    }
                }
            },
        )

    private fun executePlan(
        pass: AdoptionPass,
        mode: AdoptionPass.Mode,
        qualified: Boolean,
        output: CommandOutput,
    ): PlanExecution {
        var wrote = false
        return runCatching {
            pass.run(mode) { page, id ->
                runCatching {
                    output.intent(WriteIntent(id.toString(), page, qualified))
                }.getOrElse { failure ->
                    when (failure) {
                        is Exception -> throw CommandEventPublicationFailed(failure)
                        else -> throw failure
                    }
                }
                wrote = true
            }
        }.fold(
            onSuccess = { PlanExecution.Success(it) },
            onFailure = { failure ->
                when (failure) {
                    is RootUnavailable -> PlanExecution.Failed(abort(failure.root, wrote, output))
                    is PlanStale -> PlanExecution.Failed(abortStale(label(failure.page, qualified), failure.reason, wrote, output))
                    is AdoptWriteFailed -> {
                        val landed = if (failure.targetMutated) {
                            " (the bytes may already be durable at the authority)"
                        } else {
                            ""
                        }
                        PlanExecution.Failed(
                            abortStale(
                                label(failure.page, qualified),
                                "could not be written: ${failure.reason}$landed",
                                wrote,
                                output,
                            ),
                        )
                    }
                    is CommandEventPublicationFailed -> {
                        logger.error(failure.cause) { "adopt command event publication failed" }
                        output.error("adopt: the pre-write intent could not be published; nothing was written for that intent")
                        PlanExecution.Failed(1)
                    }
                    else -> throw failure
                }
            },
        )
    }

    /**
     * A root that went away MID-RUN (the preflight passed, so it was there when the plan was made). The plan phase
     * writes nothing and [AdoptionPass.apply] re-probes every root before its first write, so the common case is a
     * run that changed NOTHING - and the intent log, which is emitted before each write, is what proves it either way.
     */
    private fun abort(root: RootName, wrote: Boolean, output: CommandOutput): Int {
        output.error("adopt: root '$root' became unavailable mid-run; the run was ABORTED, not half-applied")
        output.error(
            if (wrote) {
                "adopt: the 'intent:' lines above name every write that was attempted - adopt is idempotent, so " +
                    "restore the path and re-run to reconcile them."
            } else {
                "adopt: nothing was written - no file, no id_map row. Restore the path and re-run."
            },
        )
        return 1
    }

    /**
     * A PAGE the plan could not apply (the disk is fine; the page is not what the plan read, or the write faulted).
     * Adopt will not recreate a page someone deleted, nor overwrite an edited one with bytes it derived from a stale
     * read, so it stops - and the remedy is the same one every other adopt abort has: re-run it.
     */
    private fun abortStale(page: String, reason: String, wrote: Boolean, output: CommandOutput): Int {
        output.error("adopt: $page $reason; the run was ABORTED, not half-applied")
        output.error(
            if (wrote) {
                "adopt: the 'intent:' lines above name every write that was attempted - adopt is idempotent, so " +
                    "re-run to reconcile them against the tree as it stands now."
            } else {
                "adopt: no file was written. Re-run once the tree has settled (adopt is idempotent)."
            },
        )
        return 1
    }

    /** A page in the intent log: bare in a single-root install (the pinned legacy line), root-qualified otherwise. */
    private fun label(page: RootedPath, qualified: Boolean): String =
        if (qualified) "${page.root}:${page.path.value}" else page.path.value

    /**
     * Refuses the whole run if any configured root is not there (the `reindex` rule, for a different reason): adopt
     * deletes nothing, so a skipped root is not destructive - it is WORSE THAN USELESS, because the operator ran the
     * one command whose promise is "every page's identity now lives in the tree itself" and walked away believing it.
     * Adopt is idempotent, so restoring the path and re-running costs nothing.
     */
    private fun refuseUnavailableRoots(
        registry: RootRegistry,
        stores: Map<RootName, ContentStore>,
        output: CommandOutput,
    ): Boolean {
        val missing = registry.roots.filter { it.name in stores }.filterNot { stores.getValue(it.name).available() }
        if (missing.isEmpty()) return false
        missing.forEach { root ->
            output.error("adopt: root '${root.name}' is not available (${root.localPath ?: "object backend"})")
        }
        output.error(
            "adopt: refusing to run - the ids of a root it cannot walk would stay in DATA_DIR only, and losing " +
                "DATA_DIR would then cost that root every permalink and citation. Restore the path(s) and re-run " +
                "(adopt is idempotent), or remove the root(s) from the roots {} block if they are gone for good.",
        )
        return true
    }

    private class CommandEventPublicationFailed(cause: Exception) : RuntimeException(cause)

    /**
     * One root's offline tree, carrying the SAME DATA_DIR exclusion the server's store does (ADR-0011): a legally-
     * nested data dir must never be walked as CONTENT, or the CLI indexes plainbase.db/search.db as pages and assets.
     * The server has always excluded it; the two CLIs never did, which was the scan-parity gap.
     */
    private fun localStore(config: PlainbaseConfig, root: Path, name: RootName): LocalContentStore =
        LocalContentStore(root = root, ignoreRules = IgnoreRules(), exclusions = listOf(config.dataDir), rootName = name)

    /** The tree a root's pass actually walked: its own directory locally, the DATA_DIR mirror for an object main. */
    private fun adoptedTree(config: PlainbaseConfig, registry: RootRegistry, root: RootName): Path =
        when (config.storage.backend) {
            StorageBackend.LOCAL ->
                if (root == registry.main.name) config.mainContentRoot() else requireNotNull(registry.byName(root)?.localPath)
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

    /**
     * One root's section. A single-root install keeps the pinned legacy lines VERBATIM ([qualified] false - there is
     * no other root to tell it apart from); a multi-root run names the root each section belongs to, since the same
     * page path can exist in two of them (the `reindex` summary rule).
     */
    private fun render(report: AdoptionPass.Report, root: RootName, tree: Path, qualified: Boolean): String = buildString {
        val subject = if (qualified) "root '$root': ${report.pages.size} page(s)" else "${report.pages.size} page(s)"
        appendLine("adopt: $subject under $tree")
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
