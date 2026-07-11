package com.plainbase

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.repository.SessionRepository
import com.plainbase.domain.repository.SetupTokenRepository
import com.plainbase.domain.repository.UserRepository
import com.plainbase.domain.root.DetachedRoots
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.service.CanonicalUrlBuilder
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.ProposalService
import com.plainbase.domain.service.RebuildScheduler
import com.plainbase.domain.service.UrlAliasRegistry
import com.plainbase.domain.service.WritePipeline
import com.plainbase.frameworks.cli.AdminCommand
import com.plainbase.frameworks.cli.AdoptCommand
import com.plainbase.frameworks.cli.ReindexCommand
import com.plainbase.frameworks.cli.S3SmokeCommand
import com.plainbase.frameworks.config.AuthMode
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.StorageBackend
import com.plainbase.frameworks.filesystem.DataDirLock
import com.plainbase.frameworks.git.GitBundleDr
import com.plainbase.frameworks.koin.checkpointModule
import com.plainbase.frameworks.koin.contentModule
import com.plainbase.frameworks.koin.historyModule
import com.plainbase.frameworks.koin.indexModule
import com.plainbase.frameworks.koin.repositoryModule
import com.plainbase.frameworks.koin.restModule
import com.plainbase.frameworks.koin.searchModule
import com.plainbase.frameworks.koin.securityModule
import com.plainbase.frameworks.ktor.KtorServer
import com.plainbase.frameworks.objectstore.ObjectContentStore
import com.plainbase.frameworks.scheduling.ExecutorAlarm
import com.plainbase.frameworks.spike.NativeSpike
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.context.startKoin
import org.koin.dsl.module
import kotlin.system.exitProcess
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

fun main(args: Array<String>) {
    // kotlin-logging 8.x prints a startup banner from KotlinLogging's class initializer unless
    // KotlinLoggingConfiguration reads this property as false - and it reads it exactly once, at
    // its own class init. Set it before anything touches a logger; both classes initialize at
    // run time under JVM and native image alike, so one programmatic gate covers both binaries.
    System.setProperty("kotlin-logging.logStartupMessage", "false")
    when (args.firstOrNull()) {
        "spike" -> exitProcess(NativeSpike.runAsMain())
        "adopt" -> exitProcess(AdoptCommand.runAsMain(args.drop(1)))
        "reindex" -> exitProcess(ReindexCommand.runAsMain(args.drop(1)))
        "admin" -> exitProcess(AdminCommand.runAsMain(args.drop(1)))
        // Hidden (not in the usage line): the credentialed C0 object-store smoke - operator-run, never CI.
        "s3-smoke" -> exitProcess(S3SmokeCommand.runAsMain(args.drop(1)))
        null, "serve" -> serve()
        else -> {
            System.err.println("Unknown command: ${args.first()} (expected: serve | spike | adopt | reindex | admin)")
            exitProcess(2)
        }
    }
}

private fun serve() {
    // Resolve config BEFORE building the Koin graph (R2-2): a bad config (an IllegalArgumentException from the
    // Q9/auth validation, OR a HOCON ConfigException - malformed plainbase.conf, unresolved ${...}, wrong-typed
    // value) must unwrap cleanly into a `serve:` stderr line + exit(1), never a raw stack trace. Resolving here
    // (not via a Koin `single {}`) is what keeps the error unwrapped - Koin would wrap it in an
    // InstanceCreationException that dodges the funnel. The resolved instance is the graph's single source.
    val config = PlainbaseConfig.loadForCommand("serve") ?: exitProcess(1)
    val koin = startKoin {
        modules(
            module { single { config } }, contentModule, repositoryModule, securityModule, indexModule,
            checkpointModule, searchModule, historyModule, restModule,
        )
    }.koin

    // Fail fast, actionably: a missing CONTENT_DIR (or an incomplete object-mode key matrix) must name
    // itself as a `serve:` refusal, not raw-stack-trace out of the `require(...)` - the same funnel as the
    // config load and the gates below, never silently serving an empty tree.
    try {
        config.requireContentDir()
    } catch (e: IllegalArgumentException) {
        System.err.println("serve: ${e.message}")
        exitProcess(1)
    }
    // Q9/Q10 ignored-key warnings (never fatal): local mode names any configured-but-ignored
    // storage.object.* keys; object mode warns when CONTENT_DIR was explicitly set.
    config.storageWarnings().forEach { logger.warn { it } }
    // Multi-root C1 warnings (never fatal): an ignored explicit CONTENT_DIR, extras a single-root
    // build cannot serve yet, and unavailable extra-root paths (ADR-0011 D11-D13).
    config.rootsWarnings().forEach { logger.warn { it } }
    // ADR-0008 fail-closed bind guard: config-only, so it fails BEFORE the heavier git-gate/lock/rebuild work.
    // Same idiom as the gates that follow (System.err + exitProcess(1), never a thrown stack trace) - a bind
    // misconfiguration is an operator-actionable startup refusal, not an argument-precondition bug.
    config.bindGuardRefusal()?.let {
        System.err.println("serve: $it")
        exitProcess(1)
    }
    if (config.auth.insecureHttp && config.isNonLoopbackBind()) {
        logger.warn {
            "PLAINBASE_INSECURE_HTTP set: serving credentials over PLAINTEXT on ${config.host} - anyone on the " +
                "network can capture them (ADR-0008)"
        }
    } else if (config.auth.insecureHttp) {
        logger.info {
            "PLAINBASE_INSECURE_HTTP set but the bind is loopback (${config.host}) - the override is redundant here " +
                "(loopback HTTP is always allowed)"
        }
    }
    // Git gate-check (ADR-0006): AFTER requireContentDir() and BEFORE the lock/rebuild/
    // reconcile block - rebuild() and reconcileDirtyPages() trigger commits, so a "git missing" failure
    // must fire FIRST with an actionable message, never as a doomed commit's stack trace. NoOp is a clean
    // no-op. Mirror the DataDirLock failure idiom: System.err + exitProcess(1), never a thrown trace.
    try {
        koin.get<HistoryProvider>().gateCheck()
    } catch (e: Exception) {
        System.err.println("serve: ${e.message}")
        exitProcess(1)
    }
    // Rev-3.4 DR nudge: an object boot that survives the gate check with git DISABLED (`git.enabled`
    // unset/false) has no commit-grained history, so name the exposure ONCE (backups are operator-owned).
    // Since C5, `git.enabled=true` in object mode wires a real GitCliHistoryProvider + bundle DR, so this
    // WARN no longer fires unconditionally on every object boot - only the git-disabled ones.
    objectModeGitDisabledWarning(config, koin.get<HistoryProvider>())?.let { logger.warn { it } }
    // Hold the DATA_DIR advisory lock for the server's whole lifetime, acquired
    // BEFORE any rebuild/watcher registration. A second server on the same DATA_DIR - or an offline
    // `plainbase reindex` while this one runs - is refused, never silently racing search.db writes.
    val lock = DataDirLock.tryAcquire(config.dataDir)
    if (lock == null) {
        System.err.println("serve: another Plainbase process is holding ${config.dataDir} - stop it before starting a second instance")
        exitProcess(1)
    }
    // Everything past the lock runs INSIDE the try/finally so the lock ALWAYS releases - including a
    // prepare() failure (a forced-on Git hitting a read-only/disk-full content dir, or a `git init` fault),
    // which must surface as the same actionable `serve:` message as gateCheck(), never a raw stack trace
    // that also leaks the lock. Startup ORDER is unchanged: gateCheck (pre-lock) → lock → prepare() →
    // watcher → rebuild.
    try {
        // Multi-root C2 boot guard (ADR-0011 D1/D15): bindings under roots absent from the config
        // WARN; a nonempty id_map ENTIRELY disjoint from the config refuses to serve. ORDERING
        // CONSTRAINT: this repository get is the process's FIRST app-DB open, which runs the
        // migration - it must stay AFTER DataDirLock.tryAcquire (a concurrent second instance
        // racing that first-open migration is exactly what the lock prevents) and satisfies the
        // fix-D never-open-before-the-lock rule below the OBJECT branch. C4 RE-VERIFY: the guard
        // runs BEFORE the object-mode hydrate/git-DR branch; when C4 wires multi-root object
        // storage, confirm the guard still reads post-restore id_map state.
        detachedRootsRefusal(
            koin.get<IdMapRepository>().roots(),
            // The REGISTRY, not config.roots.list: one runtime topology snapshot for the guard to
            // agree with (identical names by construction).
            koin.get<RootRegistry>().roots.map { it.name }.toSet(),
        )?.let { refusal ->
            lock.close() // exitProcess skips the outer finally (the hydrate-failure arm's shape)
            System.err.println("serve: $refusal")
            exitProcess(1)
        }
        // Object mode: hydrate the DATA_DIR mirror from the bucket FIRST in the lock region, strictly
        // BEFORE the first rebuild() and reconcileDirtyPages() below - both read the post-hydrate
        // mirror through the port, which is what makes a retained-mark recovery commit-or-drift-skip
        // correctly. The first LIST is also the R16 fail-closed TLS/signature self-check; its refusal
        // surfaces via the same System.err + exit(1) idiom as the other gates.
        //
        // C5: when git is enabled, a bundle-DR restore runs strictly BEFORE hydrate, and the boot
        // reconcile strictly AFTER - both in this same lock region, hydrate/hydrate's mirror walk. Nested
        // behind `config.git.enabled == true` so a git-DISABLED object boot never constructs `GitBundleDr`
        // (the R9 lazy-wiring discipline: git-disabled object mode must stay byte-identical to the
        // hydrate-only C4 boot).
        if (config.storage.backend == StorageBackend.OBJECT) {
            try {
                if (config.git.enabled == true) {
                    val bundleDr = koin.get<GitBundleDr>()
                    val restored = bundleDr.restore() // gate + FORK-2 sentinel truth table (2b); a non-404 GET failure aborts boot
                    koin.get<ObjectContentStore>().hydrate(strict = restored.isRestored) // restore path is STRICT (FORK 1)
                    bundleDr.reconcileBootCommit(restored) // ONE plumbing commit or no-op; clears the sentinel
                } else {
                    koin.get<ObjectContentStore>().hydrate()
                }
            } catch (e: Exception) {
                // exitProcess skips the outer finally - release the lock explicitly (the prepare() idiom).
                lock.close()
                System.err.println("serve: ${e.message}")
                exitProcess(1)
            }
        }
        val now = Clock.System.now()
        // Startup-time prune, INSIDE the lock so no other process races the DB: drop dead session/setup-token
        // rows that accumulate in the insert/update-only tables. Once at boot, never per-write (write amplification).
        koin.get<SessionRepository>().prune(now)
        koin.get<SetupTokenRepository>().prune(now)
        // A4b: load-or-generate the proxy-CSRF HMAC server key NOW - inside the lock - so a concurrent boot can never
        // race a double-generate into app_meta. Resolving the ProxyCsrf single forces the key load here rather
        // than relying on the lazy RouteContext resolution timing.
        koin.get<com.plainbase.frameworks.security.ProxyCsrf>()
        // A4a: on an empty / no-enabled-admin builtin DB, emit ONLY a NON-SECRET hint - NEVER a token on
        // the boot path (stdout/stderr are the scraped log under docker/systemd). The secret comes ONLY from the CLI.
        // Reads `countEnabledAdmins` only AFTER the lock is held + validated (fix D: never open/migrate the DB before
        // the lock, so a second process can't open+migrate before losing the lock race).
        if (config.auth.mode == AuthMode.BUILTIN && koin.get<UserRepository>().countEnabledAdmins() == 0L) {
            logger.warn { "Setup required: run `plainbase admin setup-token` to mint the first-admin bootstrap token" }
        }
        // Ready the history backing store now - AFTER the lock validates/owns DATA_DIR (never
        // touch it before the lock; this is why repo init was lazy) and BEFORE the watcher and the first
        // rebuild. The startup rebuild reads (lastCommits) before any save commits, and `git -C workTree log`
        // walks UP to an ancestor `.git` when CONTENT_DIR has none - so a forced-on content root with no own
        // repo would otherwise abort serve (plain dir) or read the wrong ancestor repo. NoOp is a no-op.
        try {
            koin.get<HistoryProvider>().prepare()
        } catch (e: Exception) {
            // exitProcess terminates the JVM without running the outer finally, so release the lock
            // explicitly here - otherwise a forced-on Git failure would leak it in embedded/test use.
            lock.close()
            System.err.println("serve: ${e.message}")
            exitProcess(1)
        }
        val builder = koin.get<IndexBuilder>()
        // §B2 startup ordering, no unwatched window: the watcher registers BEFORE the first rebuild.
        // Events arriving while the initial build is in flight coalesce into at most one follow-up
        // rebuild via the scheduler's single-flight dirty flag.
        val scheduler = RebuildScheduler(rebuild = { builder.rebuild() }, alarm = ExecutorAlarm())
        val watch = koin.get<ContentStore>().watch { scheduler.schedule() }
        try {
            // Full scan at startup builds the snapshot (§C4); the rescan route rebuilds on demand. The
            // rebuild also self-heals the index for any page left dirty by a prior interrupted save.
            builder.rebuild()
            // PB-WRITE-1 fix H: write-ahead recovery of a prior interrupted save, after the index is whole
            // and before serving - drift-skips a page whose on-disk bytes changed since the crash.
            koin.get<WritePipeline>().reconcileDirtyPages()
            // P1b: inspect-then-decide crash-recovery of a prior interrupted APPLY, after the index is whole + after
            // reconcileDirtyPages may have re-committed a crashed dirty page - it resolves each APPLYING row's
            // CURRENT pageId path and stamps APPLIED (write landed) or PENDING (it didn't). Cannot race a live apply.
            koin.get<ProposalService>().reconcileApplying()
            // Multi-root C3 boot guard (ADR-0011 D3(a)): a top-level 'main' URL segment in the main
            // root makes legacy /docs/main/... links indistinguishable from root-qualified URLs.
            // Deliberately AFTER both reconciles - each can WRITE pages (a crash-recovered save/apply
            // of a main/... create) and republish, so the guard must judge the LAST pre-serve snapshot.
            mainRootUrlCollisionRefusal(builder.current)?.let { refusal ->
                lock.close() // exitProcess skips the outer finally (the hydrate-failure arm's shape)
                System.err.println("serve: $refusal")
                exitProcess(1)
            }
            deadLegacyAliasWarning(koin.get<UrlAliasRegistry>().all())?.let { logger.warn { it } }
            KtorServer(config, koin.get()).start(wait = true)
        } finally {
            watch.close() // stops the object-mode poll thread BEFORE the transport it uses is closed
            scheduler.close()
            // Release the object-store transport (the ktor HttpClient) on shutdown; the poll is already
            // stopped by watch.close() above. LOCAL mode has no transport to close.
            if (config.storage.backend == StorageBackend.OBJECT) {
                // C5: the final graceful-shutdown bundle ship BEFORE the transport it needs closes; same
                // `git.enabled` guard as the boot-side wiring so a git-disabled object boot never touches
                // GitBundleDr here either. Order load-bearing.
                if (config.git.enabled == true) koin.get<GitBundleDr>().close()
                koin.get<ObjectContentStore>().close()
            }
        }
    } finally {
        lock.close()
    }
}

/**
 * The C2 boot guard's serve() shape (ADR-0011 D15): evaluates the pure [DetachedRoots] verdict,
 * logs the partial-detachment WARN itself, and returns the fatal refusal text (or null to serve).
 * A synthesized legacy config runs it with configured = {main}, and every pre-C2 DB migrates to
 * all-'main' rows, so it is trivially Clean there. The remediation is config-first, then TARGETED
 * and backup-first - it must never advise deleting plainbase.db, which also holds the security and
 * review truth (users, sessions, API tokens, roles, proposals, the audit log).
 */
internal fun detachedRootsRefusal(bound: Set<RootName>, configured: Set<RootName>): String? =
    when (val verdict = DetachedRoots.evaluate(bound, configured)) {
        DetachedRoots.Verdict.Clean -> null
        is DetachedRoots.Verdict.Detached -> {
            logger.warn {
                "id_map holds page bindings under root(s) absent from roots{}: ${verdict.roots.sortedNames()}. " +
                    "Their pages are not served and their permalinks stay dormant until a root with the same name is " +
                    "restored (root names are permanent; re-adding restores bindings subject to the ADR-0011 D2 supersede rules)."
            }
            null
        }
        is DetachedRoots.Verdict.AllDetached ->
            "REFUSING TO SERVE: every page binding in this DATA_DIR belongs to root(s) absent from the " +
                "configuration (bound: ${bound.sortedNames()}; configured: ${configured.sortedNames()}). This DATA_DIR likely " +
                "belongs to a different deployment, or the roots{} block was rewritten wholesale. Remedies, in order: " +
                "(1) fix roots{} so the bound name(s) above are declared again (root names are permanent identifiers), " +
                "or point DATA_DIR at the right directory; (2) if the removal is intentional and losing those roots' " +
                "permalinks and old-URL redirects is accepted: back up DATA_DIR first, then delete only the detached " +
                "rows, per root name, from the five identity tables - e.g. " +
                "sqlite3 DATA_DIR/plainbase.db \"DELETE FROM id_map WHERE root='<name>'\" " +
                "(repeat for url_alias, identity_issue, page_checkpoint, dirty_page). Do NOT delete plainbase.db " +
                "itself: it also holds users, sessions, API tokens, roles, proposals, and the audit log."
    }

private fun Set<RootName>.sortedNames(): String = map { it.value }.sorted().joinToString(", ")

/**
 * The C3 boot guard (ADR-0011 D3(a)), pure like [detachedRootsRefusal]: the fatal refusal text when
 * the MAIN root's built snapshot contains a top-level URL segment literally `main` - a page URL
 * path, a folder URL (the [CanonicalUrlBuilder.folderUrlPaths] truth `TreeBuilder` consumes, never
 * re-derived slugification), or an asset path (assets mirror the redirect grammar, so
 * `/assets/main/...` is equally ambiguous) - or null to serve. Segment-level detection catches the
 * whole equivalence class the URL grammar cares about (a dir `Main` slugifies to the same colliding
 * segment, a frontmatter `slug: main` page, an asset dir) and works in object mode where there is
 * no local FS to list. Main only: an extra root's top-level `main` dir is harmless (its URLs are
 * `/docs/{extra}/main/...`, root segment first - no legacy form ever pointed there). Boot-only is
 * sufficient: a `main/` dir created at RUNTIME mints self-consistent `/docs/main/main/...` URLs
 * with no pre-existing legacy links to break; the guard exists to stop the UPGRADE of a corpus
 * whose circulating `/docs/main-dir/...` links would otherwise silently re-resolve.
 */
internal fun mainRootUrlCollisionRefusal(snapshot: PageIndex): String? {
    val main = RootName.MAIN.value
    val section = snapshot.section(RootName.MAIN)
    val offenders =
        section.pages.mapNotNull { page -> page.urlPath?.value } +
            CanonicalUrlBuilder.folderUrlPaths(section.folders).values.mapNotNull { it?.value } +
            section.assets.map { it.value }
    val colliding = offenders.filter { it == main || it.startsWith("$main/") }.distinct()
    if (colliding.isEmpty()) return null
    return "REFUSING TO SERVE: the main root contains top-level URL segment '$main' - since multi-root " +
        "(ADR-0011 D1/D3) '$main' is the RESERVED root segment, so an old /docs/$main/... deep link is " +
        "indistinguishable from a root-qualified URL and would silently re-resolve to the wrong page. " +
        "Colliding entries: ${boundedPathList(colliding)}. Rename the directory (or the frontmatter `slug:` / " +
        "_folder.yaml `slug:` source producing the segment) so no top-level URL segment is '$main'. This applies " +
        "even to a fresh corpus - the reservation is deterministic, not upgrade-conditional. NOTE: renaming " +
        "permanently forfeits any circulating /docs/$main/... deep links and their recorded aliases; after the " +
        "rename those old links answer not-found instead of redirecting."
}

/**
 * The companion boot WARN (never a refusal - the corpus itself is fine): main-root `url_alias`
 * rows whose stored URL path starts with the reserved `main` segment. If such a row predates the
 * multi-root upgrade, its legacy link is PERMANENTLY dead: `/docs/main/x` now resolves as
 * root `main` + path `x`, so the recorded `(main, main/x)` row is only reachable at
 * `/docs/main/main/x` - a URL that never circulated pre-upgrade. The wording is deliberately
 * hedged: a row minted AFTER the upgrade (a runtime-created `main/` dir whose page later moved) is
 * legitimately reachable at that doubled URL, and the row alone cannot tell the two apart.
 */
internal fun deadLegacyAliasWarning(aliases: Map<RootedPath, PageId>): String? {
    val main = RootName.MAIN.value
    val dead = aliases.keys
        .filter { it.root == RootName.MAIN && it.path.segments.first() == main }
        .map { it.path.value }
    if (dead.isEmpty()) return null
    return "url_alias holds ${dead.size} main-root row(s) whose URL path starts with the reserved '$main' " +
        "segment: ${boundedPathList(dead)}. If these rows predate the multi-root upgrade, their old " +
        "/docs/<path> links are dead: the reserved root segment routes /docs/$main/x to ($main, x), so such a " +
        "row is only reachable at /docs/$main/<path> - a URL that never circulated before the upgrade. Rows " +
        "recorded after the upgrade are unaffected."
}

/** The deterministic, testable offender bound the two texts above share: first 10, lexicographic, `(+N more)`. */
private fun boundedPathList(paths: List<String>): String {
    val sorted = paths.sorted()
    val overflow = sorted.size - 10
    return sorted.take(10).joinToString(", ") + if (overflow > 0) " (+$overflow more)" else ""
}

/**
 * The single rev-3.4 backup-guidance WARN (pure accessor, the [PlainbaseConfig.bindGuardRefusal]
 * idiom): non-null exactly when an object-mode boot runs without git history, i.e. point-in-time
 * content recovery is entirely the operator's backup schedule. `serve()` logs it ONCE; there is no
 * snapshot or manifest writer (backups are operator-owned by decision).
 */
internal fun objectModeGitDisabledWarning(config: PlainbaseConfig, history: HistoryProvider): String? =
    if (config.storage.backend == StorageBackend.OBJECT && !history.enabled) {
        "object mode with git disabled: no commit-grained history; content point-in-time recovery is " +
            "your backup schedule (see the backup guidance)"
    } else {
        null
    }
